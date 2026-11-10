package app.truenascompanion.wear

import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.notify.AlertDiff
import app.truenascompanion.notify.rules.RuleData
import kotlinx.coroutines.CancellationException

/**
 * 1.12.4 (issue #12): what the widget refresh reads for the watch, only while a watch with YTN is connected.
 *
 * Before, alert titles and CPU/temperature only came from the phone-alert check, so with phone alerts off (or before
 * the first check) the watch showed "2 alerts" over an empty list. Now the refresh the watch asks for delivers them too.
 * Everything here is read-only and optional: a failed read just leaves that value out.
 */
object WatchRefresh {
    suspend fun extras(c: AppContainer, server: ServerConfig, api: TrueNasApi, openAlerts: List<AlertItem>): WearExtras? {
        if (!c.wear.watchConnected()) return null
        val data = app.truenascompanion.notify.rules.ApiRuleData(api)
        val titles = if (c.wear.titlesAllowed() && openAlerts.isNotEmpty()) titles(c, server, api, openAlerts) else emptyMap()
        val s = stats(data)
        return WearExtras(openAlerts, titles, s.cpu, s.ram, s.temp)
    }

    private suspend fun titles(c: AppContainer, server: ServerConfig, api: TrueNasApi, open: List<AlertItem>): Map<String, String> {
        val known = optional { c.settings.seenAlerts(server.id) }.orEmpty().associate { it.uuid to it.title }
        val needClass = open.any { it.uuid !in known }
        val classTitles = if (!needClass) emptyMap() else c.wear.classTitles[server.id]
            ?: optional { api.alertClassTitles() }.orEmpty().also { if (it.isNotEmpty()) c.wear.classTitles[server.id] = it }
        return titlesFor(open, known, classTitles)
    }

    /** Remembered titles first (the same ones phone alerts showed), then the alert class title (pure, unit tested). */
    fun titlesFor(open: List<AlertItem>, known: Map<String, String>, classTitles: Map<String, String>): Map<String, String> =
        open.associate { it.uuid to (known[it.uuid] ?: AlertDiff.title(it, classTitles)) }

    data class Stats(val cpu: Int?, val ram: Int?, val temp: Int?)

    /** CPU and RAM averaged over 5 minutes, the hottest disk over 10 (same as the alert check). Works on 25.10 and 27. */
    suspend fun stats(data: RuleData) = Stats(
        cpu = optional { data.cpuAverage(5) }?.let { pct(it) },
        ram = optional { data.ramAverage(5) }?.let { pct(it) },
        temp = optional { data.diskTempLowest(10) }?.values?.maxOrNull()?.takeIf { it.isFinite() && it > 0 }?.toInt(),
    )

    /** Clamped to 0..100; NaN (no samples) counts as unknown. */
    fun pct(v: Double): Int? = v.takeIf { it.isFinite() }?.let { Math.round(it).toInt().coerceIn(0, 100) }

    private suspend fun <T> optional(block: suspend () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (_: Throwable) { null }
}
