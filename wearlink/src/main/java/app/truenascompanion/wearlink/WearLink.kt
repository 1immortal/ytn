package app.truenascompanion.wearlink

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 1.12.0: the phone ↔ watch contract.
 *
 * - The phone puts [WearStatus] as a DataItem at [STATUS_PATH] whenever its existing checks run (widget refresh,
 *   phone-alert check). The watch never talks to the NAS and never holds credentials.
 * - The watch sends messages: [MSG_REFRESH] (ask the phone to refresh), [MSG_WAKE] (Wake-on-LAN through the phone),
 *   [MSG_ACK] with an alert id (the phone shows a confirmation; nothing is acknowledged without the phone).
 * - [CAPABILITY_WATCH] is declared by the watch app (res/values/wear.xml) so the phone only pushes when a watch with
 *   YTN is connected.
 */
object WearLink {
    const val STATUS_PATH = "/ytn/status"
    const val MSG_REFRESH = "/ytn/refresh"
    const val MSG_WAKE = "/ytn/wake"
    const val MSG_ACK = "/ytn/ack"
    const val CAPABILITY_WATCH = "ytn_watch"
    const val CAPABILITY_PHONE = "ytn_phone"
    const val KEY_JSON = "json"
    /** Format version; a watch that sees a newer one says "Update YTN on the watch". */
    const val VERSION = 1
    /** Never send more alerts than this (Data Layer items should stay small). */
    const val MAX_ALERTS = 20

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(s: WearStatus): ByteArray = json.encodeToString(WearStatus.serializer(), s).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray?): WearStatus? = bytes?.let {
        runCatching { json.decodeFromString(WearStatus.serializer(), it.toString(Charsets.UTF_8)) }.getOrNull()
    }
}

@Serializable
enum class WearHealth { HEALTHY, WARNING, CRITICAL, UNKNOWN }

@Serializable
data class WearAlert(
    /** Opaque id the phone maps back to the alert (the TrueNAS alert uuid). */
    val id: String,
    /** "CRITICAL", "WARNING", "INFO", … */
    val level: String,
    /** Short title only (e.g. "Pool degraded"); the full alert text stays on the phone. */
    val title: String,
)

@Serializable
data class WearStatus(
    val version: Int = WearLink.VERSION,
    val serverName: String? = null,
    val health: WearHealth = WearHealth.UNKNOWN,
    val poolsHealthy: Int = 0,
    val poolsTotal: Int = 0,
    val alertCount: Int = 0,
    val cpuPercent: Int? = null,
    val tempC: Int? = null,
    val online: Boolean = false,
    /** Short reason when offline ("Offline", "Sign in needed"). */
    val error: String? = null,
    /** Alert titles; empty when the phone's privacy setting keeps them off the watch. */
    val alerts: List<WearAlert> = emptyList(),
    /** Alert titles were held back on purpose (privacy), so the watch shows counts only. */
    val alertsHidden: Boolean = false,
    /** The active server has Wake-on-LAN set up (the watch then offers "Wake"). */
    val canWake: Boolean = false,
    /** "Waking…" / "Online" from the phone's wake controller, or null. */
    val wakeLabel: String? = null,
    val updatedAt: Long = 0L,
) {
    /** What a complication or tile title shows in a few characters. */
    fun shortText(): String = when {
        serverName == null -> "—"
        !online -> "Off"
        alertCount > 0 -> alertCount.toString()
        else -> "OK"
    }

    fun summary(): String = when {
        serverName == null -> "Open YTN on your phone"
        !online -> error ?: "Offline"
        else -> buildString {
            append(if (poolsTotal == 0) "No pools" else if (poolsHealthy == poolsTotal) "$poolsTotal pool${if (poolsTotal == 1) "" else "s"} healthy" else "$poolsHealthy/$poolsTotal pools healthy")
            append(" · ")
            append(when (alertCount) { 0 -> "no alerts"; 1 -> "1 alert"; else -> "$alertCount alerts" })
        }
    }
}
