package app.truenascompanion

import app.truenascompanion.data.api.ContainerParsers
import app.truenascompanion.data.api.Containers
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.model.ContainerApi
import app.truenascompanion.data.model.InstanceStatus
import app.truenascompanion.data.model.JobInfo
import app.truenascompanion.data.model.JobState
import app.truenascompanion.data.shell.ShellTarget
import app.truenascompanion.data.shell.WebShellProtocol
import app.truenascompanion.ui.jobs.title
import app.truenascompanion.ui.virt.containerStopText
import app.truenascompanion.ui.virt.containersUnavailableMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * 1.12.3 (issue #12): containers on TrueNAS 25.10 (Incus, `virt.*`) and TrueNAS 27 (`container.*` + `lxc.config`).
 *
 * TrueNAS 27 fixtures follow the official API reference, v27.0:
 * - https://api.truenas.com/v27.0/api_methods_container.query.html (ContainerEntry: integer id, status object,
 *   cpuset, memory in MiB, autostart, shutdown_timeout, dataset; no image or addresses)
 * - https://api.truenas.com/v27.0/api_methods_container.start.html (not a job, returns null)
 * - https://api.truenas.com/v27.0/api_methods_container.stop.html (job; options force, force_after_timeout)
 * - https://api.truenas.com/v27.0/api_methods_container.delete.html (job; options force, recursive)
 * - https://api.truenas.com/v27.0/api_methods_lxc.config.html (preferred_pool: string or null)
 * and middleware tag TS-27.0.0-RC.1 (src/middlewared/middlewared/plugins/container/lifecycle.py, container.py,
 * apps/webshell_app.py). 25.10 fixtures are the `virt.*` shapes YTN has used since 0.4.1 (see VirtTest).
 * Example data only.
 */
class V1123ContainersTest {
    private val calls = mutableListOf<Pair<String, List<JsonElement>>>()
    private var responder: (String, List<JsonElement>) -> JsonElement = { _, _ -> JsonNull }

    @Suppress("UNCHECKED_CAST")
    private val api = Proxy.newProxyInstance(TrueNasApi::class.java.classLoader, arrayOf(TrueNasApi::class.java)) { proxy, m, args ->
        when (m.name) {
            "rpc" -> {
                val method = args!![0] as String
                val params = (args[1] as Array<JsonElement>).toList()
                synchronized(calls) { calls += method to params }
                try {
                    responder(method, params)
                } catch (e: Throwable) {
                    (args.last() as kotlin.coroutines.Continuation<Any?>).resumeWith(Result.failure(e))
                    kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
                }
            }
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "toString" -> "fake"
            else -> null
        }
    } as TrueNasApi

    private fun j(s: String) = Json.parseToJsonElement(s)
    private fun methods() = calls.map { it.first }

    // --- Fixtures -----------------------------------------------------------------------------------------------

    /** 25.10 `virt.global.config` / `virt.instance.query`. */
    private val virtConfig = """{"id":1,"pool":"tank","storage_pools":["tank"],"bridge":null,"state":"INITIALIZED"}"""
    private val virtQuery = """[{"id":"debian","name":"debian","type":"CONTAINER","status":"RUNNING","cpu":"2",
        "memory":1073741824,"autostart":true,"image":{"os":"Debian","release":"bookworm"},"aliases":[],"storage_pool":"tank"}]"""

    /** 27 `lxc.config` (api_methods_lxc.config.html). */
    private val lxcConfig = """{"id":1,"preferred_pool":"tank","bridge":null,"v4_network":null,"v6_network":null}"""
    private val lxcConfigNoPool = """{"id":1,"preferred_pool":null,"bridge":null,"v4_network":null,"v6_network":null}"""

    /** 27 `container.query` ContainerEntry (api_methods_container.query.html). */
    private fun entry(id: Int, name: String, state: String, extra: String = "") = """{"id":$id,"uuid":"6f1c0d3e-0000-4000-8000-00000000000$id",
        "name":"$name","description":"","vcpus":null,"cores":null,"threads":null,"cpuset":null,"memory":null,"autostart":true,
        "time":"LOCAL","shutdown_timeout":90,"dataset":"tank/.ix-virt/containers/$name","init":"/sbin/init","initdir":null,
        "initenv":{},"inituser":null,"initgroup":null,"idmap":{"type":"DEFAULT"},"capabilities_policy":"DEFAULT",
        "capabilities_state":{},"status":{"state":"$state","pid":${if (state == "RUNNING") 4242 else "null"},"domain_state":null}$extra}"""

