package app.truenascompanion.ui.system

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.TrueNasApp
import app.truenascompanion.quick.Automation
import app.truenascompanion.ui.components.ElevatedSection
import kotlinx.coroutines.launch

/** 1.11.0: System › App lock & privacy › Automation. */
@Composable
fun AutomationSection() {
    val context = LocalContext.current
    val container = (context.applicationContext as TrueNasApp).container
    val on by container.settings.automationEnabled.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    val guard = app.truenascompanion.ui.lock.LocalLockGuard.current
    AutomationCard(on, Automation.permission(context)) { want ->
        if (want) guard.guard("Allow automation apps") { scope.launch { container.settings.setAutomationEnabled(true) } }
        else scope.launch { container.settings.setAutomationEnabled(false) }
    }
}

@Composable
fun AutomationCard(on: Boolean, permission: String, onToggle: (Boolean) -> Unit) {
    ElevatedSection {
        SettingRow(Icons.Rounded.SmartToy, "Allow automation apps", "Tasker plugin and intents: refresh, status, open screens and quick actions",
            checked = on, onCheckedChange = onToggle)
        Spacer(Modifier.height(8.dp))
        Text(
            "Automation can't change the NAS by itself: quick actions open YTN's confirmation, and the app lock still applies. " +
                "Tasker: add an action or condition under Plugin › YTN. Other apps need the permission $permission (Android asks you). " +
                "See docs/TECHNICAL.md for the intents.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
