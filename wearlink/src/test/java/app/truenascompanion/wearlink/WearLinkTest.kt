package app.truenascompanion.wearlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearLinkTest {
    @Test fun roundTrip() {
        val s = WearStatus(serverName = "nas", health = WearHealth.WARNING, poolsHealthy = 1, poolsTotal = 2, alertCount = 3,
            cpuPercent = 12, tempC = 41, online = true, alerts = listOf(WearAlert("u1", "WARNING", "Pool degraded")), canWake = true, updatedAt = 5)
        assertEquals(s, WearLink.decode(WearLink.encode(s)))
    }

    @Test fun badBytesAreNull() {
        assertNull(WearLink.decode("nope".toByteArray()))
        assertNull(WearLink.decode(null))
    }

    @Test fun unknownFieldsIgnored() {
        val s = WearLink.decode("""{"version":2,"serverName":"x","future":true,"online":true}""".toByteArray())
        assertEquals("x", s?.serverName)
    }

    @Test fun shortTextAndSummary() {
        assertEquals("—", WearStatus().shortText())
        assertEquals("Off", WearStatus(serverName = "a").shortText())
        assertEquals("OK", WearStatus(serverName = "a", online = true).shortText())
        assertEquals("4", WearStatus(serverName = "a", online = true, alertCount = 4).shortText())
        assertEquals("2 pools healthy · no alerts", WearStatus(serverName = "a", online = true, poolsHealthy = 2, poolsTotal = 2).summary())
        assertEquals("1/2 pools healthy · 1 alert", WearStatus(serverName = "a", online = true, poolsHealthy = 1, poolsTotal = 2, alertCount = 1).summary())
        assertEquals("Sign in needed", WearStatus(serverName = "a", error = "Sign in needed").summary())
    }
}