    private val containerQuery = "[" + listOf(
        entry(3, "web", "RUNNING").replace("\"cpuset\":null", "\"cpuset\":\"0-3\"").replace("\"memory\":null", "\"memory\":2048")
            .replace("\"description\":\"\"", "\"description\":\"Debian 12\"").replace("\"shutdown_timeout\":90", "\"shutdown_timeout\":120"),
        entry(1, "Alpha", "STOPPED").replace("\"autostart\":true", "\"autostart\":false"),
        entry(7, "paused", "SUSPENDED"),
    ).joinToString(",") + "]"

    private fun notFound(m: String): Nothing = throw TrueNasException.MethodNotFound(m)

    /** A TrueNAS 27 NAS: every virt.* call is "method not found". */
    private fun tn27(state: () -> String = { "STOPPED" }): (String, List<JsonElement>) -> JsonElement = { m, _ ->
        when {
            m.startsWith("virt.") -> notFound(m)
            m == "lxc.config" -> j(lxcConfig)
            m == "container.query" -> j(containerQuery)
            m == "container.start" -> JsonNull
            m == "container.stop" -> JsonPrimitive(501)
            m == "container.delete" -> JsonPrimitive(502)
            m == "core.get_jobs" -> j("""[{"id":501,"state":"SUCCESS","result":null}]""")
            m == "container.get_instance" -> j(entry(3, "web", state()))
            else -> fail("unexpected $m") as Nothing
        }
    }

    // --- Detection --------------------------------------------------------------------------------------------

    @Test
    fun tn2510KeepsUsingVirtAndNeverTouchesContainerApi() = runBlocking {
        responder = { m, _ ->
            when (m) {
                "virt.global.config" -> j(virtConfig)
                "virt.instance.query" -> j(virtQuery)
                else -> fail("25.10 must only use virt.*, got $m") as Nothing
            }
        }
        val c = Containers(api)
        assertEquals("INITIALIZED", c.state())
        val list = c.instances()
        assertEquals(ContainerApi.INCUS, c.detected)
        assertEquals(listOf("virt.global.config", "virt.instance.query"), methods())
        assertEquals("debian", list.single().id)
        assertEquals(ContainerApi.INCUS, list.single().api)
        assertEquals(InstanceStatus.RUNNING, list.single().status)
    }

    @Test
    fun tn27FallsBackOnMethodNotFoundAndCachesTheChoice() = runBlocking {
        responder = tn27()
        val c = Containers(api)
        assertEquals("INITIALIZED", c.state())
        assertEquals(ContainerApi.LXC, c.detected)
        c.instances()
        c.state()
        // virt.* is tried once only; afterwards the connection goes straight to the 27 API.
        assertEquals(listOf("virt.global.config", "lxc.config", "container.query", "lxc.config"), methods())
    }

    @Test
    fun instancesAloneAlsoDetects() = runBlocking {
        responder = tn27()
        val c = Containers(api)
        assertEquals(listOf("Alpha", "paused", "web"), c.instances().map { it.name })
        assertEquals(listOf("virt.instance.query", "container.query"), methods())
    }

    @Test
    fun neitherApiIsMethodNotFoundAndNothingIsCached() = runBlocking {
        responder = { m, _ -> notFound(m) }
        val c = Containers(api)
        try { c.state(); fail("expected MethodNotFound") } catch (e: TrueNasException.MethodNotFound) { assertEquals("lxc.config", e.method) }
        assertNull(c.detected)
    }

    @Test
    fun otherErrorsDoNotTriggerTheFallback() = runBlocking {
        responder = { _, _ -> throw TrueNasException.Forbidden() }
        val c = Containers(api)
        try { c.instances(); fail("expected Forbidden") } catch (_: TrueNasException.Forbidden) {}
        assertEquals(listOf("virt.instance.query"), methods())
        assertNull(c.detected)
    }

