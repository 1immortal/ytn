package app.truenascompanion.quick

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.TrueNasApp
import app.truenascompanion.data.store.AppearanceSettings
import app.truenascompanion.ui.theme.TrueNasTheme

/** One choice in the plugin editor. */
data class PluginChoice(val kind: String, val screen: String? = null, val quick: String? = null, val condition: String? = null) {
    val label: String get() = Automation.blurb(kind, screen, quick, null, condition)
    val takesTarget: Boolean get() = kind == Automation.QUICK_ACTION && quick != QuickAction.ALERTS.id
}

fun pluginChoices(condition: Boolean): List<PluginChoice> =
    if (condition) Automation.Condition.entries.map { PluginChoice(Automation.CONDITION, condition = it.name) }
    else listOf(PluginChoice(Automation.REFRESH)) +
        Automation.SCREENS.keys.map { PluginChoice(Automation.OPEN, screen = it) } +
        Automation.QUICK.map { PluginChoice(Automation.QUICK_ACTION, quick = it.id) }

/**
 * Tasker / Locale plugin editor (1.11.0): the user picks what the action or condition does here, inside YTN; the
 * result bundle is signed so the fire receiver knows YTN made it.
 */
class TaskerEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as TrueNasApp).container
        val condition = intent?.action == Automation.LOCALE_EDIT_CONDITION
        val old = runCatching { intent?.getBundleExtra(Automation.LOCALE_EXTRA_BUNDLE) }.getOrNull()?.takeIf { Automation.verified(this, it) }
        val enabled = Automation.enabled(this)
        setContent {
            val appearance by container.settings.appearance.collectAsStateWithLifecycle(initialValue = AppearanceSettings())
            TrueNasTheme(themeMode = appearance.themeMode, dynamicColor = appearance.dynamicColor) {
                if (!enabled) {
                    AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text("Automation is off") },
                        text = { Text("Turn on automation in YTN first: System › App lock & privacy › Automation.") },
                        confirmButton = { TextButton(onClick = { finish() }) { Text("OK") } },
                    )
                } else PluginEditor(
                    choices = pluginChoices(condition),
                    initial = old?.let { PluginChoice(it.getString(Automation.B_KIND).orEmpty(), it.getString(Automation.B_SCREEN), it.getString(Automation.B_QUICK), it.getString(Automation.B_CONDITION)) },
                    initialTarget = old?.getString(Automation.B_TARGET).orEmpty(),
                    onSave = { c, target -> save(c, target) },
                    onCancel = { finish() },
                )
            }
        }
    }

    private fun save(c: PluginChoice, target: String) {
        val t = target.trim().takeIf { c.takesTarget && it.isNotEmpty() }
        val b = Bundle().apply {
            putString(Automation.B_KIND, c.kind)
            c.screen?.let { putString(Automation.B_SCREEN, it) }
            c.quick?.let { putString(Automation.B_QUICK, it) }
            c.condition?.let { putString(Automation.B_CONDITION, it) }
            t?.let { putString(Automation.B_TARGET, it) }
        }
        Automation.sign(this, b)
        setResult(RESULT_OK, Intent()
            .putExtra(Automation.LOCALE_EXTRA_BUNDLE, b)
            .putExtra(Automation.LOCALE_EXTRA_BLURB, Automation.blurb(c.kind, c.screen, c.quick, t, c.condition)))
        finish()
    }
}

@Composable
fun PluginEditor(choices: List<PluginChoice>, initial: PluginChoice?, initialTarget: String, onSave: (PluginChoice, String) -> Unit, onCancel: () -> Unit) {
    var picked by remember { mutableStateOf(initial?.takeIf { it in choices } ?: choices.first()) }
    var target by remember { mutableStateOf(initialTarget) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("YTN automation") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                choices.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { picked = c }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = picked == c, onClick = { picked = c })
                        Text(c.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (picked.takesTarget) {
                    OutlinedTextField(target, { target = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (picked.quick == QuickAction.SCRUB_POOL.id) "Pool (optional)" else "App (optional)") })
                }
                Text("Quick actions and screens open in YTN after you tap a notification; YTN still asks you to confirm.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(picked, target) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
