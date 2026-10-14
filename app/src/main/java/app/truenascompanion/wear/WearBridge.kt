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

    fun current(): WearStatus = WearLink.decode(prefs.getString(KEY_STATUS, null)?.toByteArray()) ?: WearStatus()

    fun onSnapshot(s: WidgetSnapshot, canWake: Boolean? = null) = update { WearPolicy.fromSnapshot(it, s, canWake) }

    fun onChecked(serverName: String, alerts: List<AlertItem>, titles: Map<String, String>, cpu: Int?, temp: Int?) = update {
        WearPolicy.withAlerts(it, serverName, alerts, titles, cpu, temp, showTitles && WearPolicy.systemAllowsPrivate(context))
    }

    fun onWake(label: String?, canWake: Boolean) = update { it.copy(wakeLabel = label, canWake = canWake) }

    fun update(transform: (WearStatus) -> WearStatus) {
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                val before = current()
                val next = transform(before)
                if (next == before) return@withLock
                prefs.edit { putString(KEY_STATUS, WearLink.encode(next).toString(Charsets.UTF_8)) }
                runCatching { if (transport.watchReachable()) transport.put(WearLink.STATUS_PATH, WearLink.encode(next), urgent = next.alertCount != before.alertCount) }
                    .onFailure { Log.i(TAG, "watch push skipped: ${it.message}") }
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
    }
}

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
            alerts = if (sameServer) prev.alerts else emptyList(),
            cpuPercent = if (sameServer) prev.cpuPercent else null,
            tempC = if (sameServer) prev.tempC else null,
            canWake = canWake ?: prev.canWake,
            updatedAt = s.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
    }

    /** Alerts from the phone-alert check: open ones, worst first, at most [WearLink.MAX_ALERTS], titles only. */
    fun withAlerts(prev: WearStatus, serverName: String, alerts: List<AlertItem>, titles: Map<String, String>, cpu: Int?, temp: Int?, showTitles: Boolean): WearStatus {
        val open = alerts.filter { !it.dismissed }.sortedBy { rank(it.level) }
        return prev.copy(
            serverName = serverName,
            online = true,
            error = null,
            alertCount = open.size,
            alerts = if (showTitles) open.take(WearLink.MAX_ALERTS).map { WearAlert(it.uuid, it.level.uppercase(), (titles[it.uuid] ?: it.klass ?: "Alert").take(80)) } else emptyList(),
            alertsHidden = !showTitles && open.isNotEmpty(),
            cpuPercent = cpu ?: prev.cpuPercent,
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