    @Test
    fun noPreferredPoolIsNotSetUp() {
        assertEquals("NO_POOL", ContainerParsers.lxcState(j(lxcConfigNoPool).jsonObject))
        assertEquals("NO_POOL", ContainerParsers.lxcState(j(lxcConfig.replace("\"tank\"", "\"\"")).jsonObject))
        assertEquals("INITIALIZED", ContainerParsers.lxcState(j(lxcConfig).jsonObject))
        assertNull(ContainerParsers.lxcState(null))
        // The screen shows the same "choose a pool" hint as on 25.10.
        assertTrue(containersUnavailableMessage("NO_POOL")!!.contains("Choose a pool"))
        assertNull(containersUnavailableMessage("INITIALIZED"))
    }

    // --- Parsing ----------------------------------------------------------------------------------------------

    @Test
    fun containerEntryParses() {
        val web = ContainerParsers.container(j(containerQuery).let { it as kotlinx.serialization.json.JsonArray }[0].jsonObject)!!
        assertEquals("3", web.id)
        assertEquals("web", web.name)
        assertEquals("CONTAINER", web.type)
        assertEquals(InstanceStatus.RUNNING, web.status)
        assertEquals("0-3", web.cpu)
        assertEquals(2048L * 1024 * 1024, web.memoryBytes)
        assertTrue(web.autostart)
        assertEquals("Debian 12", web.image)
        assertEquals(emptyList<String>(), web.addresses)
        assertEquals("tank", web.storagePool)
        assertEquals(ContainerApi.LXC, web.api)
        assertEquals(120, web.shutdownTimeout)

        val alpha = ContainerParsers.container(j(entry(1, "Alpha", "STOPPED").replace("\"autostart\":true", "\"autostart\":false")).jsonObject)!!
        assertEquals(InstanceStatus.STOPPED, alpha.status)
        assertFalse(alpha.autostart)
        assertNull(alpha.cpu)
        assertNull(alpha.memoryBytes)
        assertNull(alpha.image)
        assertEquals(InstanceStatus.FROZEN, ContainerParsers.container(j(entry(7, "p", "SUSPENDED")).jsonObject)!!.status)
        assertEquals(InstanceStatus.UNKNOWN, ContainerParsers.container(j("""{"id":9,"name":"x","dataset":"tank/x"}""").jsonObject)!!.status)
        assertNull(ContainerParsers.container(j("""{"name":"no id"}""").jsonObject))
    }

    // --- Actions ----------------------------------------------------------------------------------------------

    private suspend fun lxcInstance(c: Containers) = c.instances().first { it.name == "web" }

    @Test
    fun tn27StartIsAPlainCallWithTheIntegerId() = runBlocking {
        responder = tn27()
        val c = Containers(api)
        val web = lxcInstance(c)
        calls.clear()
        assertNull(c.start(web))
        assertEquals(listOf("container.start" to listOf<JsonElement>(JsonPrimitive(3L))), calls)
    }

    @Test
    fun tn27StopAndDeleteAreJobsWithSafeOptions() = runBlocking {
        responder = tn27()
        val c = Containers(api)
        val web = lxcInstance(c)
        calls.clear()
        assertEquals(501L, c.stop(web, force = false))
        assertEquals(j("""{"force":false,"force_after_timeout":false}"""), calls.last().second[1])
        assertEquals(501L, c.stop(web, force = true))
        assertEquals(j("""{"force":true,"force_after_timeout":false}"""), calls.last().second[1])
        assertEquals(502L, c.delete(web))
        assertEquals("container.delete", calls.last().first)
        assertEquals(JsonPrimitive(3L), calls.last().second[0])
        // Never forced, never recursive (snapshots and child datasets stay).
        assertEquals(j("""{"force":false,"recursive":false}"""), calls.last().second[1])
    }

    @Test
    fun tn27RestartStopsWaitsChecksThenStarts() = runBlocking {
        responder = tn27 { "STOPPED" }
        val c = Containers(api)
        val web = lxcInstance(c)
        calls.clear()
        assertNull(c.restart(web))
        assertEquals(listOf("container.stop", "core.get_jobs", "container.get_instance", "container.start"), methods())
        assertEquals(j("""{"force":false,"force_after_timeout":false}"""), calls[0].second[1])
    }

    @Test
    fun tn27RestartDoesNotStartWhenTheStopDidNotTakeEffect() = runBlocking {
        responder = tn27 { "RUNNING" }
        val c = Containers(api)
        val web = lxcInstance(c)
        calls.clear()
        try { c.restart(web); fail("expected JobFailed") } catch (e: TrueNasException.JobFailed) {
            assertTrue(e.message!!.contains("wasn't started again"))
        }
        assertFalse("container.start" in methods())
    }

