package app.truenascompanion.ui.checkup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.checkup.CheckFix
import app.truenascompanion.data.checkup.CheckId
import app.truenascompanion.data.checkup.CheckMark
import app.truenascompanion.data.checkup.CheckResult
import app.truenascompanion.data.checkup.CheckStatus
import app.truenascompanion.data.checkup.Checkup
import app.truenascompanion.data.checkup.CheckupApi
import app.truenascompanion.data.checkup.CheckupScore
import app.truenascompanion.data.checkup.CheckupSummary
import app.truenascompanion.data.model.Health
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.components.SectionTitle
import app.truenascompanion.ui.theme.LocalStatusColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CheckupState(val loading: Boolean = false, val results: List<CheckResult>? = null, val error: String? = null, val at: Long = 0)

class CheckupViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(CheckupState())
    val state = _state.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val marks: StateFlow<Map<String, CheckMark>> = c.repository.activeServer.flatMapLatest { s -> s?.let { c.settings.checkupMarks(it.id) } ?: flowOf(emptyMap()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun run() {
        if (_state.value.loading) return
        val server = c.repository.activeServer.value
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val last = server?.let { c.settings.lastConfigBackupOnce(it.id) }
                val input = c.repository.call { CheckupApi(it).gather(last) }.copy(lastConfigBackupKnown = server != null)
                val now = System.currentTimeMillis()
                val results = Checkup.evaluate(input, now)
                _state.value = CheckupState(results = results, at = now)
                server?.let { s ->
                    val score = Checkup.score(results, c.settings.checkupMarks(s.id).first())
                    c.settings.saveCheckupSummary(s.id, CheckupSummary(score.score, score.issues, now))
                }
            } catch (e: Throwable) {
                _state.value = _state.value.copy(loading = false, error = e.userMessage())
            }
        }
    }

    fun mark(id: CheckId, mark: CheckMark?) {
        val s = c.repository.activeServer.value ?: return
        viewModelScope.launch {
            c.settings.setCheckupMark(s.id, id.name, mark)
            _state.value.results?.let { r ->
                val score = Checkup.score(r, marks.value.let { if (mark == null) it - id.name else it + (id.name to mark) })
                c.settings.saveCheckupSummary(s.id, CheckupSummary(score.score, score.issues, _state.value.at))
            }
        }
    }
}

