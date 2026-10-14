package app.truenascompanion

import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.NetInterface
import app.truenascompanion.data.model.NetInterfaceType
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.wol.Reach
import app.truenascompanion.data.wol.WakeController
import app.truenascompanion.data.wol.WakeOnLan
import app.truenascompanion.data.wol.WakeState
import app.truenascompanion.data.wol.WolConfig
import app.truenascompanion.quick.Automation
import app.truenascompanion.quick.pluginChoices
import app.truenascompanion.ui.adaptive.NavStyle
import app.truenascompanion.ui.adaptive.WindowLayout
import app.truenascompanion.ui.system.hubColumns
import app.truenascompanion.wear.WearPolicy
import app.truenascompanion.wearlink.WearHealth
import app.truenascompanion.wearlink.WearStatus
import app.truenascompanion.widget.WidgetPool
import app.truenascompanion.widget.WidgetSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.12.0: Wake-on-LAN, window layouts, the watch snapshot and the automation wake action. */
class V1120Test {
    // --- MAC addresses and the magic packet ---

    @Test fun parsesCommonMacFormats() {
        val want = "3c:ec:ef:12:34:56"
        listOf("3c:ec:ef:12:34:56", "3C-EC-EF-12-34-56", "3cec.ef12.3456", "3CECEF123456", " 3c ec ef 12 34 56 ").forEach {
            assertEquals(it, want, WakeOnLan.normalizeMac(it))
        }
    }

    @Test fun rejectsBadMacs() {
        listOf(null, "", "3c:ec:ef:12:34", "zz:ec:ef:12:34:56", "00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff", "01:00:5e:00:00:01", "3c:ec:ef:12:34:56:78").forEach {
            assertNull("$it", WakeOnLan.parseMac(it))
        }
    }

    @Test fun magicPacketIsSixFfThenSixteenMacs() {
        val mac = WakeOnLan.parseMac("3c:ec:ef:12:34:56")!!
        val p = WakeOnLan.magicPacket(mac)
        assertEquals(102, p.size)
        assertTrue(p.take(6).all { it == 0xFF.toByte() })
        for (i in 0 until 16) assertTrue(p.copyOfRange(6 + i * 6, 12 + i * 6).contentEquals(mac))
    }

    @Test fun subnetBroadcast() {
        assertEquals("192.168.1.255", WakeOnLan.subnetBroadcast("192.168.1.20/24"))
        assertEquals("10.0.15.255", WakeOnLan.subnetBroadcast("10.0.8.4/21"))
        assertEquals("172.16.255.255", WakeOnLan.subnetBroadcast("172.16.3.9/16"))
        assertNull(WakeOnLan.subnetBroadcast("192.168.1.20/32"))
        assertNull(WakeOnLan.subnetBroadcast("fe80::1/64"))
        assertNull(WakeOnLan.subnetBroadcast("192.168.1.20"))
    }

    private fun nic(name: String, mac: String, addrs: List<String>, fake: Boolean = false) =
        NetInterface(name = name, type = NetInterfaceType.PHYSICAL, currentAddresses = addrs, mac = mac, notPresent = fake)

    @Test fun candidatesFromInterfaceQuery() {
        val list = listOf(
            nic("enp3s0", "3C:EC:EF:12:34:56", listOf("fe80::1/64", "192.168.1.20/24")),
            nic("enp4s0", "3c:ec:ef:12:34:57", emptyList()),
            nic("br0", "", listOf("10.0.0.2/24")),
            nic("gone0", "3c:ec:ef:12:34:58", listOf("10.1.0.2/24"), fake = true),
        )
        val c = WakeOnLan.candidates(list)
        assertEquals(listOf("enp3s0", "enp4s0"), c.map { it.interfaceName })
        assertEquals("3c:ec:ef:12:34:56", c[0].mac)
        assertEquals("192.168.1.255", c[0].broadcast)
        assertNull(c[1].broadcast)
    }

