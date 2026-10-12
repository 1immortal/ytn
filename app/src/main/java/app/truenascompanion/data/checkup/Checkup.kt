package app.truenascompanion.data.checkup

import kotlinx.serialization.Serializable

/**
 * 1.11.0: Protection checkup (Storage › Backup). A scored checklist built from read-only queries; every finding says
 * what it means in plain words and links to the screen that fixes it. Nothing here changes the NAS.
 *
 * This file is pure (no Android, no network) so every rule is unit tested; [CheckupApi] gathers the inputs.
 */
@Serializable
enum class CheckMark { HIDDEN, ACCEPTED }

@Serializable
data class CheckupSummary(val score: Int, val issues: Int, val at: Long)

enum class CheckStatus { PASS, WARN, FAIL, UNKNOWN }

/** Where the "Fix" button goes. */
enum class CheckFix(val label: String) {
    SNAPSHOT_TASKS("Snapshot tasks"),
    REPLICATION("Replication & cloud sync"),
    SCRUBS("Scrub schedules"),
    SMART("SMART schedules"),
    SHARES("Shares"),
    CERTIFICATES("Certificates"),
    API_KEYS("API keys (web UI)"),
    SSH("SSH settings"),
    ACCOUNTS("Users"),
    TWO_FACTOR("Two-factor (web UI)"),
    UPDATES("Updates"),
    BOOT("Boot environments"),
    CONFIG_BACKUP("Back up config"),
}

enum class CheckId(val title: String, val weight: Int, val fix: CheckFix, val why: String) {
    SNAPSHOT_COVERAGE("Every dataset has periodic snapshots", 3, CheckFix.SNAPSHOT_TASKS,
        "Snapshots let you undo deletions, ransomware and mistakes. Datasets without a periodic snapshot task have no way back."),
    OFF_BOX("Snapshots are copied off this NAS", 3, CheckFix.REPLICATION,
        "Snapshots live on the same disks. If the pool is lost (fire, theft, failed disks) they go with it. Replication or cloud sync keeps a copy elsewhere."),
    SNAPSHOT_TASKS("Snapshot tasks are on and working", 2, CheckFix.SNAPSHOT_TASKS,
        "A disabled or failing task silently stops taking snapshots."),
    SCRUBS("Pools are scrubbed regularly", 2, CheckFix.SCRUBS,
        "A scrub reads every block and repairs silent corruption while a good copy still exists. Monthly is the usual rhythm."),
    SMART("Disks have SMART tests scheduled", 2, CheckFix.SMART,
        "SMART self-tests catch failing disks early, before a second failure costs data."),
    SMB_GUEST("No SMB share allows guest access", 2, CheckFix.SHARES,
        "Guest access lets anyone on the network open the share without a password."),
    NFS_OPEN("No NFS share is open to everyone", 2, CheckFix.SHARES,
        "An NFS share with no allowed networks or hosts can be mounted by any machine that can reach the NAS."),
    CERTIFICATES("Certificates aren't about to expire", 2, CheckFix.CERTIFICATES,
        "An expired certificate breaks HTTPS and anything that checks it (apps, replication, the web UI)."),
    API_KEYS("No stale API keys", 1, CheckFix.API_KEYS,
        "Old keys without an expiry date are easy to forget and stay valid forever. TrueNAS 25.10 doesn't record when a key was last used, so this looks at age and expiry only."),
    SSH_ROOT_PASSWORD("Admins can't sign in to SSH with a password", 3, CheckFix.SSH,
        "Password sign-in for root or admin accounts over SSH invites brute-force attacks. Use SSH keys instead."),
    TWO_FACTOR("Two-factor sign-in is on for admins", 2, CheckFix.TWO_FACTOR,
        "With two-factor authentication a stolen password alone isn't enough to take over the NAS."),
    UPDATE("TrueNAS is up to date", 1, CheckFix.UPDATES,
        "Updates fix security issues and bugs. Back up the configuration first."),
    BOOT_POOL("Boot pool is healthy with free space", 3, CheckFix.BOOT,
        "TrueNAS runs from the boot pool. If it's degraded or full, updates fail and the NAS may not start."),
    CONFIG_BACKUP("Configuration backed up recently", 2, CheckFix.CONFIG_BACKUP,
        "A configuration backup restores users, shares, tasks and settings onto a reinstalled or replacement NAS in minutes."),
}

