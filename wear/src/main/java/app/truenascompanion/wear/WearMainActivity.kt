package app.truenascompanion.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.MaterialTheme
import app.truenascompanion.wearlink.WearLink
import app.truenascompanion.wearlink.WearStatus
import kotlinx.coroutines.launch

/** The glanceable app: status, alerts (acknowledge goes through the phone) and Wake-on-LAN through the phone. */
class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { PhoneLink.pull(this@WearMainActivity) }
        setContent {
            val status by WearStore.observe(this).collectAsStateWithLifecycle()
            val s = status ?: WearStatus()
            var screen by rememberSaveable { mutableStateOf("status") }
            var ackId by rememberSaveable { mutableStateOf<String?>(null) }
            var message by rememberSaveable { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            fun send(path: String, data: ByteArray = ByteArray(0), ok: String) = scope.launch {
                message = when (PhoneLink.send(this@WearMainActivity, path, data)) {
                    PhoneLink.Result.SENT -> ok
                    PhoneLink.Result.NO_PHONE -> "Phone not connected"
                    PhoneLink.Result.FAILED -> "Couldn't reach the phone"
                }
            }
            BackHandler(enabled = screen != "status") { screen = if (screen == "ack") "alerts" else "status" }
            MaterialTheme {
                val ack = ackId?.let { id -> s.alerts.firstOrNull { it.id == id } }
                when {
                    screen == "ack" && ack != null -> AckConfirmScreen(ack,
                        onConfirm = { send(WearLink.MSG_ACK, ack.id.toByteArray(), "Confirm on your phone"); screen = "status" },
                        onCancel = { screen = "alerts" })
                    screen == "alerts" -> AlertListScreen(s, onAck = { ackId = it.id; screen = "ack" }, onBack = { screen = "status" })
                    else -> StatusScreen(s, message,
                        onRefresh = { send(WearLink.MSG_REFRESH, ok = "Refreshing on the phone…") },
                        onAlerts = { screen = "alerts" },
                        onWake = { send(WearLink.MSG_WAKE, ok = "Waking… (up to ~2 min)") })
                }
            }
        }
    }
}