    @Test fun bestCandidateIsOnTheServersSubnet() {
        val c = listOf(
            WakeOnLan.Candidate("a", "3c:ec:ef:00:00:01", "10.0.0.255", "10.0.0.2", 24),
            WakeOnLan.Candidate("b", "3c:ec:ef:00:00:02", "192.168.1.255", "192.168.1.20", 24),
        )
        assertEquals("b", WakeOnLan.bestCandidate(c, listOf("192.168.1.20"))?.interfaceName)
        assertEquals("a", WakeOnLan.bestCandidate(c, listOf("nas.example.com"))?.interfaceName)
        assertNull(WakeOnLan.bestCandidate(emptyList(), listOf("x")))
    }

    @Test fun targetsAndRelay() {
        val cfg = WolConfig("3c:ec:ef:12:34:56", broadcast = "192.168.1.255", port = 9, relay = "router.lan:4009")
        assertEquals(listOf("192.168.1.255" to 9, "255.255.255.255" to 9, "router.lan" to 4009), WakeOnLan.targets(cfg))
        assertEquals(listOf("255.255.255.255" to 7), WakeOnLan.targets(WolConfig("3c:ec:ef:12:34:56", port = 7)))
        assertEquals("pi.lan" to 9, WakeOnLan.parseRelay("pi.lan", 9))
        assertEquals("::1" to 9, WakeOnLan.parseRelay("[::1]:9", 7))
        assertNull(WakeOnLan.parseRelay("a:b:c", 9))
        assertNull(WakeOnLan.parseRelay("host:99999", 9))
        assertNull(WakeOnLan.parseRelay("http://x", 9))
    }

    @Test fun problems() {
        assertNull(WakeOnLan.problem(WolConfig("3c:ec:ef:12:34:56")))
        assertNotNull(WakeOnLan.problem(WolConfig("nope")))
        assertNotNull(WakeOnLan.problem(WolConfig("3c:ec:ef:12:34:56", broadcast = "192.168.1")))
        assertNotNull(WakeOnLan.problem(WolConfig("3c:ec:ef:12:34:56", relay = "a b")))
    }

    @Test fun probeTargetsFromSavedAddresses() {
        val s = ServerConfig("1", "n", "https://nas.example.com", localUrl = "https://192.168.1.20:8443", tailscaleUrl = "http://100.64.0.2")
        assertEquals(listOf("192.168.1.20" to 8443, "100.64.0.2" to 80, "nas.example.com" to 443), WakeOnLan.probeTargets(s))
    }

