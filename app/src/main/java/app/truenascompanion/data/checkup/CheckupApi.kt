package app.truenascompanion.data.checkup

import app.truenascompanion.data.api.AccountsApi
import app.truenascompanion.data.api.CertificatesApi
import app.truenascompanion.data.api.NasSystemApi
import app.truenascompanion.data.api.ProtectionApi
import app.truenascompanion.data.api.StorageApi
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.arr
import app.truenascompanion.data.api.bool
import app.truenascompanion.data.api.long
import app.truenascompanion.data.api.obj
import app.truenascompanion.data.api.parseDate
import app.truenascompanion.data.api.prim
import app.truenascompanion.data.api.str
import app.truenascompanion.data.model.CertKind
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Gathers [CheckupInput] with read-only calls (25.10): `pool.dataset.query`, `pool.snapshottask.query`,
 * `replication.query`, `cloudsync.query`, `rsynctask.query`, `pool.query`, `pool.scrub.query`, the SMART cron jobs,
 * `sharing.smb.query` (`options.guestok`), `sharing.nfs.query`, `certificate.query`, `api_key.query`, `ssh.config`,
 * `service.query`, `user.query`, `auth.me`, `auth.twofactor.config`, `update.status` and `boot.get_state`.
 * Each part fails on its own (null = "couldn't check"), so a limited account still gets a partial checkup.
 */
class CheckupApi(private val api: TrueNasApi) {

    suspend fun gather(lastConfigBackup: Long?): CheckupInput = coroutineScope {
        fun <T> part(block: suspend () -> T) = async { runCatching { block() }.getOrNull() }
        val protection = ProtectionApi(api)
        val datasetsAll = part { api.datasets() }
        val snap = part {
            protection.snapshotTasks().map {
                SnapTaskIn(it.id, it.dataset, it.recursive, it.exclude, it.enabled, it.state?.state == "ERROR", it.state?.error?.lineSequence()?.firstOrNull())
            }
        }
        val repl = part { rows("replication.query") }
        val cloud = part { rows("cloudsync.query") }
        val rsync = part { rows("rsynctask.query") }
        val pools = part { api.pools() }
        val scrubs = part { protection.scrubTasks() }
        val smart = part { protection.smartSchedules().count { it.enabled } }
        val smb = part { rows("sharing.smb.query").map { o -> SmbIn(o.str("name") ?: "?", o.bool("enabled") ?: true, smbGuest(o)) } }
        val nfs = part { StorageApi(api).nfsShares().map { NfsIn(it.path, it.enabled, it.networks.isEmpty() && it.hosts.isEmpty()) } }
        val certs = part { CertificatesApi(api).certificates().filter { it.kind == CertKind.CERTIFICATE }.map { CertIn(it.name, it.untilMillis) } }
        val keys = part {
            rows("api_key.query").map { o ->
                ApiKeyIn(o.str("name") ?: "key", o.str("username"), parseDate(o["created_at"]), parseDate(o["expires_at"]), o.bool("revoked") ?: false)
            }
        }
        val sshCfg = part { api.rpc("ssh.config").obj() }
        val services = part { api.services() }
        val users = part {
            AccountsApi(api).users().filter { it.local }.map {
                UserIn(it.username, it.uid, "FULL_ADMIN" in it.roles, it.locked, it.passwordDisabled, it.sshPasswordEnabled, it.twoFactor)
            }
        }
        val me = part { api.rpc("auth.me").obj()?.str("pw_name") }
        val twoFa = part { api.rpc("auth.twofactor.config").obj()?.bool("enabled") }
        val update = part { NasSystemApi(api).updateStatus() }
        val boot = part {
            api.rpc("boot.get_state").obj()?.let { o -> BootIn(o.bool("healthy") ?: false, o.str("status"), o.long("size"), o.long("allocated")) }
        }

        val ds = datasetsAll.await()?.filter { !it.isSystem && it.depth >= 1 }?.map { it.id }?.sorted()
        val allIds = datasetsAll.await()?.map { it.id }.orEmpty()
        val offBox = run {
            val r = repl.await(); val c = cloud.await(); val s = rsync.await()
            if (r == null && c == null && s == null) null else buildList<OffBoxIn> {
                r?.forEach { o ->
                    if (o.str("direction") == "PUSH" && o.str("transport") != "LOCAL") {
                        val src = o["source_datasets"].arr()?.mapNotNull { it.prim()?.contentOrNull }.orEmpty()
                        add(OffBoxIn("Replication ${o.str("name") ?: ""}".trim(), src, o.bool("recursive") ?: false, o.bool("enabled") ?: true))
                    }
                }
                c?.forEach { o ->
                    if (o.str("direction") == "PUSH") pathDataset(o.str("path"), allIds)?.let {
                        add(OffBoxIn("Cloud sync ${o.str("description") ?: ""}".trim(), listOf(it), true, o.bool("enabled") ?: true))
                    }
                }
                s?.forEach { o ->
                    if (o.str("direction") == "PUSH") pathDataset(o.str("path"), allIds)?.let {
                        add(OffBoxIn("Rsync ${o.str("desc") ?: ""}".trim(), listOf(it), true, o.bool("enabled") ?: true))
                    }
                }
            }
        }
        val scrubList = scrubs.await()
        val poolIn = pools.await()?.let { ps ->
            ps.map { p ->
                val scrubEnd = if (p.scanFunction.equals("SCRUB", true) && p.scanState.equals("FINISHED", true)) p.scanEndMillis else null
                PoolIn(p.name, scrubEnd, scrubList?.any { it.poolName == p.name && it.enabled } ?: false)
            }
        }
        val svc = services.await()
        val u = update.await()
        CheckupInput(
            datasets = ds,
            snapshotTasks = snap.await(),
            offBox = offBox,
            pools = if (scrubList == null) null else poolIn,
            smartSchedulesEnabled = smart.await(),
            smb = smb.await(),
            nfs = nfs.await(),
            certificates = certs.await(),
            apiKeys = keys.await(),
            sshRunning = svc?.firstOrNull { it.service == "ssh" }?.running ?: svc?.let { false },
            sshPasswordAuth = sshCfg.await()?.bool("passwordauth"),
            users = users.await(),
            signedInUser = me.await(),
            twoFactorEnabled = twoFa.await(),
            updateAvailable = u?.let { if (it.updateAvailable) true else if (it.upToDate) false else null },
            updateVersion = u?.newVersion,
            boot = boot.await(),
            lastConfigBackup = lastConfigBackup,
        )
    }

    private suspend fun rows(method: String): List<JsonObject> = api.rpc(method).arr()?.mapNotNull { it.obj() } ?: emptyList()

    companion object {
        /** `options.guestok` (25.10 share options); older layouts had a top-level `guestok`. */
        fun smbGuest(o: JsonObject): Boolean = o["options"].obj()?.bool("guestok") ?: o.bool("guestok") ?: false

        /** The dataset holding a `/mnt/...` path (longest match), or null. */
        fun pathDataset(path: String?, datasetIds: List<String>): String? {
            val p = path?.trimEnd('/') ?: return null
            if (!p.startsWith("/mnt/")) return null
            val rel = p.removePrefix("/mnt/")
            return datasetIds.filter { rel == it || rel.startsWith("$it/") }.maxByOrNull { it.length }
        }
    }
}
