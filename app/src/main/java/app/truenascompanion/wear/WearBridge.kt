package app.truenascompanion.wear

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.Health
import app.truenascompanion.widget.WidgetSnapshot
import app.truenascompanion.wearlink.WearAlert
import app.truenascompanion.wearlink.WearHealth
import app.truenascompanion.wearlink.WearLink
import app.truenascompanion.wearlink.WearStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 1.12.0: keeps the Wear OS app up to date through the Wearable Data Layer.
 *
 * The phone never polls for the watch: [onSnapshot] runs when the widget/tile refresh saves a snapshot and [onChecked]
 * when the phone-alert check has run. Each merges its part into one small [WearStatus] and puts it as a DataItem, but
 * only when a watch with YTN installed is reachable ([WearLink.CAPABILITY_WATCH]). Without Google Play services the
 * Data Layer isn't available; every call then fails quietly and the phone app works as before.
 */
class WearBridge(private val context: Context, private val scope: CoroutineScope, private val transport: WearTransport = PlayWearTransport(context)) {
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Mutex()

    /** System › Phone alerts › "Alert titles on the watch" (on by default; see [WearPolicy.showTitles]). */
    var showTitles: Boolean
        get() = prefs.getBoolean(KEY_TITLES, true)
        set(v) { prefs.edit { putBoolean(KEY_TITLES, v) } }

    /**
     * 1.12.4: System › Phone alerts › "Reboot and shut down from the watch" (on by default). Each request is still
     * confirmed on the phone ([WatchPower]); with this off the watch hides the buttons and the phone ignores requests.
     */
    var powerFromWatch: Boolean
        get() = prefs.getBoolean(KEY_POWER, true)
        set(v) { prefs.edit { putBoolean(KEY_POWER, v) }; update { it } }

    fun current(): WearStatus = WearLink.decode(prefs.getString(KEY_STATUS, null)?.toByteArray()) ?: WearStatus()

    /**
     * Widget refresh. 1.12.4 (issue #12): with [extras] (read only while a watch is connected) the same refresh also
     * carries the alert titles and CPU/RAM/temperature, so the watch's alert list always matches its count, even when
     * phone alerts are off or haven't run yet.
     */
    fun onSnapshot(s: WidgetSnapshot, canWake: Boolean? = null, extras: WearExtras? = null) = update { prev ->
        WearPolicy.fromRefresh(prev, s, canWake, extras, extras != null && titlesAllowed())
    }

    /** Alert class titles per server for the watch's widget-refresh path (fetched once per process, like the alert check). */
    val classTitles = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    /** True when alert titles may go to the watch at all (setting and lock-screen privacy). */
    fun titlesAllowed(): Boolean = showTitles && WearPolicy.systemAllowsPrivate(context)

    fun onChecked(serverName: String, alerts: List<AlertItem>, titles: Map<String, String>, cpu: Int?, temp: Int?, ram: Int? = null) = update {
        WearPolicy.withAlerts(it, serverName, alerts, titles, cpu, temp, titlesAllowed(), ram)
    }

    fun onWake(label: String?, canWake: Boolean) = update { it.copy(wakeLabel = label, canWake = canWake) }

    fun update(transform: (WearStatus) -> WearStatus) {
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                val before = current()
                val next = transform(before).copy(canPower = powerFromWatch)
                if (next == before) return@withLock
                prefs.edit { putString(KEY_STATUS, WearLink.encode(next).toString(Charsets.UTF_8)) }
                runCatching { if (transport.watchReachable()) transport.put(WearLink.STATUS_PATH, WearLink.encode(next), urgent = next.alertCount != before.alertCount) }
                    .onFailure { Log.i(TAG, "watch push skipped: ${it.message}") }
            }
        }
    }

    @Volatile private var lastResendMs = 0L

    /**
     * 1.12.3 (issue #12): opening the phone app sends the last status once (at most once a minute), so a watch that
     * connected since the last change gets it without waiting for the next one. Same reachability check as [update].
     */
    fun resend(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastResendMs < RESEND_GAP_MS) return
        lastResendMs = nowMs
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                val s = current()
                if (s.serverName == null) return@withLock
                runCatching { if (transport.watchReachable()) transport.put(WearLink.STATUS_PATH, WearLink.encode(s), urgent = false) }
                    .onFailure { Log.i(TAG, "watch resend skipped: ${it.message}") }
            }
        }
    }

    /** True when a watch with YTN is connected (used to decide whether CPU/temperature are worth reading). */
    suspend fun watchConnected(): Boolean = runCatching { transport.watchReachable() }.getOrDefault(false)

    companion object {
        private const val TAG = "WearBridge"
        private const val PREFS = "wear_bridge"
        private const val KEY_STATUS = "status"
        private const val KEY_TITLES = "titles"
        private const val KEY_POWER = "power"
        private const val RESEND_GAP_MS = 60_000L
    }
}

