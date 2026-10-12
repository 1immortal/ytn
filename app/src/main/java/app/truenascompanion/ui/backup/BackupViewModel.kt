package app.truenascompanion.ui.backup

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.BuildConfigInfo
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.backup.ConfigBackupPolicy
import app.truenascompanion.data.backup.ConfigBackupRunner
import app.truenascompanion.data.backup.ConfigFileKind
import app.truenascompanion.data.files.TransferService
import app.truenascompanion.data.store.SettingsExport
import app.truenascompanion.data.store.SettingsTransfer
import app.truenascompanion.quick.QuickAction
import app.truenascompanion.quick.QuickActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** A file picked for restore, checked on the phone before anything is sent. */
data class RestoreFile(val uri: Uri, val name: String, val size: Long, val kind: ConfigFileKind?, val refuse: String?)

sealed interface RestoreStage {
    data class Uploading(val done: Long, val total: Long) : RestoreStage
    data object Checking : RestoreStage
    data object Rebooting : RestoreStage
    data class Failed(val message: String) : RestoreStage
}

data class BackupUi(
    val hostname: String? = null,
    val connected: Boolean = false,
    val secretSeed: Boolean = true,
    val rootKeys: Boolean = false,
    /** Bytes received while a backup is running. */
    val backupBytes: Long? = null,
    val restoreFile: RestoreFile? = null,
    val restore: RestoreStage? = null,
    val importPreview: SettingsTransfer.Preview? = null,
    val busy: Boolean = false,
)

class BackupViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(BackupUi())
    val ui: StateFlow<BackupUi> = _ui.asStateFlow()
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    private var job: Job? = null
    private var pendingImport: SettingsExport? = null

    val server = c.repository.activeServer

    @OptIn(ExperimentalCoroutinesApi::class)
    val lastBackup: StateFlow<Long?> = c.repository.activeServer.flatMapLatest { s -> s?.let { c.settings.lastConfigBackup(it.id) } ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val reminderDays: StateFlow<Int> = c.settings.backupReminderDays.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private fun say(m: String) { messages.trySend(m) }

    fun load() = viewModelScope.launch {
        runCatching { c.repository.call { it.systemInfo().hostname } }
            .onSuccess { h -> _ui.update { it.copy(hostname = h, connected = true) } }
            .onFailure { _ui.update { it.copy(connected = false) } }
    }

    fun setSecretSeed(on: Boolean) = _ui.update { it.copy(secretSeed = on) }
    fun setRootKeys(on: Boolean) = _ui.update { it.copy(rootKeys = on) }
    fun setReminder(days: Int) = viewModelScope.launch { c.settings.setBackupReminderDays(days) }

    val archive: Boolean get() = _ui.value.secretSeed || _ui.value.rootKeys
    fun backupFileName(): String = ConfigBackupPolicy.fileName(_ui.value.hostname ?: server.value?.name, archive, LocalDate.now())

    fun backup(uri: Uri?) {
        if (uri == null || job?.isActive == true) return
        val u = _ui.value
        job = viewModelScope.launch {
            _ui.update { it.copy(backupBytes = 0) }
            TransferService.start(c.context, "Backing up TrueNAS configuration")
            try {
                val bytes = ConfigBackupRunner.backup(c, uri, u.secretSeed, u.rootKeys, backupFileName()) { done -> _ui.update { it.copy(backupBytes = done) } }
                say("Configuration saved (${app.truenascompanion.util.Format.bytes(bytes)})")
            } catch (e: kotlinx.coroutines.CancellationException) {
                say("Backup cancelled")
            } catch (e: Throwable) {
                say("Backup failed: ${e.userMessage()}")
            } finally {
                TransferService.stop(c.context)
                _ui.update { it.copy(backupBytes = null) }
            }
        }
    }

    fun cancel() { job?.cancel() }

    fun inspectRestore(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) {
                runCatching {
                    val r = c.context.contentResolver
                    var name = "config"; var size = -1L
                    r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
                        if (cur.moveToFirst()) {
                            name = cur.getString(0) ?: name
                            if (!cur.isNull(1)) size = cur.getLong(1)
                        }
                    }
                    val head = ByteArray(512)
                    val n = r.openInputStream(uri)?.use { s -> var off = 0; while (off < head.size) { val k = s.read(head, off, head.size - off); if (k < 0) break; off += k }; off } ?: 0
                    val kind = ConfigBackupPolicy.sniff(head.copyOf(n))
                    RestoreFile(uri, name, size, kind, ConfigBackupPolicy.refuseReason(size, kind))
                }.getOrNull()
            }
            if (f == null) say("Couldn't read that file.") else _ui.update { it.copy(restoreFile = f, restore = null) }
        }
    }

    fun dismissRestore() { if (_ui.value.restore !is RestoreStage.Uploading && _ui.value.restore != RestoreStage.Checking) _ui.update { it.copy(restoreFile = null, restore = null) } }

    fun restore() {
        val f = _ui.value.restoreFile ?: return
        if (f.refuse != null || job?.isActive == true) return
        job = viewModelScope.launch {
            _ui.update { it.copy(restore = RestoreStage.Uploading(0, f.size)) }
            TransferService.start(c.context, "Restoring TrueNAS configuration")
            try {
                ConfigBackupRunner.restore(c, f.uri, f.name, f.size,
                    onUpload = { done, total -> _ui.update { it.copy(restore = RestoreStage.Uploading(done, total)) } },
                    onChecking = { _ui.update { it.copy(restore = RestoreStage.Checking) } })
                _ui.update { it.copy(restore = RestoreStage.Rebooting) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _ui.update { it.copy(restore = RestoreStage.Failed("Cancelled. If the upload had finished, TrueNAS may still apply it.")) }
            } catch (e: Throwable) {
                _ui.update { it.copy(restore = RestoreStage.Failed(e.userMessage())) }
            } finally {
                TransferService.stop(c.context)
            }
        }
    }

    // --- App settings ---

    fun exportSettings(uri: Uri?, password: CharArray, includeSecrets: Boolean) {
        if (uri == null) { password.fill(' '); return }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                val e = c.settings.exportSettings(includeSecrets).copy(
                    tileAction = QuickActions.tileAction(c.context).id,
                    exportedAt = System.currentTimeMillis(),
                    appVersion = BuildConfigInfo.versionName(c.context),
                )
                val bytes = withContext(Dispatchers.Default) { SettingsTransfer.encrypt(SettingsTransfer.encode(e), password) }
                withContext(Dispatchers.IO) {
                    (c.context.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("Can't write to the chosen file.")).use { it.write(bytes) }
                }
                say("Settings exported (${e.servers.size} server${if (e.servers.size == 1) "" else "s"}${if (includeSecrets) ", with passwords and keys" else ""})")
            } catch (e: Throwable) {
                withContext(Dispatchers.IO) { runCatching { android.provider.DocumentsContract.deleteDocument(c.context.contentResolver, uri) } }
                say("Export failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                password.fill(' ')
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun openImport(uri: Uri?, password: CharArray) {
        if (uri == null) { password.fill(' '); return }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                val raw = withContext(Dispatchers.IO) {
                    c.context.contentResolver.openInputStream(uri)?.use { s -> s.readBytes(1_000_000) } ?: throw IllegalStateException("Can't read the file.")
                }
                val e = withContext(Dispatchers.Default) { SettingsTransfer.decode(SettingsTransfer.decrypt(raw, password)) }
                pendingImport = e
                _ui.update { it.copy(importPreview = SettingsTransfer.preview(e, c.settings.savedServerIds())) }
            } catch (e: Throwable) {
                say(e.message ?: "Couldn't open the file.")
            } finally {
                password.fill(' ')
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun dismissImport() { pendingImport = null; _ui.update { it.copy(importPreview = null) } }

    fun applyImport(replace: Boolean) {
        val e = pendingImport ?: return
        viewModelScope.launch {
            try {
                val added = c.settings.importSettings(e, replace)
                if (replace) e.tileAction?.let { QuickAction.byId(it) }?.let { QuickActions.setTileAction(c.context, it) }
                say(if (replace) "Settings replaced: $added server${if (added == 1) "" else "s"}" else "Added $added server${if (added == 1) "" else "s"}")
            } catch (t: Throwable) {
                say("Import failed: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                dismissImport()
            }
        }
    }
}

/** Reads at most [max] bytes (a settings file is a few KB; refuse anything huge). */
private fun java.io.InputStream.readBytes(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > max) throw IllegalStateException("The file is too big to be a YTN settings file.")
    }
    return out.toByteArray()
}
