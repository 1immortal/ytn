package app.truenascompanion.wear

import app.truenascompanion.wearlink.WearAlert
import app.truenascompanion.wearlink.WearHealth
import app.truenascompanion.wearlink.WearStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearPureTest {
    @Test fun updatedTextBuckets() {
        assertEquals("Waiting for the phone", updatedText(0, 10))
        assertEquals("Updated just now", updatedText(1_000, 30_000))
        assertEquals("Updated 4 min ago", updatedText(0 + 1, 4 * 60_000 + 1))
        assertEquals("Updated 2 h ago", updatedText(1, 2 * 3_600_000 + 1))
        assertEquals("Updated 3 d ago", updatedText(1, 3 * 86_400_000L + 1))
    }

    @Test fun tileLines() {
        val s = WearStatus(serverName = "homenas", health = WearHealth.HEALTHY, poolsHealthy = 2, poolsTotal = 2, cpuPercent = 3, tempC = 38, online = true, updatedAt = 1)
        val l = StatusTileLayout.lines(s, now = 60_001)
        assertEquals("homenas", l[0])
        assertEquals("Healthy", l[1])
        assertTrue(l.contains("CPU 3% · 38\u00A0°C"))
        assertEquals("Updated 1 min ago", l.last())
        val off = StatusTileLayout.lines(s.copy(online = false, health = WearHealth.UNKNOWN), now = 60_001)
        assertFalse(off.any { it.startsWith("CPU") })
    }

    @Test fun ackNeedsAnAlert() {
        val s = WearStatus(alerts = listOf(WearAlert("a", "CRITICAL", "Pool degraded")), alertCount = 1, health = WearHealth.CRITICAL, online = true, serverName = "nas")
        assertEquals("Degraded", WearColors.healthWord(s))
    }

    // 1.12.4 (issue #12)
    @Test fun alertListNoteExplainsEmptyList() {
        val base = WearStatus(serverName = "nas", online = true, alertCount = 2)
        assertEquals("Open YTN on your phone, or tap Refresh, to load the alert details.", alertListNote(base))
        assertTrue(alertListNote(base.copy(alertsHidden = true))!!.contains("hidden"))
        assertEquals(null, alertListNote(base.copy(alerts = listOf(WearAlert("a", "WARNING", "x"), WearAlert("b", "INFO", "y")))))
        assertEquals("Showing 1 of 2. The rest are on your phone.", alertListNote(base.copy(alerts = listOf(WearAlert("a", "WARNING", "x")))))
        assertEquals(null, alertListNote(base.copy(alertCount = 0)))
    }

    @Test fun tileShowsRam() {
        val s = WearStatus(serverName = "nas", online = true, cpuPercent = 5, ramPercent = 60, tempC = 40, updatedAt = 1)
        assertTrue(StatusTileLayout.lines(s, now = 2).contains("CPU 5% · RAM 60% · 40\u00A0°C"))
    }

    @Test fun powerQuestions() {
        assertEquals("Reboot nas?", powerQuestion(app.truenascompanion.wearlink.WearLink.POWER_REBOOT, "nas").first)
        assertEquals("Shut down the NAS?", powerQuestion(app.truenascompanion.wearlink.WearLink.POWER_SHUTDOWN, null).first)
        assertTrue(powerQuestion(app.truenascompanion.wearlink.WearLink.POWER_SHUTDOWN, "nas").second.contains("phone asks again"))
    }
}
