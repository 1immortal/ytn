package app.truenascompanion.data.api

import app.truenascompanion.data.model.ContainerApi
import app.truenascompanion.data.model.InstanceStatus
import app.truenascompanion.data.model.VirtInstance
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 1.12.3 (issue #12): Linux containers on two different APIs.
 *
 * - TrueNAS 25.04–25.10: Incus, `virt.global.config` and `virt.instance.*` (string ids, every action is a job).
 * - TrueNAS 27: `virt.*` is gone. Containers are `container.*` (integer ids) and the global settings are `lxc.config`.
 *   `container.start` is a plain call, `container.stop` / `container.delete` are jobs and there is no restart.
 *   Shapes from https://api.truenas.com/v27.0/ (container.query, container.start, container.stop, container.delete,
 *   lxc.config) and middleware TS-27.0.0-RC.1 (`plugins/container/lifecycle.py`, `container.py`).
 *
 * The API is detected once per connection: the 25.10 call first (so 25.10 behaves exactly as before), and the 27 call
 * only when the NAS answers "method not found".
 */
class Containers(private val api: TrueNasApi, private val stopWaitMs: (VirtInstance) -> Long = ::defaultStopWaitMs) {
    @Volatile var detected: ContainerApi? = null
        private set

    private suspend fun <T> pick(incus: suspend () -> T, lxc: suspend () -> T): T {
        when (detected) {
            ContainerApi.INCUS -> return incus()
            ContainerApi.LXC -> return lxc()
            null -> Unit
        }
        return try {
            incus().also { detected = ContainerApi.INCUS }
        } catch (e: TrueNasException.MethodNotFound) {
            lxc().also { detected = ContainerApi.LXC }
        }
    }

    suspend fun state(): String? = pick(
        incus = { api.rpc("virt.global.config").obj()?.str("state") },
        lxc = { ContainerParsers.lxcState(api.rpc("lxc.config").obj()) },
    )

    suspend fun instances(): List<VirtInstance> = pick(
        incus = { api.rpc("virt.instance.query").arr()?.mapNotNull { it.obj()?.let(Parsers::virtInstance) } ?: emptyList() },
        lxc = { api.rpc("container.query").arr()?.mapNotNull { it.obj()?.let(ContainerParsers::container) } ?: emptyList() },
    ).sortedBy { it.name.lowercase() }

    suspend fun start(inst: VirtInstance): Long? = when (inst.api) {
        ContainerApi.INCUS -> job("virt.instance.start", JsonPrimitive(inst.id))
        ContainerApi.LXC -> { api.rpc("container.start", lxcId(inst)); null }
    }

    suspend fun stop(inst: VirtInstance, force: Boolean): Long? = when (inst.api) {
        ContainerApi.INCUS -> job("virt.instance.stop", JsonPrimitive(inst.id), buildJsonObject { put("timeout", if (force) -1 else 60); put("force", force) })
        ContainerApi.LXC -> job("container.stop", lxcId(inst), stopOptions(force))
    }

    /** 27 has no restart: a clean stop (the container's own shutdown timeout, no kill), a check, then a start. */
    suspend fun restart(inst: VirtInstance): Long? = when (inst.api) {
        ContainerApi.INCUS -> job("virt.instance.restart", JsonPrimitive(inst.id), buildJsonObject { put("timeout", 60); put("force", false) })
        ContainerApi.LXC -> {
            val id = lxcId(inst)
            val stopJob = job("container.stop", id, stopOptions(force = false))
            api.awaitJob(stopJob, "container.stop", stopWaitMs(inst))
            val state = api.rpc("container.get_instance", id).obj()?.get("status").obj()?.str("state")
            if (!state.equals("STOPPED", ignoreCase = true)) throw TrueNasException.JobFailed(
                "${inst.name} didn't shut down (it is ${state?.lowercase() ?: "in an unknown state"}), so it wasn't started again.",
            )
            api.rpc("container.start", id)
            null
        }
    }

    /** Only offered for stopped containers. Never forced and never recursive: snapshots and child datasets stay. */
    suspend fun delete(inst: VirtInstance): Long? = when (inst.api) {
        ContainerApi.INCUS -> job("virt.instance.delete", JsonPrimitive(inst.id))
        ContainerApi.LXC -> job("container.delete", lxcId(inst), buildJsonObject { put("force", false); put("recursive", false) })
    }

    private suspend fun job(method: String, vararg args: JsonElement): Long =
        api.rpc(method, *args).prim()?.takeUnless { it.isString }?.longOrNull
            ?: throw TrueNasException.JobFailed("$method did not return a job id")

    private fun stopOptions(force: Boolean) = buildJsonObject { put("force", force); put("force_after_timeout", false) }

    private fun lxcId(inst: VirtInstance) = JsonPrimitive(
        inst.id.toLongOrNull() ?: throw TrueNasException.Unsupported("Unexpected container id ${inst.id}."),
    )

    companion object {
        /** The container's shutdown timeout (default 90 s on 27) plus a minute for the job itself. */
        fun defaultStopWaitMs(inst: VirtInstance): Long = ((inst.shutdownTimeout ?: 90) + 60) * 1000L
    }
}

/** TrueNAS 27 `container.query` / `lxc.config` parsing (pure, unit tested). */
object ContainerParsers {
    /** `lxc.config` has no state field; without a `preferred_pool` containers aren't set up (25.10's NO_POOL). */
    fun lxcState(config: JsonObject?): String? = when {
        config == null -> null
        config.str("preferred_pool").isNullOrBlank() -> "NO_POOL"
        else -> "INITIALIZED"
    }

    fun container(o: JsonObject): VirtInstance? {
        val id = o.long("id") ?: return null
        val name = o.str("name")?.takeIf { it.isNotBlank() } ?: id.toString()
        val dataset = o.str("dataset")
        return VirtInstance(
            id = id.toString(),
            name = name,
            type = "CONTAINER",
            status = when (o["status"].obj()?.str("state")?.uppercase()) {
                "RUNNING" -> InstanceStatus.RUNNING
                "STOPPED" -> InstanceStatus.STOPPED
                "SUSPENDED" -> InstanceStatus.FROZEN
                else -> InstanceStatus.UNKNOWN
            },
            // `cpuset` pins CPUs ("0-3"), it isn't a core count, so the card shows it as is.
            cpu = o.str("cpuset")?.takeIf { it.isNotBlank() },
            memoryBytes = o.long("memory")?.let { it * 1024 * 1024 },
            autostart = o.bool("autostart") ?: true,
            image = o.str("description")?.takeIf { it.isNotBlank() },
            addresses = emptyList(),
            storagePool = dataset?.substringBefore('/')?.takeIf { it.isNotBlank() },
            api = ContainerApi.LXC,
            shutdownTimeout = o.long("shutdown_timeout")?.toInt(),
        )
    }
}