/** Storage › Backup › Protection checkup (1.11.0). Read-only; every "Fix" button just opens a screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckupScreen(onBack: () -> Unit, onFix: (CheckFix) -> Unit) {
    val vm = appViewModel { CheckupViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val marks by vm.marks.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { if (state.results == null) vm.run() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Protection checkup") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = { IconButton(onClick = vm::run, enabled = !state.loading) { Icon(Icons.Rounded.Refresh, "Check again") } },
        )
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            CheckupContent(state, marks, onFix = onFix, onMark = vm::mark)
        }
    }
}

const val CHECKUP_LIST_TAG = "checkup_list"

@Composable
fun CheckupContent(state: CheckupState, marks: Map<String, CheckMark>, onFix: (CheckFix) -> Unit, onMark: (CheckId, CheckMark?) -> Unit) {
    val results = state.results
    LazyColumn(Modifier.fillMaxSize().testTag(CHECKUP_LIST_TAG), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            val score = results?.let { Checkup.score(it, marks) }
            ScoreCard(score, state.loading)
        }
        state.error?.let { e -> item { InfoBanner("Couldn't run the checkup: $e") } }
        if (results != null) {
            val ordered = Checkup.ordered(results, marks)
            val visible = ordered.filter { marks[it.id.name] != CheckMark.HIDDEN }
            val hidden = ordered.filter { marks[it.id.name] == CheckMark.HIDDEN }
            items(visible, key = { it.id.name }) { r -> CheckCard(r, marks[r.id.name], onFix, onMark) }
            if (hidden.isNotEmpty()) {
                item { SectionTitle("Hidden checks") }
                items(hidden, key = { "h-" + it.id.name }) { r -> CheckCard(r, CheckMark.HIDDEN, onFix, onMark) }
            }
            item {
                Text(
                    "The checkup only reads. It never changes anything on the NAS; the buttons take you to where you can.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
    }
}

@Composable
fun ScoreCard(score: CheckupScore?, loading: Boolean) {
    val c = LocalStatusColors.current
    val health = when {
        score == null || score.counted == 0 -> Health.UNKNOWN
        score.score >= 90 -> Health.HEALTHY
        score.score >= 60 -> Health.WARNING
        else -> Health.CRITICAL
    }
    ElevatedSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator(Modifier.size(56.dp))
                else CircularProgressIndicator(progress = { (score?.score ?: 0) / 100f }, modifier = Modifier.size(56.dp), color = c.of(health), trackColor = c.containerOf(health))
                Text(score?.takeIf { it.counted > 0 }?.score?.toString() ?: "–", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(if (loading && score == null) "Checking…" else score?.grade ?: "Checking…", style = MaterialTheme.typography.titleMedium)
                score?.let {
                    val parts = listOfNotNull(
                        if (it.issues == 0) "Nothing to fix" else "${it.issues} to look at",
                        it.unknown.takeIf { n -> n > 0 }?.let { n -> "$n couldn't be checked" },
                    )
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CheckCard(r: CheckResult, mark: CheckMark?, onFix: (CheckFix) -> Unit, onMark: (CheckId, CheckMark?) -> Unit) {
    val colors = LocalStatusColors.current
    val accepted = mark == CheckMark.ACCEPTED
    val (icon, health, label) = when {
        accepted -> Triple(Icons.Rounded.CheckCircle, Health.INFO, "Accepted risk")
        r.status == CheckStatus.PASS -> Triple(Icons.Rounded.CheckCircle, Health.HEALTHY, "OK")
        r.status == CheckStatus.WARN -> Triple(Icons.Rounded.Warning, Health.WARNING, "Warning")
        r.status == CheckStatus.FAIL -> Triple(Icons.Rounded.Error, Health.CRITICAL, "Problem")
        else -> Triple(Icons.Rounded.HelpOutline, Health.UNKNOWN, "Not checked")
    }
    var menu by remember { mutableStateOf(false) }
    ElevatedSection(contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = colors.of(health), modifier = Modifier.padding(top = 2.dp).size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "${r.id.title}: $label. ${r.summary}" }) {
                Text(r.id.title, style = MaterialTheme.typography.titleSmall)
                Text(r.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.MoreVert, "Options for ${r.id.title}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (mark == CheckMark.HIDDEN) DropdownMenuItem(text = { Text("Show again") }, onClick = { menu = false; onMark(r.id, null) })
                    else DropdownMenuItem(text = { Text("Hide this check") }, onClick = { menu = false; onMark(r.id, CheckMark.HIDDEN) })
                    if (r.status != CheckStatus.PASS && mark != CheckMark.HIDDEN) {
                        if (accepted) DropdownMenuItem(text = { Text("Undo accepted risk") }, onClick = { menu = false; onMark(r.id, null) })
                        else DropdownMenuItem(text = { Text("Accept this risk") }, onClick = { menu = false; onMark(r.id, CheckMark.ACCEPTED) })
                    }
                }
            }
        }
        val problem = r.status == CheckStatus.WARN || r.status == CheckStatus.FAIL
        if (problem && !accepted && mark != CheckMark.HIDDEN) {
            r.details.forEach { d -> Text("• $d", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 34.dp, top = 2.dp)) }
            Text(r.id.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 34.dp, top = 6.dp))
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { onFix(r.id.fix) }, modifier = Modifier.padding(start = 34.dp)) {
                Text(r.id.fix.label); Spacer(Modifier.width(6.dp)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(16.dp))
            }
        }
    }
}

/** Entry card at the top of Storage › Backup and on Home: last score, tap to open the checkup. */
@Composable
fun CheckupEntryCard(summary: CheckupSummary?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedSection(modifier = modifier.testTag("checkup_entry"), onClick = onOpen, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            app.truenascompanion.ui.components.IconBadge(Icons.Rounded.HealthAndSafety)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Protection checkup", style = MaterialTheme.typography.titleSmall)
                Text(
                    summary?.let { "Score ${it.score} · " + if (it.issues == 0) "nothing to fix" else "${it.issues} to look at" } ?: "Snapshots, off-site copies, scrubs, shares, sign-in security…",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpen) { Text(if (summary == null) "Run" else "Open") }
        }
    }
}