    @Test
    fun tn27RestartStopsWhenTheStopJobFails() = runBlocking {
        val base = tn27()
        responder = { m, a -> if (m == "core.get_jobs") j("""[{"id":501,"state":"FAILED","error":"Container is not running"}]""") else base(m, a) }
        val c = Containers(api)
        val web = lxcInstance(c)
        calls.clear()
        try { c.restart(web); fail("expected JobFailed") } catch (e: TrueNasException.JobFailed) { assertEquals("Container is not running", e.message) }
        assertFalse("container.start" in methods())
    }

    @Test
    fun tn2510ActionsAreUnchanged() = runBlocking {
        responder = { m, _ -> if (m == "virt.instance.query") j(virtQuery) else JsonPrimitive(77) }
        val c = Containers(api)
        val d = c.instances().single()
        calls.clear()
        assertEquals(77L, c.start(d))
        assertEquals(77L, c.stop(d, force = false))
        assertEquals(77L, c.stop(d, force = true))
        assertEquals(77L, c.restart(d))
        assertEquals(77L, c.delete(d))
        assertEquals(
            listOf(
                "virt.instance.start" to listOf(JsonPrimitive("debian")),
                "virt.instance.stop" to listOf(JsonPrimitive("debian"), j("""{"timeout":60,"force":false}""")),
                "virt.instance.stop" to listOf(JsonPrimitive("debian"), j("""{"timeout":-1,"force":true}""")),
                "virt.instance.restart" to listOf(JsonPrimitive("debian"), j("""{"timeout":60,"force":false}""")),
                "virt.instance.delete" to listOf<JsonElement>(JsonPrimitive("debian")),
            ),
            calls,
        )
    }

    @Test
    fun aJobActionWithoutAJobIdIsAnError() = runBlocking {
        responder = { m, _ -> if (m == "virt.instance.query") j(virtQuery) else JsonNull }
        val c = Containers(api)
        val d = c.instances().single()
        try { c.stop(d, false); fail("expected JobFailed") } catch (e: TrueNasException.JobFailed) { assertEquals("virt.instance.stop did not return a job id", e.message) }
    }

    // --- UI bits ----------------------------------------------------------------------------------------------

    @Test
    fun stopTextUsesTheRightTimeout() = runBlocking {
        responder = tn27()
        val web = lxcInstance(Containers(api))
        assertEquals("Stops the container (waits up to 120 s for a clean shutdown).", containerStopText(web))
        assertEquals("Stops the container (waits up to 90 s for a clean shutdown).", containerStopText(web.copy(shutdownTimeout = null)))
        assertEquals("Stops the container (waits up to 60 s for a clean shutdown).", containerStopText(web.copy(api = ContainerApi.INCUS)))
    }

    @Test
    fun tn27ShellOptions() {
        val o = WebShellProtocol.options(ShellTarget.Instance("3", null, lxc = true))
        assertEquals(3L, o["container_id"]!!.jsonPrimitive.content.toLong())
        assertTrue(o["container_id"]!!.jsonPrimitive.isString.not())
        assertNull(o["virt_instance_id"])
        assertNull(o["use_console"])
        assertNull(o["command"])
        assertEquals("/bin/sh", WebShellProtocol.options(ShellTarget.Instance("3", "/bin/sh", lxc = true))["command"]!!.jsonPrimitive.content)
        // 25.10 options are unchanged.
        val inc = WebShellProtocol.options(ShellTarget.Instance("debian", null))
        assertEquals("debian", inc["virt_instance_id"]!!.jsonPrimitive.content)
        assertNull(inc["container_id"])
    }

    @Test
    fun tn27JobTitles() {
        fun job(method: String, arg: String?) = JobInfo(1, method, arg, null, JobState.RUNNING, null, null, null, false, null, null)
        assertEquals("Stop container 3", job("container.stop", "3").title())
        assertEquals("Delete container 3", job("container.delete", "3").title())
        assertEquals("Stop container", job("container.stop", null).title())
        assertEquals("Create container", job("container.create", null).title())
        assertEquals("Stop debian", job("virt.instance.stop", "debian").title())
    }
}
