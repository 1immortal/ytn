package app.truenascompanion.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.apps.ImageCleanup
import app.truenascompanion.data.apps.NasImage
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.DestructiveButton
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImagesState(
    val loading: Boolean = true,
    val error: String? = null,
    val unused: List<NasImage> = emptyList(),
    val totalImages: Int = 0,
    val selected: Set<String> = emptySet(),
    val deleting: Boolean = false,
    /** Per image: why it couldn't be deleted. */
    val failures: Map<String, String> = emptyMap(),
    val message: String? = null,
)

class ImagesViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ImagesState())
    val state = _state.asStateFlow()

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        try {
            val (images, used) = c.repository.call { ImageCleanup.load(it) }
            val unused = ImageCleanup.unused(images, used)
            _state.update { s -> s.copy(loading = false, unused = unused, totalImages = images.size, selected = s.selected.filter { id -> unused.any { it.id == id } }.toSet()) }
        } catch (e: Throwable) {
            _state.update { it.copy(loading = false, error = e.userMessage()) }
        }
    }

    fun toggle(id: String) = _state.update { s -> s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id) }
    fun selectAll(on: Boolean) = _state.update { s -> s.copy(selected = if (on) s.unused.map { it.id }.toSet() else emptySet()) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun delete(ids: Set<String>) {
        if (_state.value.deleting || ids.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(deleting = true, failures = emptyMap()) }
            val fails = mutableMapOf<String, String>()
            var freed = 0L; var ok = 0
            val byId = _state.value.unused.associateBy { it.id }
            for (id in ids) {
                try {
                    c.repository.call { ImageCleanup.delete(it, id) }
                    ok++; freed += byId[id]?.size ?: 0
                } catch (e: Throwable) {
                    fails[id] = ImageCleanup.friendlyDeleteError(e.message)
                }
            }
            _state.update { it.copy(deleting = false, failures = fails, selected = emptySet(),
                message = "Removed $ok image${if (ok == 1) "" else "s"}, freed ${Format.bytes(freed)}" + if (fails.isNotEmpty()) ". ${fails.size} couldn't be removed." else "") }
            load()
        }
    }
}

const val IMAGES_DELETE_TAG = "images_delete"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagesScreen(onBack: () -> Unit) {
    val vm = appViewModel { ImagesViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(vm) { vm.load() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Unused images") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { vm.load() }, enabled = !state.loading && !state.deleting) { Icon(Icons.Rounded.Refresh, "Refresh") } },
        )
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            ImagesContent(state, onToggle = vm::toggle, onSelectAll = vm::selectAll,
                onDeleteSelected = { confirm = state.selected }, onDeleteAll = { confirm = state.unused.map { it.id }.toSet() })
        }
    }
    confirm?.let { ids ->
        val imgs = state.unused.filter { it.id in ids }
        ConfirmDialog(
            title = "Remove ${imgs.size} image${if (imgs.size == 1) "" else "s"}?",
            text = "Frees about ${Format.bytes(ImageCleanup.total(imgs))}. No app uses ${if (imgs.size == 1) "it" else "them"}; " +
                "TrueNAS refuses to remove any image a container still needs. An app that needs one later downloads it again.",
            confirmLabel = "Remove", destructive = true, icon = Icons.Rounded.DeleteSweep,
            onConfirm = { confirm = null; vm.delete(ids) }, onDismiss = { confirm = null },
        )
    }
    state.message?.let { m -> androidx.compose.material3.AlertDialog(onDismissRequest = vm::consumeMessage, text = { Text(m) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = vm::consumeMessage) { Text("OK") } }) }
}

@Composable
fun ImagesContent(state: ImagesState, onToggle: (String) -> Unit, onSelectAll: (Boolean) -> Unit, onDeleteSelected: () -> Unit, onDeleteAll: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.loading || state.deleting) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { e -> item { InfoBanner(e) } }
        item {
            ElevatedSection {
                Text(if (state.unused.isEmpty() && !state.loading) "No unused images" else "${state.unused.size} unused of ${state.totalImages} images", style = MaterialTheme.typography.titleMedium)
                Text("${Format.bytes(ImageCleanup.total(state.unused))} can be reclaimed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Images left over from app updates and removed apps. Images any app uses aren't listed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.unused.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                        DestructiveButton(onClick = onDeleteSelected, enabled = state.selected.isNotEmpty() && !state.deleting, modifier = Modifier.weight(1f).testTag(IMAGES_DELETE_TAG)) {
                            Text(if (state.selected.isEmpty()) "Remove selected" else "Remove ${state.selected.size} selected", maxLines = 1)
                        }
                        OutlinedButton(onClick = onDeleteAll, enabled = !state.deleting) { Text("Remove all") }
                    }
                }
            }
        }
        if (state.unused.isNotEmpty()) item {
            val all = state.selected.size == state.unused.size
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = all, onCheckedChange = { onSelectAll(!all) })
                Text("Select all", style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(state.unused, key = { it.id }) { img ->
            ElevatedSection(contentPadding = 10.dp, onClick = { onToggle(img.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = img.id in state.selected, onCheckedChange = { onToggle(img.id) })
                    Column(Modifier.weight(1f)) {
                        Text(img.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                        Text(listOfNotNull(img.shortId, if (img.dangling) "dangling" else null, img.createdMillis?.let { Format.relativeTime(it) }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        state.failures[img.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                    Text(Format.bytes(img.size), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