    @Test fun wolIsSavedWithTheServerAndOldConfigsStillLoad() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val s = ServerConfig("1", "n", "https://nas", wol = WolConfig("3c:ec:ef:12:34:56", "enp3s0", "192.168.1.255", 7, "pi:9"))
        val back = json.decodeFromString(ServerConfig.serializer(), json.encodeToString(ServerConfig.serializer(), s))
        assertEquals(s.wol, back.wol)
        assertNull(json.decodeFromString(ServerConfig.serializer(), """{"id":"1","name":"n","url":"https://nas"}""").wol)
    }

    // --- wake controller ---

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun wakePollsUntilOnline() {
        val scope = TestScope(StandardTestDispatcher())
        var sent = 0
        var probes = 0
        var online = 0
        val c = WakeController(scope, sender = { sent++; 3 }, probe = { probes++ >= 2 }, clock = { scope.testScheduler.currentTime }, onOnline = { online++ })
        val s = ServerConfig("1", "n", "https://nas", wol = WolConfig("3c:ec:ef:12:34:56"))
        assertTrue(c.wake(s))
        assertFalse("one wake at a time", c.wake(s))
        scope.runCurrent()
        assertEquals(1, sent)
        assertTrue(c.state("1") is WakeState.Waking)
        scope.advanceTimeBy(WakeOnLan.POLL_INTERVAL_MS * 3 + 1)
        assertEquals(WakeState.Online, c.state("1"))
        assertEquals(Reach.ONLINE, c.reach.value["1"])
        assertEquals(1, online)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun wakeTimesOutAndReportsSendFailures() {
        val scope = TestScope(StandardTestDispatcher())
        val c = WakeController(scope, sender = { 1 }, probe = { false }, clock = { scope.testScheduler.currentTime })
        val s = ServerConfig("1", "n", "https://nas", wol = WolConfig("3c:ec:ef:12:34:56"))
        c.wake(s)
        scope.advanceTimeBy(WakeOnLan.WAKE_TIMEOUT_MS + WakeOnLan.POLL_INTERVAL_MS * 2)
        assertEquals(WakeState.TimedOut, c.state("1"))
        assertTrue(WakeController.label(WakeState.TimedOut)!!.contains("BIOS"))

        val f = WakeController(scope, sender = { throw java.io.IOException("No Wi-Fi") }, probe = { false })
        f.wake(s.copy(id = "2"))
        scope.runCurrent()
        assertEquals(WakeState.Failed("No Wi-Fi"), f.state("2"))
        assertFalse("no settings, no wake", f.wake(ServerConfig("3", "n", "https://nas")))
    }

    @Test fun wakingLabel() {
        assertEquals("Waking… (up to ~2 min)", WakeController.label(WakeState.Waking(1000), now = 2000))
        assertEquals("Waking… (up to ~2 min) · 30s", WakeController.label(WakeState.Waking(0), now = 30_000))
        assertEquals("Online", WakeController.label(WakeState.Online))
        assertNull(WakeController.label(WakeState.Idle))
    }

    // --- window layouts ---

    @Test fun navStyleAndPanesPerWindow() {
        val phone = WindowLayout(0, 480)       // compact width bucket
        val landscapePhone = WindowLayout(600, 0)
        val tablet = WindowLayout(600, 900)
        val expanded = WindowLayout(840, 480)
        val large = WindowLayout(1200, 480)
        assertEquals(NavStyle.BOTTOM_BAR, phone.navStyle); assertFalse(phone.twoPane)
        assertEquals(NavStyle.RAIL, landscapePhone.navStyle); assertFalse(landscapePhone.twoPane)
        assertEquals(NavStyle.RAIL, tablet.navStyle); assertTrue(tablet.twoPane)
        assertEquals(NavStyle.RAIL, expanded.navStyle); assertTrue(expanded.twoPane)
        assertEquals(NavStyle.DRAWER, large.navStyle); assertTrue(large.twoPane)
        assertEquals(NavStyle.RAIL, WindowLayout(1200, 0).navStyle)
        assertEquals(NavStyle.RAIL, WindowLayout(0, 0).navStyle) // tiny or very short window
    }

    @Test fun dashboardAndHubColumns() {
        val l = WindowLayout.PHONE
        assertEquals(2, l.dashboardColumns(360))
        assertEquals(4, l.dashboardColumns(760))
        assertEquals(6, l.dashboardColumns(1200))
        assertEquals(2, hubColumns(360, 1f))
        assertEquals(3, hubColumns(760, 1f))
        assertEquals(4, hubColumns(1040, 1f))
        assertEquals(3, hubColumns(1040, 1.5f))
    }

    // --- watch snapshot ---

    @Test fun watchStatusFromWidgetSnapshot() {
        val snap = WidgetSnapshot(serverName = "nas", poolHealth = Health.WARNING, alertCount = 2, updatedAt = 5,
            pools = listOf(WidgetPool("tank", 50, Health.HEALTHY), WidgetPool("fast", 90, Health.WARNING)))
        val w = WearPolicy.fromSnapshot(WearStatus(), snap, canWake = true)
        assertEquals(WearHealth.WARNING, w.health)
        assertEquals(1, w.poolsHealthy); assertEquals(2, w.poolsTotal); assertEquals(2, w.alertCount)
        assertTrue(w.online); assertTrue(w.canWake); assertEquals(5, w.updatedAt)
        val off = WearPolicy.fromSnapshot(w, WidgetSnapshot(serverName = "nas", error = "Sign in again"), null)
        assertFalse(off.online); assertEquals("Sign in needed", off.error); assertEquals(2, off.alertCount); assertTrue(off.canWake)
        val other = WearPolicy.fromSnapshot(w.copy(cpuPercent = 4), WidgetSnapshot(serverName = "other"), null)
        assertNull("another server's numbers aren't kept", other.cpuPercent)
    }

    @Test fun watchAlertsAreTitlesOnlyAndRespectPrivacy() {
        val alerts = listOf(
            AlertItem("a", "INFO", "Long text with details 192.0.2.1", "SomeInfo", null, false, false),
            AlertItem("b", "CRITICAL", "Pool tank is DEGRADED", "ZpoolCapacity", null, false, false),
            AlertItem("c", "WARNING", "dismissed", null, null, true, false),
        )
        val w = WearPolicy.withAlerts(WearStatus(), "nas", alerts, mapOf("b" to "Pool degraded"), cpu = 7, temp = 41, showTitles = true)
        assertEquals(2, w.alertCount)
        assertEquals(listOf("b", "a"), w.alerts.map { it.id })
        assertEquals("Pool degraded", w.alerts[0].title)
        assertTrue(w.alerts.none { it.title.contains("192.0.2.1") })
        assertEquals(7, w.cpuPercent); assertEquals(41, w.tempC)
        val hidden = WearPolicy.withAlerts(WearStatus(), "nas", alerts, emptyMap(), null, null, showTitles = false)
        assertTrue(hidden.alerts.isEmpty()); assertTrue(hidden.alertsHidden); assertEquals(2, hidden.alertCount)
    }

    // --- automation: wake ---

    @Test fun automationWake() {
        assertEquals(Automation.Request.Wake, Automation.parse(Automation.WAKE, null, null, null))
        assertEquals("Wake the NAS (Wake-on-LAN)", Automation.blurb(Automation.WAKE, null, null, null, null))
        assertTrue(pluginChoices(false).any { it.kind == Automation.WAKE })
        assertFalse(pluginChoices(true).any { it.kind == Automation.WAKE })
    }

    // --- source rules ---

    private fun src(path: String) = File("src/main/$path").readText()

    @Test fun wakeIntentIsPermissionGuardedAndWearServiceDoesNotAcknowledgeByItself() {
        val manifest = src("AndroidManifest.xml")
        val receiver = manifest.substringAfter("android:name=\".quick.AutomationReceiver\"").substringBefore("</receiver>")
        assertTrue(receiver.contains("permission.AUTOMATION"))
        assertTrue(receiver.contains("action.WAKE"))
        val wear = src("java/app/truenascompanion/wear/WearMessageService.kt")
        assertFalse("acknowledge goes through the app's confirmation", wear.contains("dismiss("))
        assertTrue(wear.contains("postAutomationPrompt"))
    }

    @Test fun phoneUpdaterIgnoresTheWearAsset() {
        val body = """{"tag_name":"v1.12.0","draft":false,"assets":[
            {"name":"truenas-companion-wear-release.apk","browser_download_url":"https://x/w.apk","size":1},
            {"name":"truenas-companion-release.apk","browser_download_url":"https://x/r.apk","size":2},
            {"name":"truenas-companion-debug.apk","browser_download_url":"https://x/d.apk","size":3}]}"""
        val r = app.truenascompanion.data.update.UpdateChecker.parseRelease(body, app.truenascompanion.data.update.UpdateChannel.RELEASE)
        assertTrue(r.toString(), r.toString().contains("r.apk"))
        assertFalse(r.toString().contains("w.apk"))
        val d = app.truenascompanion.data.update.UpdateChecker.parseRelease(body, app.truenascompanion.data.update.UpdateChannel.DEBUG)
        assertTrue(d.toString().contains("d.apk"))
    }

    @Test fun versionIs1120() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("versionCode = 32"))
        assertTrue(gradle.contains("versionName = \"1.12.0\""))
        val wear = File("../wear/build.gradle.kts").readText()
        assertTrue(wear.contains("applicationId = \"app.truenascompanion\""))
        assertTrue(wear.contains("minSdk = 30"))
        assertTrue(wear.contains("isDebuggable = false"))
    }
}
