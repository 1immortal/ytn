package app.truenascompanion.ui.system

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.mapNotNull
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.NasSystemApi
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.JobInfo
import app.truenascompanion.data.model.JobState
import app.truenascompanion.data.model.NasUpdateStatus
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.jobs.JobProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NasUpdateViewModel(private val c: AppContainer) : ViewModel() {
    private val _status = MutableStateFlow<UiState<NasUpdateStatus>>(UiState.Loading)
    val status = _status.asStateFlow()
    private val _checking = MutableStateFlow(false)
    val checking = _checking.asStateFlow()
    private val _job = MutableStateFlow<JobInfo?>(null)
    val job = _job.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private var watch: Job? = null

    fun consumeMessage() { _message.value = null }

    // 1.11.0: "Back up config first" step before an update.
    private val _backupBytes = MutableStateFlow<Long?>(null)
    val backupBytes = _backupBytes.asStateFlow()
    private val _backupDone = MutableStateFlow(false)
    /** True once the pre-update backup finished; the screen then opens the update confirmation. */
    val backupDone = _backupDone.asStateFlow()
    fun consumeBackupDone() { _backupDone.value = false }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val lastBackup = c.repository.activeServer.flatMapLatest { s -> s?.let { c.settings.lastConfigBackup(it.id) } ?: kotlinx.coroutines.flow.flowOf(null) }

    suspend fun backupFileName(): String {
        val host = runCatching { c.repository.call { it.systemInfo().hostname } }.getOrNull() ?: c.repository.activeServer.value?.name
        return app.truenascompanion.data.backup.ConfigBackupPolicy.fileName(host, archive = true, date = java.time.LocalDate.now())
    }

    fun backupBeforeUpdate(uri: android.net.Uri?, name: String) {
        if (uri == null || _backupBytes.value != null) return
        viewModelScope.launch {
            _backupBytes.value = 0
            try {
                app.truenascompanion.data.backup.ConfigBackupRunner.backup(c, uri, secretSeed = true, rootKeys = false, fileName = name) { _backupBytes.value = it }
                _message.value = "Configuration backed up"
                _backupDone.value = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _message.value = "Backup failed: ${e.userMessage()}. The update wasn't started."
            } finally {
                _backupBytes.value = null
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        _checking.value = true
        try {
            _status.value = UiState.Success(c.repository.call { NasSystemApi(it).updateStatus() })
        } catch (e: Throwable) {
            if (_status.value !is UiState.Success) _status.value = UiState.Error(e.userMessage(), e)
            else _message.value = e.userMessage()
        } finally {
            _checking.value = false
        }
    }

    fun applyUpdate(reboot: Boolean = true) {
        if (_job.value != null) return
        viewModelScope.launch {
            try {
                val id = c.repository.call { NasSystemApi(it).startUpdate(reboot) }
                _message.value = "Update job #$id started"
                watchJob(id)
            } catch (e: Throwable) {
                _message.value = e.userMessage()
            }
        }
    }

    private fun watchJob(id: Long) {
        watch?.cancel()
        watch = viewModelScope.launch {
            try {
                // 1.7.1 (review P1-4): stop watching once the job ended (return@collect only skipped one emission).
                val done = c.repository.jobs()
                    .mapNotNull { list -> list.firstOrNull { it.id == id } }
                    .onEach { _job.value = it }
                    .first { it.state == JobState.SUCCESS || it.state == JobState.FAILED || it.state == JobState.ABORTED }
                _message.value = when (done.state) {
                    JobState.SUCCESS -> "Update finished. The NAS will reboot if that was selected."
                    JobState.FAILED -> "Update failed: ${done.error ?: done.progressText ?: "unknown error"}"
                    else -> "Update cancelled"
                }
                refresh()
                _job.value = null
            } catch (_: Throwable) {
                // connection dropped during update is expected when rebooting
            }
        }
    }
}

@Composable
fun NasUpdateSection() {
    val vm = appViewModel { NasUpdateViewModel(it) }
    val status by vm.status.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    var backupStep by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    val backupBytes by vm.backupBytes.collectAsStateWithLifecycle()
    val backupDone by vm.backupDone.collectAsStateWithLifecycle()
    val lastBackup by vm.lastBackup.collectAsStateWithLifecycle(initialValue = null)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var backupName by remember { mutableStateOf("truenas-config.tar") }
    val saveBackup = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/x-tar"),
    ) { uri -> vm.backupBeforeUpdate(uri, backupName) }
    LaunchedEffect(backupDone) { if (backupDone) { vm.consumeBackupDone(); confirm = true } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.refresh() }
    }

    NasUpdateCard(status, checking, job, message, onCheck = { vm.refresh() }, onApply = { backupStep = true }, onNotes = { showNotes = true })

    if (backupStep || backupBytes != null) {
        UpdateBackupStepDialog(
            lastBackup = lastBackup, now = System.currentTimeMillis(), backupBytes = backupBytes,
            onBackup = { scope.launch { backupName = vm.backupFileName(); backupStep = false; saveBackup.launch(backupName) } },
            onSkip = { backupStep = false; confirm = true },
            onDismiss = { backupStep = false },
        )
    }
    if (confirm) {
        val ver = (status as? UiState.Success)?.data?.newVersion ?: "the new version"
        ConfirmDialog(
            title = "Update TrueNAS to $ver?",
            text = "The NAS downloads and installs the update, then reboots. Apps, shares and VMs will be unavailable for several minutes. Keep this phone on the same network until the job starts.",
            confirmLabel = "Update & reboot",
            destructive = true,
            icon = Icons.Rounded.SystemUpdateAlt,
            onConfirm = { confirm = false; vm.applyUpdate(reboot = true) },
            onDismiss = { confirm = false },
        )
    }
    if (showNotes) {
        val d = (status as? UiState.Success)?.data
        AlertDialog(
            onDismissRequest = { showNotes = false },
            title = { Text(d?.newVersion ?: "Release notes") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).height(360.dp)) {
                    Text(d?.releaseNotes?.takeIf { it.isNotBlank() } ?: d?.changelog ?: "No notes.", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { showNotes = false }) { Text("Close") } },
        )
    }
}