data class CheckResult(val id: CheckId, val status: CheckStatus, val summary: String, val details: List<String> = emptyList())

// --- Inputs (null list = couldn't read it, e.g. missing permission) ---

data class SnapTaskIn(val id: Int, val dataset: String, val recursive: Boolean, val exclude: List<String>, val enabled: Boolean, val failed: Boolean, val error: String? = null)
/** A task that copies data off this NAS (push replication not to the same box, push cloud sync, push rsync). */
data class OffBoxIn(val label: String, val datasets: List<String>, val recursive: Boolean, val enabled: Boolean)
data class PoolIn(val name: String, val lastScrubEndMillis: Long?, val scrubScheduled: Boolean)
data class SmbIn(val name: String, val enabled: Boolean, val guest: Boolean)
data class NfsIn(val path: String, val enabled: Boolean, val open: Boolean)
data class CertIn(val name: String, val untilMillis: Long?)
data class ApiKeyIn(val name: String, val username: String?, val createdMillis: Long?, val expiresMillis: Long?, val revoked: Boolean)
data class UserIn(val username: String, val uid: Int, val admin: Boolean, val locked: Boolean, val passwordDisabled: Boolean, val sshPassword: Boolean, val twoFactor: Boolean)
data class BootIn(val healthy: Boolean, val status: String?, val size: Long?, val allocated: Long?)

data class CheckupInput(
    val datasets: List<String>? = null,
    val snapshotTasks: List<SnapTaskIn>? = null,
    val offBox: List<OffBoxIn>? = null,
    val pools: List<PoolIn>? = null,
    val smartSchedulesEnabled: Int? = null,
    val smb: List<SmbIn>? = null,
    val nfs: List<NfsIn>? = null,
    val certificates: List<CertIn>? = null,
    val apiKeys: List<ApiKeyIn>? = null,
    val sshRunning: Boolean? = null,
    val sshPasswordAuth: Boolean? = null,
    val users: List<UserIn>? = null,
    val signedInUser: String? = null,
    val twoFactorEnabled: Boolean? = null,
    val updateAvailable: Boolean? = null,
    val updateVersion: String? = null,
    val boot: BootIn? = null,
    /** From this phone's settings; [lastConfigBackupKnown] false when there's no server. */
    val lastConfigBackup: Long? = null,
    val lastConfigBackupKnown: Boolean = true,
)

data class CheckupScore(val score: Int, val counted: Int, val issues: Int, val unknown: Int) {
    val grade: String get() = when {
        counted == 0 -> "Not checked"
        score >= 90 -> "Well protected"
        score >= 70 -> "Mostly protected"
        score >= 50 -> "Needs attention"
        else -> "At risk"
    }
}

object Checkup {
    const val DAY = 24L * 60 * 60 * 1000
    const val SCRUB_MAX_DAYS = 35
    const val CERT_WARN_DAYS = 30
    const val API_KEY_STALE_DAYS = 365
    const val BACKUP_MAX_DAYS = 30
    const val BOOT_MAX_USED = 0.80
    private const val LIST_MAX = 8

    fun covered(ds: String, t: SnapTaskIn): Boolean = covers(ds, t.dataset, t.recursive, t.exclude)
    fun covered(ds: String, o: OffBoxIn): Boolean = o.datasets.any { covers(ds, it, o.recursive, emptyList()) }

    private fun covers(ds: String, root: String, recursive: Boolean, exclude: List<String>): Boolean =
        ds == root || (recursive && ds.startsWith("$root/") && exclude.none { ds == it || ds.startsWith("$it/") })

    private fun list(items: List<String>): List<String> =
        if (items.size <= LIST_MAX) items else items.take(LIST_MAX) + "…and ${items.size - LIST_MAX} more"