/** 1.12.4: what the widget refresh read for the watch (only while one is connected). */
data class WearExtras(
    val alerts: List<AlertItem>,
    val titles: Map<String, String>,
    val cpu: Int?,
    val ram: Int?,
    val temp: Int?,
)

/** How [WearStatus] is built (pure, unit tested). */
object WearPolicy {
    fun health(h: Health): WearHealth = when (h) {
        Health.HEALTHY, Health.INFO -> WearHealth.HEALTHY
        Health.WARNING -> WearHealth.WARNING
        Health.CRITICAL -> WearHealth.CRITICAL
        Health.UNKNOWN -> WearHealth.UNKNOWN
    }

    fun fromSnapshot(prev: WearStatus, s: WidgetSnapshot, canWake: Boolean?): WearStatus {
        val sameServer = prev.serverName == s.serverName
        return prev.copy(
            serverName = s.serverName,
            online = s.error == null && s.serverName != null,
            error = s.error?.let { if (it.contains("Sign in", ignoreCase = true)) "Sign in needed" else "Offline" },
            health = if (s.error != null) WearHealth.UNKNOWN else health(s.poolHealth),
            poolsTotal = s.pools.size,
            poolsHealthy = s.pools.count { it.health == Health.HEALTHY || it.health == Health.INFO },
            alertCount = if (s.error != null && sameServer) prev.alertCount else s.alertCount,
            // 1.12.4: no open alerts means no (stale) titles either.
            alerts = if (!sameServer || (s.error == null && s.alertCount == 0)) emptyList() else prev.alerts,
            alertsHidden = if (!sameServer || (s.error == null && s.alertCount == 0)) false else prev.alertsHidden,
            cpuPercent = if (sameServer) prev.cpuPercent else null,
            ramPercent = if (sameServer) prev.ramPercent else null,
            tempC = if (sameServer) prev.tempC else null,
            canWake = canWake ?: prev.canWake,
            updatedAt = s.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
    }

    /** 1.12.4: the widget refresh, plus (while a watch is connected) its alert titles and stats in the same update. */
    fun fromRefresh(prev: WearStatus, s: WidgetSnapshot, canWake: Boolean?, extras: WearExtras?, showTitles: Boolean): WearStatus {
        val base = fromSnapshot(prev, s, canWake)
        if (extras == null || s.error != null || s.serverName == null) return base
        return withAlerts(base, s.serverName, extras.alerts, extras.titles, extras.cpu, extras.temp, showTitles, extras.ram).copy(updatedAt = base.updatedAt)
    }

    /**
     * Alerts from the phone-alert check or (1.12.4) the widget refresh: open ones, worst first, at most
     * [WearLink.MAX_ALERTS], titles only. [titles] maps alert uuids to titles; missing ones fall back to the class.
     */
    fun withAlerts(prev: WearStatus, serverName: String, alerts: List<AlertItem>, titles: Map<String, String>, cpu: Int?, temp: Int?, showTitles: Boolean, ram: Int? = null): WearStatus {
        val open = alerts.filter { !it.dismissed }.sortedBy { rank(it.level) }
        return prev.copy(
            serverName = serverName,
            online = true,
            error = null,
            alertCount = open.size,
            alerts = if (showTitles) open.take(WearLink.MAX_ALERTS).map { WearAlert(it.uuid, it.level.uppercase(), (titles[it.uuid] ?: it.klass ?: "Alert").take(80)) } else emptyList(),
            alertsHidden = !showTitles && open.isNotEmpty(),
            cpuPercent = cpu ?: prev.cpuPercent,
            ramPercent = ram ?: prev.ramPercent,
            tempC = temp ?: prev.tempC,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun rank(level: String) = when (level.uppercase()) {
        "EMERGENCY", "ALERT" -> 0; "CRITICAL", "ERROR" -> 1; "WARNING" -> 2; "NOTICE" -> 3; else -> 4
    }

    /**
     * Android's "hide sensitive content" lock-screen setting. When the user hides sensitive notification content on the
     * lock screen, the watch gets counts only, never alert titles. Unknown (setting not readable) counts as allowed,
     * since YTN's own notifications are private on the lock screen either way.
     */
    fun systemAllowsPrivate(context: Context): Boolean = runCatching {
        android.provider.Settings.Secure.getInt(context.contentResolver, "lock_screen_allow_private_notifications", 1) != 0
    }.getOrDefault(true)
}
