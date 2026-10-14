package app.truenascompanion.wear

import app.truenascompanion.TrueNasApp
import app.truenascompanion.notify.DeepLink
import app.truenascompanion.quick.QuickActions
import app.truenascompanion.wearlink.WearLink
import app.truenascompanion.widget.WidgetRefreshWorker
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.launch

/**
 * 1.12.0: requests from the YTN watch app. Google Play services only delivers messages from the user's paired watch,
 * and only from an app with the same package name and signing key, so nothing else can reach this.
 *
 * - refresh: one forced status refresh (the same as the Quick Settings tile).
 * - wake: Wake-on-LAN for the active server (harmless, no confirmation; the phone sends the packet).
 * - ack: never acknowledges anything by itself. It posts a notification that opens Alerts in YTN, where the usual
 *   confirmation and app lock apply.
 */
class WearMessageService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val c = (applicationContext as TrueNasApp).container
        when (event.path) {
            WearLink.MSG_REFRESH -> WidgetRefreshWorker.refreshNow(this, force = true)
            WearLink.MSG_WAKE -> c.appScope.launch { c.wakeServer() }
            WearLink.MSG_ACK -> {
                val id = event.data?.toString(Charsets.UTF_8)?.take(64).orEmpty()
                val title = c.wear.current().alerts.firstOrNull { it.id == id }?.title ?: "an alert"
                c.notifier.postAutomationPrompt("Acknowledge \u201c$title\u201d from your watch?", QuickActions.intent(this, DeepLink.DEST_ALERTS))
            }
        }
    }
}