    private fun plural(n: Int, one: String, many: String = one + "s") = "$n " + if (n == 1) one else many

    private fun unknown(id: CheckId) = CheckResult(id, CheckStatus.UNKNOWN, "Couldn't read this (the signed-in account may lack permission).")

    fun evaluate(i: CheckupInput, now: Long): List<CheckResult> = CheckId.entries.map { id ->
        when (id) {
            CheckId.SNAPSHOT_COVERAGE -> {
                val ds = i.datasets; val tasks = i.snapshotTasks
                if (ds == null || tasks == null) unknown(id) else {
                    val bare = ds.filter { d -> tasks.none { it.enabled && covered(d, it) } }
                    when {
                        ds.isEmpty() -> CheckResult(id, CheckStatus.PASS, "No datasets to protect yet.")
                        bare.isEmpty() -> CheckResult(id, CheckStatus.PASS, "All ${plural(ds.size, "dataset")} have a snapshot task.")
                        else -> CheckResult(id, CheckStatus.FAIL, "${plural(bare.size, "dataset")} without periodic snapshots.", list(bare))
                    }
                }
            }
            CheckId.OFF_BOX -> {
                val ds = i.datasets; val tasks = i.snapshotTasks; val off = i.offBox
                if (ds == null || tasks == null || off == null) unknown(id) else {
                    val snapped = ds.filter { d -> tasks.any { it.enabled && covered(d, it) } }
                    val local = snapped.filter { d -> off.none { it.enabled && covered(d, it) } }
                    when {
                        snapped.isEmpty() -> CheckResult(id, CheckStatus.WARN, "Nothing is snapshotted yet, so nothing is copied off the NAS.")
                        local.isEmpty() -> CheckResult(id, CheckStatus.PASS, "Every snapshotted dataset is replicated or synced elsewhere.")
                        else -> CheckResult(id, CheckStatus.WARN, "${plural(local.size, "dataset")} only kept on this NAS.", list(local))
                    }
                }
            }
            CheckId.SNAPSHOT_TASKS -> {
                val tasks = i.snapshotTasks
                if (tasks == null) unknown(id) else {
                    val off = tasks.filter { !it.enabled }.map { "${it.dataset}: disabled" }
                    val bad = tasks.filter { it.enabled && it.failed }.map { "${it.dataset}: failed" + (it.error?.let { e -> " ($e)" } ?: "") }
                    when {
                        tasks.isEmpty() -> CheckResult(id, CheckStatus.WARN, "No snapshot tasks yet.")
                        bad.isNotEmpty() -> CheckResult(id, CheckStatus.FAIL, "${plural(bad.size, "task")} failed" + if (off.isNotEmpty()) ", ${off.size} disabled." else ".", list(bad + off))
                        off.isNotEmpty() -> CheckResult(id, CheckStatus.WARN, "${plural(off.size, "task")} disabled.", list(off))
                        else -> CheckResult(id, CheckStatus.PASS, "All ${plural(tasks.size, "task")} enabled and working.")
                    }
                }
            }
            CheckId.SCRUBS -> {
                val pools = i.pools
                if (pools == null) unknown(id) else {
                    val issues = pools.mapNotNull { p ->
                        val age = p.lastScrubEndMillis?.let { ((now - it) / DAY).toInt() }
                        when {
                            !p.scrubScheduled -> "${p.name}: no scrub schedule"
                            age == null -> "${p.name}: never scrubbed"
                            age > SCRUB_MAX_DAYS -> "${p.name}: last scrub $age days ago"
                            else -> null
                        }
                    }
                    when {
                        pools.isEmpty() -> CheckResult(id, CheckStatus.PASS, "No data pools.")
                        issues.isEmpty() -> CheckResult(id, CheckStatus.PASS, "All pools scheduled and scrubbed in the last $SCRUB_MAX_DAYS days.")
                        else -> CheckResult(id, CheckStatus.WARN, "${plural(issues.size, "pool")} need a scrub or schedule.", list(issues))
                    }
                }
            }
            CheckId.SMART -> when (val n = i.smartSchedulesEnabled) {
                null -> unknown(id)
                0 -> CheckResult(id, CheckStatus.WARN, "No SMART test schedule.")
                else -> CheckResult(id, CheckStatus.PASS, "${plural(n, "schedule")} enabled.")
            }
            CheckId.SMB_GUEST -> i.smb?.let { s ->
                val g = s.filter { it.enabled && it.guest }.map { it.name }
                if (g.isEmpty()) CheckResult(id, CheckStatus.PASS, "No guest shares.") else CheckResult(id, CheckStatus.WARN, "${plural(g.size, "share")} allow guests.", list(g))
            } ?: unknown(id)
            CheckId.NFS_OPEN -> i.nfs?.let { s ->
                val o = s.filter { it.enabled && it.open }.map { it.path }
                if (o.isEmpty()) CheckResult(id, CheckStatus.PASS, "Every NFS share is limited to networks or hosts.") else CheckResult(id, CheckStatus.WARN, "${plural(o.size, "share")} open to any host.", list(o))
            } ?: unknown(id)
            CheckId.CERTIFICATES -> i.certificates?.let { cs ->
                val expired = cs.filter { c -> c.untilMillis != null && c.untilMillis < now }.map { "${it.name}: expired" }
                val soon = cs.filter { c -> c.untilMillis != null && c.untilMillis >= now && c.untilMillis - now <= CERT_WARN_DAYS * DAY }
                    .map { "${it.name}: expires in ${((it.untilMillis!! - now) / DAY)} days" }
                when {
                    expired.isNotEmpty() -> CheckResult(id, CheckStatus.FAIL, "${plural(expired.size, "certificate")} expired.", list(expired + soon))
                    soon.isNotEmpty() -> CheckResult(id, CheckStatus.WARN, "${plural(soon.size, "certificate")} expire within $CERT_WARN_DAYS days.", list(soon))
                    else -> CheckResult(id, CheckStatus.PASS, if (cs.isEmpty()) "No certificates." else "None expire in the next $CERT_WARN_DAYS days.")
                }
            } ?: unknown(id)
            CheckId.API_KEYS -> i.apiKeys?.let { keys ->
                val live = keys.filter { !it.revoked }
                val stale = live.mapNotNull { k ->
                    val who = k.username?.let { " ($it)" } ?: ""
                    when {
                        k.expiresMillis != null && k.expiresMillis < now -> "${k.name}$who: expired, can be deleted"
                        k.expiresMillis == null && k.createdMillis != null && now - k.createdMillis > API_KEY_STALE_DAYS * DAY ->
                            "${k.name}$who: ${(now - k.createdMillis) / DAY} days old, never expires"
                        else -> null
                    }
                }
                if (stale.isEmpty()) CheckResult(id, CheckStatus.PASS, if (live.isEmpty()) "No API keys." else "${plural(live.size, "key")}, none stale.")
                else CheckResult(id, CheckStatus.WARN, "${plural(stale.size, "key")} worth reviewing.", list(stale))
            } ?: unknown(id)
            CheckId.SSH_ROOT_PASSWORD -> {
                val running = i.sshRunning; val pw = i.sshPasswordAuth; val users = i.users
                when {
                    running == false -> CheckResult(id, CheckStatus.PASS, "SSH is off.")
                    pw == false -> CheckResult(id, CheckStatus.PASS, "SSH password sign-in is off (keys only).")
                    running == null || pw == null || users == null -> unknown(id)
                    else -> {
                        val risky = users.filter { (it.uid == 0 || it.admin) && it.sshPassword && !it.passwordDisabled && !it.locked }.map { it.username }
                        if (risky.isEmpty()) CheckResult(id, CheckStatus.PASS, "No admin account may use an SSH password.")
                        else CheckResult(id, CheckStatus.FAIL, "SSH password sign-in allowed for ${risky.joinToString()}.", list(risky))
                    }
                }
            }
            CheckId.TWO_FACTOR -> {
                val users = i.users
                when {
                    i.twoFactorEnabled == null -> unknown(id)
                    i.twoFactorEnabled == false -> CheckResult(id, CheckStatus.WARN, "Two-factor authentication is off on this NAS.")
                    users == null -> unknown(id)
                    else -> {
                        val need = users.filter { (it.admin || it.uid == 0 || it.username == i.signedInUser) && !it.locked && !it.passwordDisabled && !it.twoFactor }.map { it.username }
                        if (need.isEmpty()) CheckResult(id, CheckStatus.PASS, "Admins sign in with a second factor.")
                        else CheckResult(id, CheckStatus.WARN, "${plural(need.size, "account")} without two-factor.", list(need))
                    }
                }
            }
            CheckId.UPDATE -> when (i.updateAvailable) {
                null -> unknown(id)
                true -> CheckResult(id, CheckStatus.WARN, "Update available" + (i.updateVersion?.let { ": $it" } ?: "") + ".")
                false -> CheckResult(id, CheckStatus.PASS, "No update waiting.")
            }
            CheckId.BOOT_POOL -> i.boot?.let { b ->
                val used = if (b.size != null && b.allocated != null && b.size > 0) b.allocated.toDouble() / b.size else null
                when {
                    !b.healthy -> CheckResult(id, CheckStatus.FAIL, "Boot pool is ${b.status?.lowercase() ?: "not healthy"}.")
                    used != null && used > BOOT_MAX_USED -> CheckResult(id, CheckStatus.WARN, "Boot pool is ${(used * 100).toInt()} % full. Delete old boot environments.")
                    else -> CheckResult(id, CheckStatus.PASS, "Healthy" + (used?.let { ", ${(it * 100).toInt()} % used" } ?: "") + ".")
                }
            } ?: unknown(id)
            CheckId.CONFIG_BACKUP -> {
                if (!i.lastConfigBackupKnown) unknown(id) else {
                    val days = i.lastConfigBackup?.let { ((now - it) / DAY).toInt() }
                    when {
                        days == null -> CheckResult(id, CheckStatus.WARN, "No configuration backup from this phone yet.")
                        days > BACKUP_MAX_DAYS -> CheckResult(id, CheckStatus.WARN, "Last backup from this phone was $days days ago.")
                        else -> CheckResult(id, CheckStatus.PASS, if (days == 0) "Backed up today." else "Backed up $days days ago.")
                    }
                }
            }
        }
    }

