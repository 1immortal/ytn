package app.truenascompanion.wear

import app.truenascompanion.wearlink.WearLink

/**
 * 1.12.4 (issue #12): emergency reboot / shut down from the watch.
 *
 * The watch asks first, then sends [WearLink.MSG_POWER]. The phone never acts on the message itself: it posts a
 * notification that opens System › Power with the usual confirmation dialog (and the app lock / "confirm dangerous
 * actions" prompt when they are on). Only confirming there calls `system.reboot` / `system.shutdown`. The deep link is
 * signed ([app.truenascompanion.notify.DeepLinkGuard]) and expires after [TTL_MS], so an old notification does nothing.
 */
object WatchPower {
    const val TTL_MS = 5 * 60_000L
    private const val CLOCK_SKEW_MS = 60_000L

    /** The action in a watch message, or null for anything else. */
    fun parseMessage(data: ByteArray?): String? =
        data?.takeIf { it.size <= 32 }?.toString(Charsets.UTF_8)?.trim()?.takeIf { it == WearLink.POWER_REBOOT || it == WearLink.POWER_SHUTDOWN }

    /** What the phone does with a watch message (pure, unit tested). */
    sealed interface Decision {
        data class Prompt(val action: String, val serverId: String, val title: String, val arg: String) : Decision
        data object Disabled : Decision
        data object NoServer : Decision
        data object Invalid : Decision
    }

    fun decide(data: ByteArray?, enabled: Boolean, serverId: String?, serverName: String?, nowMs: Long): Decision {
        val action = parseMessage(data) ?: return Decision.Invalid
        if (!enabled) return Decision.Disabled
        val id = serverId ?: return Decision.NoServer
        return Decision.Prompt(action, id, promptTitle(action, serverName), arg(action, nowMs))
    }

    fun promptTitle(action: String, serverName: String?): String {
        val name = serverName?.takeIf { it.isNotBlank() } ?: "your NAS"
        return if (action == WearLink.POWER_SHUTDOWN) "Shut down $name? (from your watch)" else "Reboot $name? (from your watch)"
    }

    /** Deep link argument: the action and when the watch asked. */
    fun arg(action: String, nowMs: Long) = "$action:$nowMs"

    /** The action of a deep link argument, or null when it is malformed or older than [TTL_MS]. */
    fun parseArg(arg: String?, nowMs: Long): String? {
        val a = arg ?: return null
        val action = a.substringBefore(':')
        val at = a.substringAfter(':', "").toLongOrNull() ?: return null
        if (action != WearLink.POWER_REBOOT && action != WearLink.POWER_SHUTDOWN) return null
        if (at > nowMs + CLOCK_SKEW_MS || nowMs - at > TTL_MS) return null
        return action
    }
}
