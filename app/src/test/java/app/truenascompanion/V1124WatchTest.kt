package app.truenascompanion

import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.Health
import app.truenascompanion.notify.DeepLink
import app.truenascompanion.notify.DeepLinkGuard
import app.truenascompanion.wear.WatchPower
import app.truenascompanion.wear.WatchRefresh
import app.truenascompanion.wear.WearExtras
import app.truenascompanion.wear.WearPolicy
import app.truenascompanion.wearlink.WearAlert
import app.truenascompanion.wearlink.WearLink
import app.truenascompanion.wearlink.WearStatus
import app.truenascompanion.widget.WidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.12.4 (issue #12): the watch's alert list matches its count, CPU/RAM/temperature, and power requests from the watch. */
class V1124WatchTest {
    private fun alert(id: String, level: String, klass: String? = "SomeClass", dismissed: Boolean = false) =
        AlertItem(id, level, "text $id", klass, null, dismissed, false)

    private val snap = WidgetSnapshot(serverName = "nas", poolHealth = Health.WARNING, alertCount = 2, updatedAt = 50)

    @Test fun refreshWithoutPhoneAlertsStillFillsTheList() {
        // The reported bug: count from the refresh, titles never arrived (phone alerts off).
        val before = WearPolicy.fromSnapshot(WearStatus(), snap, null)
        assertEquals(2, before.alertCount)
        assertTrue(before.alerts.isEmpty())
        val extras = WearExtras(listOf(alert("w", "WARNING"), alert("c", "CRITICAL")), mapOf("w" to "Pool almost full", "c" to "Pool degraded"), cpu = 7, ram = 41, temp = 39)
        val w = WearPolicy.fromRefresh(WearStatus(), snap, null, extras, showTitles = true)
        assertEquals(2, w.alertCount)
        assertEquals(listOf("Pool degraded", "Pool almost full"), w.alerts.map { it.title }) // worst first
        assertEquals(w.alertCount, w.alerts.size)
        assertEquals(7, w.cpuPercent); assertEquals(41, w.ramPercent); assertEquals(39, w.tempC)
        assertEquals(50, w.updatedAt)
        assertFalse(w.alertsHidden)
    }

    @Test fun privacyKeepsTitlesOff() {
        val extras = WearExtras(listOf(alert("a", "WARNING")), mapOf("a" to "Secret"), null, null, null)
        val w = WearPolicy.fromRefresh(WearStatus(), snap.copy(alertCount = 1), null, extras, showTitles = false)
        assertTrue(w.alerts.isEmpty())
        assertTrue(w.alertsHidden)
        assertEquals(1, w.alertCount)
    }

    @Test fun atMostMaxAlertsAndDismissedSkipped() {
        val many = (1..30).map { alert("a$it", "WARNING") } + alert("d", "CRITICAL", dismissed = true)
        val w = WearPolicy.fromRefresh(WearStatus(), snap.copy(alertCount = 30), null, WearExtras(many, emptyMap(), null, null, null), showTitles = true)
        assertEquals(WearLink.MAX_ALERTS, w.alerts.size)
        assertEquals(30, w.alertCount)
        assertTrue(w.alerts.none { it.id == "d" })
    }

    @Test fun errorOrNoExtrasKeepsPrevious() {
        val prev = WearStatus(serverName = "nas", online = true, alertCount = 1, alerts = listOf(WearAlert("a", "WARNING", "x")), cpuPercent = 3, ramPercent = 30)
        val off = WearPolicy.fromRefresh(prev, WidgetSnapshot(serverName = "nas", error = "Can't reach"), null, WearExtras(emptyList(), emptyMap(), 1, 1, 1), true)
        assertEquals(1, off.alertCount); assertEquals(1, off.alerts.size); assertFalse(off.online); assertEquals(30, off.ramPercent)
        val plain = WearPolicy.fromRefresh(prev, snap.copy(alertCount = 1), null, null, true)
        assertEquals(1, plain.alerts.size)
    }

    @Test fun noOpenAlertsClearsStaleTitles() {
        val prev = WearStatus(serverName = "nas", online = true, alertCount = 2, alerts = listOf(WearAlert("a", "WARNING", "x")), alertsHidden = true)
        val w = WearPolicy.fromSnapshot(prev, snap.copy(alertCount = 0), null)
        assertTrue(w.alerts.isEmpty()); assertFalse(w.alertsHidden); assertEquals(0, w.alertCount)
    }

    @Test fun otherServerDropsStats() {
        val prev = WearStatus(serverName = "nas", online = true, cpuPercent = 3, ramPercent = 30, tempC = 40)
        val w = WearPolicy.fromSnapshot(prev, WidgetSnapshot(serverName = "other"), null)
        assertNull(w.cpuPercent); assertNull(w.ramPercent); assertNull(w.tempC)
    }

    @Test fun alertCheckCarriesRam() {
        val w = WearPolicy.withAlerts(WearStatus(ramPercent = 10), "nas", emptyList(), emptyMap(), cpu = null, temp = null, showTitles = true, ram = 55)
        assertEquals(55, w.ramPercent)
        assertEquals(10, WearPolicy.withAlerts(WearStatus(ramPercent = 10), "nas", emptyList(), emptyMap(), null, null, true).ramPercent)
    }

    @Test fun titlesPreferRememberedThenClass() {
        val open = listOf(alert("a", "WARNING", "ZpoolCapacityWarning"), alert("b", "INFO", "Known"), alert("c", "INFO", null))
        val t = WatchRefresh.titlesFor(open, known = mapOf("b" to "Remembered"), classTitles = mapOf("ZpoolCapacityWarning" to "Pool space warning"))
        assertEquals("Pool space warning", t["a"])
        assertEquals("Remembered", t["b"])
        assertTrue(t["c"]!!.isNotBlank())
    }

    @Test fun percentRounding() {
        assertEquals(42, WatchRefresh.pct(41.6))
        assertEquals(100, WatchRefresh.pct(130.0))
        assertEquals(0, WatchRefresh.pct(-1.0))
        assertNull(WatchRefresh.pct(Double.NaN))
    }

    // Power requests from the watch

    @Test fun powerMessageParsing() {
        assertEquals("reboot", WatchPower.parseMessage("reboot".toByteArray()))
        assertEquals("shutdown", WatchPower.parseMessage(" shutdown\n".toByteArray()))
        assertNull(WatchPower.parseMessage("rm -rf".toByteArray()))
        assertNull(WatchPower.parseMessage(null))
        assertNull(WatchPower.parseMessage(ByteArray(100) { 'r'.code.toByte() }))
    }

    @Test fun powerDecision() {
        val d = WatchPower.decide("reboot".toByteArray(), enabled = true, serverId = "s1", serverName = "homenas", nowMs = 1_000)
        assertTrue(d is WatchPower.Decision.Prompt)
        d as WatchPower.Decision.Prompt
        assertEquals("s1", d.serverId)
        assertEquals("reboot:1000", d.arg)
        assertEquals("Reboot homenas? (from your watch)", d.title)
        assertEquals(WatchPower.Decision.Disabled, WatchPower.decide("reboot".toByteArray(), false, "s1", "n", 0))
        assertEquals(WatchPower.Decision.NoServer, WatchPower.decide("shutdown".toByteArray(), true, null, null, 0))
        assertEquals(WatchPower.Decision.Invalid, WatchPower.decide("format".toByteArray(), true, "s1", "n", 0))
        assertEquals("Shut down your NAS? (from your watch)", WatchPower.promptTitle(WearLink.POWER_SHUTDOWN, " "))
    }

    @Test fun powerLinkExpires() {
        val now = 10_000_000L
        assertEquals("shutdown", WatchPower.parseArg(WatchPower.arg("shutdown", now - 60_000), now))
        assertNull(WatchPower.parseArg(WatchPower.arg("reboot", now - WatchPower.TTL_MS - 1), now))
        assertNull(WatchPower.parseArg(WatchPower.arg("reboot", now + 10 * 60_000), now))
        assertNull(WatchPower.parseArg("reboot", now))
        assertNull(WatchPower.parseArg("delete:$now", now))
        assertNull(WatchPower.parseArg(null, now))
    }

    @Test fun powerLinkMustBeSigned() {
        // Other apps can start MainActivity, but an unsigned watch_power link is dropped.
        assertNull(DeepLinkGuard.decide(DeepLink.DEST_WATCH_POWER, "s1", "reboot:1", signed = false))
        assertEquals(DeepLink.DEST_WATCH_POWER, DeepLinkGuard.decide(DeepLink.DEST_WATCH_POWER, "s1", "reboot:1", signed = true)?.destination)
        assertFalse(DeepLink.DEST_WATCH_POWER in DeepLinkGuard.PUBLIC)
    }
}
