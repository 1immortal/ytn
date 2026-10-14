package app.truenascompanion.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import app.truenascompanion.wearlink.WearAlert
import app.truenascompanion.wearlink.WearHealth
import app.truenascompanion.wearlink.WearStatus

/** Colours shared by the app, the tile and the complication. */
object WearColors {
    const val HEALTHY = 0xFF4CD964.toInt()
    const val WARNING = 0xFFFFB020.toInt()
    const val CRITICAL = 0xFFFF5A5F.toInt()
    const val UNKNOWN = 0xFF9AA0A6.toInt()
    const val ACCENT = 0xFF5AB4FF.toInt()

    fun of(s: WearStatus): Int = when {
        s.serverName == null || !s.online -> UNKNOWN
        s.health == WearHealth.CRITICAL -> CRITICAL
        s.health == WearHealth.WARNING || s.alertCount > 0 -> WARNING
        s.health == WearHealth.HEALTHY -> HEALTHY
        else -> UNKNOWN
    }

    fun level(level: String): Int = when (level.uppercase()) {
        "EMERGENCY", "ALERT", "CRITICAL", "ERROR" -> CRITICAL
        "WARNING" -> WARNING
        else -> ACCENT
    }

    fun healthWord(s: WearStatus): String = when {
        s.serverName == null -> "Not set up"
        !s.online -> s.error ?: "Offline"
        s.health == WearHealth.CRITICAL -> "Degraded"
        s.health == WearHealth.WARNING -> "Warning"
        s.alertCount > 0 -> "Alerts"
        s.health == WearHealth.HEALTHY -> "Healthy"
        else -> "Unknown"
    }
}

/** "Updated 4 min ago" (pure). */
fun updatedText(updatedAt: Long, now: Long = System.currentTimeMillis()): String {
    if (updatedAt <= 0) return "Waiting for the phone"
    val min = ((now - updatedAt) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> "Updated just now"
        min < 60 -> "Updated $min min ago"
        min < 24 * 60 -> "Updated ${min / 60} h ago"
        else -> "Updated ${min / (24 * 60)} d ago"
    }
}

@Composable
fun StatusDot(color: Int, label: String, size: Int = 14) {
    Box(Modifier.size(size.dp).background(Color(color), CircleShape).semantics { contentDescription = label })
}

/** The main screen: health, pools, alerts, CPU/temperature, then Refresh / Alerts / Wake. */
@Composable
fun StatusScreen(
    s: WearStatus,
    message: String?,
    onRefresh: () -> Unit,
    onAlerts: () -> Unit,
    onWake: () -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    val state = rememberScalingLazyListState()
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        state = state,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 20.dp)) {
                StatusDot(WearColors.of(s), WearColors.healthWord(s))
                Spacer(Modifier.width(8.dp))
                Text(s.serverName ?: "YTN", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item { Text(WearColors.healthWord(s), style = MaterialTheme.typography.displaySmall, color = Color(WearColors.of(s))) }
        item { Text(s.summary(), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (s.online && (s.cpuPercent != null || s.tempC != null)) item {
            Text(listOfNotNull(s.cpuPercent?.let { "CPU $it%" }, s.tempC?.let { "Disks $it °C" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        (message ?: s.wakeLabel)?.let { m -> item { Text(m, style = MaterialTheme.typography.bodySmall, color = Color(WearColors.ACCENT), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp)) } }
        item {
            Button(onClick = onAlerts, modifier = Modifier.fillMaxWidth(), label = { Text(if (s.alertCount == 0) "Alerts" else "Alerts (${s.alertCount})") })
        }
        item {
            Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.filledTonalButtonColors(), label = { Text("Refresh") })
        }
        if (s.canWake) item {
            Button(onClick = onWake, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.filledTonalButtonColors(), label = { Text("Wake NAS") })
        }
        item { Text(updatedText(s.updatedAt, now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp)) }
    }
}

@Composable
fun AlertListScreen(s: WearStatus, onAck: (WearAlert) -> Unit, onBack: () -> Unit) {
    val state = rememberScalingLazyListState()
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        state = state,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { ListHeader { Text(if (s.alertCount == 0) "No alerts" else "${s.alertCount} alert${if (s.alertCount == 1) "" else "s"}") } }
        if (s.alertsHidden) item {
            Text("Alert details are hidden on the watch (phone privacy setting). Open YTN on your phone to read them.",
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
        }
        s.alerts.forEach { a ->
            item(key = a.id) {
                Button(
                    onClick = { onAck(a) }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { StatusDot(WearColors.level(a.level), a.level.lowercase(), 10) },
                    label = { Text(a.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(a.level.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        item { Button(onClick = onBack, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(), label = { Text("Back") }) }
        item { Spacer(Modifier.size(24.dp)) }
    }
}

/** Asks before sending: acknowledging happens on the phone, which asks again (and unlocks). */
@Composable
fun AckConfirmScreen(a: WearAlert, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Acknowledge on phone?", style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.size(4.dp))
        Text(a.title, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.size(8.dp))
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth(), label = { Text("Send to phone") })
        Spacer(Modifier.size(4.dp))
        Button(onClick = onCancel, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(), label = { Text("Cancel") })
    }
}
