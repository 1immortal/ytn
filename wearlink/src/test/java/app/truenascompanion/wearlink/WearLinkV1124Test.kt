package app.truenascompanion.wearlink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.12.4 (issue #12): RAM and the power flag are optional fields; old and new watches/phones keep understanding each other. */
class WearLinkV1124Test {
    @Test fun oldPhoneJsonStillDecodes() {
        // What a 1.12.3 phone sends: no ramPercent, no canPower.
        val old = """{"version":1,"serverName":"nas","health":"HEALTHY","poolsHealthy":1,"poolsTotal":1,"alertCount":0,
            |"cpuPercent":4,"tempC":37,"online":true,"error":null,"alerts":[],"alertsHidden":false,"canWake":false,
            |"wakeLabel":null,"updatedAt":9}""".trimMargin()
        val s = WearLink.decode(old.toByteArray())!!
        assertEquals(4, s.cpuPercent)
        assertNull(s.ramPercent)
        assertFalse(s.canPower)
    }

    @Test fun newFieldsRoundTripAndStayVersion1() {
        val s = WearStatus(serverName = "nas", online = true, cpuPercent = 7, ramPercent = 41, tempC = 38, canPower = true, updatedAt = 1)
        assertEquals(s, WearLink.decode(WearLink.encode(s)))
        val o = Json.parseToJsonElement(WearLink.encode(s).toString(Charsets.UTF_8)).jsonObject
        assertEquals("1", o["version"].toString())
        assertTrue("ramPercent" in o && "canPower" in o)
    }

    @Test fun olderWatchIgnoresNewFields() {
        // A 1.12.3 watch decodes with ignoreUnknownKeys; simulate it with a JSON that has extra keys.
        val s = WearLink.decode("""{"version":1,"serverName":"x","online":true,"ramPercent":50,"canPower":true,"somethingLater":1}""".toByteArray())
        assertEquals(50, s?.ramPercent)
        assertEquals(true, s?.canPower)
    }

    @Test fun statsLine() {
        val s = WearStatus(serverName = "nas", online = true, cpuPercent = 3, ramPercent = 41, tempC = 38)
        assertEquals("CPU 3% · RAM 41% · 38\u00A0°C", s.statsLine())
        assertEquals("CPU 3% · RAM 41% · Disks\u00A038\u00A0°C", s.statsLine("Disks\u00A0"))
        assertEquals("RAM 41%", s.copy(cpuPercent = null, tempC = null).statsLine())
        assertNull(s.copy(cpuPercent = null, ramPercent = null, tempC = null).statsLine())
        assertNull(s.copy(online = false).statsLine())
    }

    @Test fun powerMessageConstants() {
        assertEquals("/ytn/power", WearLink.MSG_POWER)
        assertTrue(WearLink.POWER_REBOOT != WearLink.POWER_SHUTDOWN)
    }
}