/** Stateless TrueNAS update card (System › Updates & boot; also rendered by the screenshot tests). */
@Composable
fun NasUpdateCard(
    status: UiState<NasUpdateStatus>,
    checking: Boolean,
    job: JobInfo?,
    message: String?,
    onCheck: () -> Unit,
    onApply: () -> Unit,
    onNotes: () -> Unit,
) {
    ElevatedSection {
        SettingRow(
            Icons.Rounded.SystemUpdateAlt,
            "TrueNAS updates",
            when (val s = status) {
                UiState.Loading -> "Checking…"
                is UiState.Error -> s.message
                is UiState.Success -> when {
                    s.data.updateAvailable -> "Update available: ${s.data.newVersion}"
                    s.data.rebootRequired -> "Reboot required to finish an update"
                    s.data.upToDate -> "Up to date"
                    else -> s.data.errorReason ?: s.data.code
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        when (val s = status) {
            UiState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is UiState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            is UiState.Success -> {
                val d = s.data
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(
                        when {
                            d.updateAvailable -> Health.WARNING
                            d.rebootRequired -> Health.WARNING
                            d.upToDate -> Health.HEALTHY
                            else -> Health.CRITICAL
                        },
                        when {
                            d.updateAvailable -> "Available"
                            d.rebootRequired -> "Reboot"
                            d.upToDate -> "Current"
                            else -> d.code
                        },
                    )
                    d.currentTrain?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (d.updateAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Text("New version ${d.newVersion}", style = MaterialTheme.typography.bodyLarge)
                    val summary = d.releaseNotes?.takeIf { it.isNotBlank() } ?: d.changelog
                    summary?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it.take(280) + if (it.length > 280) "…" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = onNotes) { Text("Full notes") }
                    }
                }
                d.downloadPercent?.let { pct ->
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { (pct / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(d.downloadDescription ?: "Downloading… ${pct.toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        job?.let {
            Spacer(Modifier.height(10.dp))
            JobProgress(it)
            Text(it.progressText ?: it.state.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCheck, enabled = !checking && job == null) {
                if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Check")
            }
            val canApply = (status as? UiState.Success)?.data?.updateAvailable == true && job == null
            GlowButton(onClick = onApply, enabled = canApply) { Text("Download & update") }
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            LaunchedEffect(it) { kotlinx.coroutines.delay(50); /* shown inline */ }
        }
    }
}

/** 1.11.0: offered before every TrueNAS update. */
@Composable
fun UpdateBackupStepDialog(lastBackup: Long?, now: Long, backupBytes: Long?, onBackup: () -> Unit, onSkip: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (backupBytes == null) onDismiss() },
        icon = { androidx.compose.material3.Icon(Icons.Rounded.SystemUpdateAlt, null) },
        title = { Text("Back up the configuration first?") },
        text = {
            Column {
                Text("If the update goes wrong, a fresh configuration backup gets the NAS back to how it is now: users, shares, tasks and settings.")
                Spacer(Modifier.height(8.dp))
                Text(app.truenascompanion.data.backup.ConfigBackupPolicy.lastBackupText(lastBackup, now), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("The backup includes the secret seed, so stored passwords and keys can be restored too. Keep the file private.", style = MaterialTheme.typography.bodySmall)
                if (backupBytes != null) {
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Downloading… ${app.truenascompanion.util.Format.bytes(backupBytes)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { app.truenascompanion.ui.components.GlowButton(onClick = onBackup, enabled = backupBytes == null) { Text("Back up first") } },
        dismissButton = { TextButton(onClick = onSkip, enabled = backupBytes == null) { Text("Skip") } },
    )
}