    /**
     * Weighted score 0–100: a pass counts fully, a warning half, a failure nothing. Hidden and unknown checks don't
     * count; an accepted risk counts as a pass (the user decided).
     */
    fun score(results: List<CheckResult>, marks: Map<String, CheckMark>): CheckupScore {
        var total = 0; var got = 0.0; var issues = 0; var unknown = 0; var counted = 0
        results.forEach { r ->
            val mark = marks[r.id.name]
            if (mark == CheckMark.HIDDEN) return@forEach
            if (r.status == CheckStatus.UNKNOWN) { unknown++; return@forEach }
            counted++
            total += r.id.weight
            val accepted = mark == CheckMark.ACCEPTED
            got += when {
                r.status == CheckStatus.PASS || accepted -> r.id.weight.toDouble()
                r.status == CheckStatus.WARN -> r.id.weight / 2.0
                else -> 0.0
            }
            if (r.status != CheckStatus.PASS && !accepted) issues++
        }
        val score = if (total == 0) 0 else Math.round(got * 100 / total).toInt()
        return CheckupScore(score, counted, issues, unknown)
    }

    /** Order for display: problems first (fail, warn), then unknown, then passes; hidden ones go to the end. */
    fun ordered(results: List<CheckResult>, marks: Map<String, CheckMark>): List<CheckResult> =
        results.sortedWith(compareBy({ marks[it.id.name] == CheckMark.HIDDEN }, { if (marks[it.id.name] == CheckMark.ACCEPTED) 3 else when (it.status) { CheckStatus.FAIL -> 0; CheckStatus.WARN -> 1; CheckStatus.UNKNOWN -> 2; CheckStatus.PASS -> 4 } }))
}
