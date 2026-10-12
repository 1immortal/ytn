package app.truenascompanion.ui.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.awaitJob
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.apps.ComposeEdit
import app.truenascompanion.data.model.Health
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.theme.LocalStatusColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ComposeEditorState(
    val loading: Boolean = true,
    val error: String? = null,
    val original: String = "",
    val text: String = "",
    val saving: Boolean = false,
    val saved: Boolean = false,
    val saveError: String? = null,
)

class ComposeEditorViewModel(private val c: AppContainer, private val appName: String) : ViewModel() {
    private val _state = MutableStateFlow(ComposeEditorState())
    val state = _state.asStateFlow()

    fun load() = viewModelScope.launch {
        try {
            val d = c.repository.call { it.appEditData(appName) }
            if (!d.customApp) { _state.value = ComposeEditorState(loading = false, error = "Only custom (compose) apps can be edited as YAML."); return@launch }
            val yaml = ComposeEdit.toYaml(d.values)
            _state.value = ComposeEditorState(loading = false, original = yaml, text = yaml)
        } catch (e: Throwable) {
            _state.value = ComposeEditorState(loading = false, error = e.userMessage())
        }
    }

    fun edit(t: String) = _state.update { it.copy(text = t, saveError = null) }
    fun revert() = _state.update { it.copy(text = it.original, saveError = null) }

    fun save() {
        val s = _state.value
        if (s.saving || !ComposeEdit.check(s.text).ok) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, saveError = null) }
            try {
                c.repository.call { api ->
                    val id = ComposeEdit.save(api, appName, s.text)
                    api.awaitJob(id, "app.update", timeoutMs = 15 * 60_000L)
                }
                _state.update { it.copy(saving = false, saved = true, original = s.text) }
            } catch (e: Throwable) {
                _state.update { it.copy(saving = false, saveError = e.userMessage()) }
            }
        }
    }
}

const val COMPOSE_FIELD_TAG = "compose_field"
const val COMPOSE_SAVE_TAG = "compose_save"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeEditorScreen(appName: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "compose:$appName") { ComposeEditorViewModel(it, appName) }
    val state by vm.state.collectAsStateWithLifecycle()
    var review by remember { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    LaunchedEffect(vm) { if (state.original.isEmpty() && state.error == null) vm.load() }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    val dirty = state.text != state.original
    androidx.activity.compose.BackHandler(enabled = dirty && !state.saving) { leave = true }

    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text("Edit compose"); Text(appName, style = MaterialTheme.typography.bodySmall) } },
            navigationIcon = { IconButton(onClick = { if (dirty) leave = true else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = {
                if (dirty) TextButton(onClick = vm::revert, enabled = !state.saving) { Text("Revert") }
                GlowButton(onClick = { review = true }, enabled = dirty && !state.saving && ComposeEdit.check(state.text).ok, modifier = Modifier.padding(end = 8.dp).testTag(COMPOSE_SAVE_TAG)) { Text("Review") }
            },
        )
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding()) {
            when {
                state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error != null -> InfoBanner(state.error!!, Modifier.padding(16.dp))
                else -> ComposeEditorContent(state.text, vm::edit, state.saving, state.saveError)
            }
        }
    }
    if (review) ComposeDiffDialog(state.original, state.text, appName,
        onConfirm = { review = false; vm.save() }, onDismiss = { review = false })
    if (leave) AlertDialog(
        onDismissRequest = { leave = false },
        title = { Text("Discard your changes?") },
        text = { Text("The compose file on the NAS stays as it was.") },
        confirmButton = { TextButton(onClick = { leave = false; onBack() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { leave = false }) { Text("Keep editing") } },
    )
}

/** Stateless editor body: check result, monospace text field, save progress/error. */
@Composable
fun ComposeEditorContent(text: String, onEdit: (String) -> Unit, saving: Boolean, saveError: String?) {
    val check = remember(text) { ComposeEdit.check(text) }
    val colors = LocalStatusColors.current
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            if (check.ok) {
                Icon(Icons.Rounded.CheckCircle, null, tint = colors.of(Health.HEALTHY))
                Spacer(Modifier.width(6.dp))
                Text("Valid YAML · ${check.services.size} service${if (check.services.size == 1) "" else "s"}: ${check.services.joinToString()}", style = MaterialTheme.typography.bodyMedium)
            } else {
                Icon(Icons.Rounded.Error, null, tint = colors.of(Health.CRITICAL))
                Spacer(Modifier.width(6.dp))
                Text(check.error!!.text, style = MaterialTheme.typography.bodyMedium, color = colors.of(Health.CRITICAL))
            }
        }
        check.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = colors.of(Health.WARNING)) }
        if (saving) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Saving and redeploying… TrueNAS checks the file with Docker first.", style = MaterialTheme.typography.bodySmall) }
        saveError?.let { InfoBanner("TrueNAS didn't accept it: $it", health = Health.CRITICAL, modifier = Modifier.padding(vertical = 6.dp)) }
        OutlinedTextField(
            value = text, onValueChange = onEdit, enabled = !saving,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth().weight(1f).testTag(COMPOSE_FIELD_TAG),
        )
    }
}

/** The diff shown before saving (changed lines with two lines of context). */
@Composable
fun ComposeDiffDialog(old: String, new: String, appName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val lines = remember(old, new) { ComposeEdit.diff(old, new) }
    val (added, removed) = remember(lines) { ComposeEdit.stats(lines) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save changes to $appName?") },
        text = {
            Column {
                Text("+$added  −$removed lines. TrueNAS validates the file, then recreates the app's containers (it restarts if it's running).", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                ComposeDiffView(lines, Modifier.heightIn(max = 380.dp))
            }
        },
        confirmButton = { GlowButton(onClick = onConfirm) { Text("Save & redeploy") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep editing") } },
    )
}

@Composable
fun ComposeDiffView(lines: List<ComposeEdit.DiffLine>, modifier: Modifier = Modifier) {
    val hunks = remember(lines) { ComposeEdit.hunks(lines) }
    val add = LocalStatusColors.current.containerOf(Health.HEALTHY)
    val del = LocalStatusColors.current.containerOf(Health.CRITICAL)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    LazyColumn(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        items(hunks) { l ->
            if (l == null) Text("  ⋯", style = mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                val (prefix, bg) = when (l.op) {
                    ComposeEdit.Op.ADD -> "+ " to add
                    ComposeEdit.Op.REMOVE -> "− " to del
                    ComposeEdit.Op.SAME -> "  " to androidx.compose.ui.graphics.Color.Transparent
                }
                Text(prefix + l.text, style = mono, softWrap = false, modifier = Modifier.background(bg).padding(horizontal = 4.dp))
            }
        }
    }
}
