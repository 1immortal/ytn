package app.truenascompanion

import app.truenascompanion.data.model.ReportData
import app.truenascompanion.data.model.ReportSeries
import app.truenascompanion.notify.rules.RuleData
import app.truenascompanion.notify.rules.RuleFindings
import app.truenascompanion.wear.WatchRefresh
import app.truenascompanion.wear.WearExtras
import app.truenascompanion.wear.WearPolicy
import app.truenascompanion.wearlink.WearStatus
import app.truenascompanion.widget.WidgetSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.12.5 (issue #12): the watch's temperature on TrueNAS 27, with fallbacks that also work on 25.10. */
class V1125TemperatureTest {
    private class Fake(
        val now: Map<String, Double>? = null,
        val latest: Map<String, Double>? = null,
        val cpuTemp: Double? = null,
        val fail: Boolean = false,
    ) : RuleData {
        val calls = mutableListOf<String>()
        private fun <T> r(name: String, v: T): T { calls += name; if (fail) error("boom"); return v }
        override suspend fun pools() = null
        override suspend fun apps() = null
        override suspend fun services() = null
        override suspend fun backups() = null
        override suspend fun certificates() = null
        override suspend fun cpuAverage(minutes: Int) = r("cpu", 16.4)
        override suspend fun ramAverage(minutes: Int) = r("ram", 50.0)
        override suspend fun diskTempLowest(minutes: Int): Map<String, Double>? = r("lowest", null)
        override suspend fun diskTempNow() = r("now", now)
        override suspend fun diskTempLatest(minutes: Int) = r("latest$minutes", latest)
        override suspend fun cpuTempAverage(minutes: Int) = r("cputemp", cpuTemp)
    }

    private fun stats(f: Fake) = runBlocking { WatchRefresh.stats(f) }

    @Test fun liveDiskTemperatureFirst() {
        // What Pavel's 25.10 NAS answers to disk.temperatures([]).
        val f = Fake(now = mapOf("sdb" to 52.0, "sda" to 53.0, "nvme0n1" to 25.85), latest = mapOf("sda" to 99.0), cpuTemp = 40.0)
        val s = stats(f)
        assertEquals(53, s.temp)
        assertNull(s.cpuTemp)
        assertEquals(16, s.cpu); assertEquals(50, s.ram)
        assertFalse("no netdata or CPU temperature reads when a disk answers", f.calls.any { it.startsWith("latest") || it == "cputemp" })
    }

    @Test fun netdataHistoryWhenNoLiveValue() {
        val f = Fake(now = emptyMap(), latest = mapOf("sda" to 41.0, "sdb" to 44.4))
        val s = stats(f)
        assertEquals(44, s.temp)
        assertTrue("30-minute window (charts are sampled every 5 minutes)", "latest30" in f.calls)
        assertFalse("cputemp" in f.calls)
    }

    @Test fun cpuTemperatureWhenNoDiskReportsOne() {
        // e.g. virtual disks: disk.temperatures has only nulls (dropped by the parser) and the chart only zeros.
        val s = stats(Fake(now = emptyMap(), latest = mapOf("vda" to 0.0), cpuTemp = 45.6))
        assertNull(s.temp)
        assertEquals(46, s.cpuTemp)
    }

    @Test fun nothingAvailableOrEverythingFails() {
        val none = stats(Fake())
        assertNull(none.temp); assertNull(none.cpuTemp)
        val failed = stats(Fake(fail = true))
        assertNull(failed.temp); assertNull(failed.cpuTemp); assertNull(failed.cpu); assertNull(failed.ram)
    }

    @Test fun zerosAndNonsenseAreNotTemperatures() {
        assertNull(WatchRefresh.hottestDisk(mapOf("a" to 0.0, "b" to Double.NaN, "c" to -5.0, "d" to 400.0)))
        assertEquals(38, WatchRefresh.hottestDisk(mapOf("a" to 0.0, "b" to 37.6)))
        assertNull(WatchRefresh.hottestDisk(null))
        assertNull(WatchRefresh.temp(0.0))
    }

    private fun series(vararg v: Float, min: Double? = null, mean: Double? = null, max: Double? = null) =
        ReportSeries("temperature_value", floatArrayOf(*v), min = min, mean = mean, max = max)

    @Test fun diskLowestIgnoresNoReadingZeros() {
        // Before 1.12.5 one 0 sample made the disk's lowest temperature 0 (rule never fired, watch showed nothing).
        assertEquals(51.0, RuleFindings.diskLowest(series(52f, 0f, 51f, Float.NaN))!!, 0.001)
        assertNull(RuleFindings.diskLowest(series(0f, 0f)))
        assertEquals(50.0, RuleFindings.diskLowest(series(Float.NaN, min = 50.0))!!, 0.001)
    }

    @Test fun latestReadingUsesNewestRealSample() {
        assertEquals(24.0, RuleFindings.latestReading(longArrayOf(100, 400, 700), series(27f, 24f, 0f))!!, 0.001)
        // Order follows the timestamps, not the array.
        assertEquals(27.0, RuleFindings.latestReading(longArrayOf(700, 400, 100), series(27f, 24f, 26f))!!, 0.001)
        assertEquals(30.0, RuleFindings.latestReading(longArrayOf(), series(max = 30.0))!!, 0.001)
        assertNull(RuleFindings.latestReading(longArrayOf(1), series(0f, max = 0.0)))
    }

    @Test fun cpuTempIsHottestCoreMean() {
        val d = ReportData("cputemp", null, 0, 0, longArrayOf(1, 2),
            listOf(ReportSeries("cpu0", floatArrayOf(33f, 35f)), ReportSeries("cpu1", floatArrayOf(40f, 0f)), ReportSeries("cpu2", floatArrayOf(0f, 0f))))
        assertEquals(40.0, RuleFindings.cpuTempMax(d)!!, 0.001)
        assertNull(RuleFindings.cpuTempMax(ReportData("cputemp", null, 0, 0, longArrayOf(), emptyList())))
    }

    @Test fun policyReplacesOrKeepsTemperatures() {
        val prev = WearStatus(serverName = "nas", online = true, tempC = 40)
        val cpuOnly = WearPolicy.withAlerts(prev, "nas", emptyList(), emptyMap(), cpu = 5, temp = null, showTitles = true, cpuTemp = 45)
        assertNull("a CPU reading replaces an old disk reading", cpuOnly.tempC)
        assertEquals(45, cpuOnly.cpuTempC)
        val back = WearPolicy.withAlerts(cpuOnly, "nas", emptyList(), emptyMap(), null, temp = 39, showTitles = true)
        assertEquals(39, back.tempC); assertNull(back.cpuTempC)
        val none = WearPolicy.withAlerts(back, "nas", emptyList(), emptyMap(), null, null, true)
        assertEquals("no reading keeps the last one", 39, none.tempC)
        val other = WearPolicy.fromSnapshot(cpuOnly, WidgetSnapshot(serverName = "other"), null)
        assertNull(other.cpuTempC)
    }

    @Test fun refreshCarriesCpuTemperature() {
        val snap = WidgetSnapshot(serverName = "nas", alertCount = 0, updatedAt = 5)
        val w = WearPolicy.fromRefresh(WearStatus(), snap, null, WearExtras(emptyList(), emptyMap(), cpu = 16, ram = 50, temp = null, cpuTemp = 47), true)
        assertEquals(47, w.cpuTempC)
        assertEquals("CPU 16%, 47\u00A0°C · RAM 50%", w.statsLine())
    }
}
