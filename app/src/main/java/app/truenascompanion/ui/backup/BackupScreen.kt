package app.truenascompanion.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.data.backup.ConfigBackupPolicy
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.store.SettingsTransfer
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.DestructiveButton
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.components.SectionTitle
import app.truenascompanion.ui.system.SettingRow
import app.truenascompanion.util.Format
import java.time.LocalDate

/** Facts the stateless page needs (also built by screenshot tests). */
data class BackupPageState(
    val serverName: String?,
    val hostname: String?,
    val connected: Boolean,
    val lastBackup: Long?,
    val now: Long,
    val reminderDays: Int,
    val secretSeed: Boolean,
    val rootKeys: Boolean,
    val backupBytes: Long? = null,
    val busy: Boolean = false,
)

class BackupPageActions(
    val onSecretSeed: (Boolean) -> Unit = {},
    val onRootKeys: (Boolean) -> Unit = {},
    val onBackup: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onRestore: () -> Unit = {},
    val onReminder: (Int) -> Unit = {},
    val onExport: () -> Unit = {},
    val onImport: () -> Unit = {},
)

const val BACKUP_BUTTON_TAG = "backup_now"
const val RESTORE_BUTTON_TAG = "restore_pick"
const val RESTORE_CONFIRM_TAG = "restore_confirm"
const val RESTORE_TYPE_TAG = "restore_type"

/** System › Backup & restore (1.11.0). Lives inside the System page list. */
@Composable
fun BackupRestorePage() {
    val vm = appViewModel { BackupViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val server by vm.server.collectAsStateWithLifecycle()
    val last by vm.lastBackup.collectAsStateWithLifecycle()
    val reminder by vm.reminderDays.collectAsStateWithLifecycle()
    var seedWarning by remember { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf(false) }
    var importDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingPassword by remember { mutableStateOf<CharArray?>(null) }
    var pendingSecrets by remember { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.load() }
    LaunchedEffect(vm) { vm.events.collect { message = it } }

    val saveConfig = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ConfigBackupPolicy.mimeType(vm.archive))) { vm.backup(it) }
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.inspectRestore(it) }
    val saveSettings = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SettingsTransfer.MIME)) { uri ->
        pendingPassword?.let { vm.exportSettings(uri, it, pendingSecrets) }; pendingPassword = null
    }
    val pickSettings = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingPassword?.let { vm.openImport(uri, it) }; pendingPassword = null
    }

    val state = BackupPageState(
        serverName = server?.name, hostname = ui.hostname, connected = ui.connected, lastBackup = last, now = System.currentTimeMillis(),
        reminderDays = reminder, secretSeed = ui.secretSeed, rootKeys = ui.rootKeys, backupBytes = ui.backupBytes, busy = ui.busy,
    )
    BackupPageContent(state, BackupPageActions(
        onSecretSeed = vm::setSecretSeed, onRootKeys = vm::setRootKeys,
        onBackup = { if (ui.secretSeed) seedWarning = true else saveConfig.launch(vm.backupFileName()) },
        onCancel = vm::cancel,
        onRestore = { pickRestore.launch(arrayOf("*/*")) },
        onReminder = { vm.setReminder(it) },
        onExport = { exportDialog = true }, onImport = { importDialog = true },
    ))

    message?.let { m ->
        AlertDialog(onDismissRequest = { message = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } })
    }
    if (seedWarning) SecretSeedWarningDialog(
        onConfirm = { seedWarning = false; saveConfig.launch(vm.backupFileName()) },
        onDismiss = { seedWarning = false },
    )
    ui.restoreFile?.let { f ->
        val stage = ui.restore
        if (stage == null) RestoreGuardDialog(f, ui.hostname, server?.name, onConfirm = vm::restore, onDismiss = vm::dismissRestore)
        else RestoreProgressDialog(stage, ui.hostname ?: server?.name, onCancel = vm::cancel, onClose = vm::dismissRestore)
    }
    if (exportDialog) ExportSettingsDialog(
        onExport = { pw, secrets -> exportDialog = false; pendingPassword = pw; pendingSecrets = secrets; saveSettings.launch(SettingsTransfer.fileName(LocalDate.now())) },
        onDismiss = { exportDialog = false },
    )
    if (importDialog) ImportPasswordDialog(
        onOpen = { pw -> importDialog = false; pendingPassword = pw; pickSettings.launch(arrayOf("*/*")) },
        onDismiss = { importDialog = false },
    )
    ui.importPreview?.let { p -> ImportPreviewDialog(p, onMerge = { vm.applyImport(false) }, onReplace = { vm.applyImport(true) }, onDismiss = vm::dismissImport) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupPageContent(s: BackupPageState, a: BackupPageActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("TrueNAS configuration")
        ElevatedSection {
            SettingRow(Icons.Rounded.Backup, s.serverName ?: "Server", ConfigBackupPolicy.lastBackupText(s.lastBackup, s.now))
            Spacer(Modifier.height(8.dp))
            Text(
                "Saves the NAS's settings database: users, shares, tasks, apps settings, network and more (not your files). " +
                    "Keep a recent copy before updates and big changes.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SettingRow(Icons.Rounded.Key, "Include the secret seed",
                "Needed to restore stored passwords, cloud credentials and keys onto a reinstalled NAS", checked = s.secretSeed, onCheckedChange = a.onSecretSeed)
            SettingRow(Icons.Rounded.Key, "Include admin SSH keys", "root and truenas_admin authorized_keys", checked = s.rootKeys, onCheckedChange = a.onRootKeys)
            if (s.secretSeed) {
                Spacer(Modifier.height(8.dp))
                InfoBanner("With the secret seed, anyone who has this file can decrypt the passwords and keys stored on the NAS. Keep it private, like a password.", health = Health.CRITICAL)
            }
            Spacer(Modifier.height(12.dp))
            val bytes = s.backupBytes
            if (bytes != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloading… ${Format.bytes(bytes)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = a.onCancel) { Text("Cancel") }
                }
            } else {
                GlowButton(onClick = a.onBackup, enabled = s.connected, modifier = Modifier.fillMaxWidth().testTag(BACKUP_BUTTON_TAG)) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text("Back up now (.${if (s.secretSeed || s.rootKeys) "tar" else "db"})")
                }
            }
        }

        SectionTitle("Reminder")
        ElevatedSection {
            Text("Remind me when this phone's last backup is older than:", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfigBackupPolicy.REMINDER_CHOICES.forEach { d ->
                    FilterChip(selected = s.reminderDays == d, onClick = { a.onReminder(d) }, label = { Text(if (d == 0) "Off" else "$d days") })
                }
            }
            Text("Checked with the phone alerts (needs alerts on for this server).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionTitle("Restore")
        ElevatedSection {
            SettingRow(Icons.Rounded.Restore, "Restore a configuration", "Replaces every setting, then reboots the NAS")
            Spacer(Modifier.height(8.dp))
            Text(
                "TrueNAS checks the file first; if it isn't a valid configuration nothing changes. You'll have to type the NAS's name to confirm.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = a.onRestore, enabled = s.connected && s.hostname != null, modifier = Modifier.fillMaxWidth().testTag(RESTORE_BUTTON_TAG)) {
                Icon(Icons.Rounded.Upload, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Choose backup file…")
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Block, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Factory reset isn't offered here: it erases every setting with no file to go back to, and a phone isn't the place for that. Use the web UI if you really need it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionTitle("YTN settings (this phone)")
        ElevatedSection {
            SettingRow(Icons.Rounded.PhoneAndroid, "Move YTN to another phone", "Servers, addresses, certificate pins, alert rules, dashboard layout, quiet hours, tile")
            Spacer(Modifier.height(8.dp))
            Text(
                "The file is encrypted with a password you choose. Passwords and API keys stay out unless you include them. App lock settings aren't exported.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = a.onExport, enabled = !s.busy, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.CloudDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Export…") }
                OutlinedButton(onClick = a.onImport, enabled = !s.busy, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Upload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import…") }
            }
            if (s.busy) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
fun SecretSeedWarningDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var understood by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("This backup holds decryptable secrets") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("The secret seed is the key TrueNAS uses to encrypt stored passwords, cloud and replication credentials, and other keys. Together with the database it lets anyone read them.")
                Spacer(Modifier.height(8.dp))
                Text("Save it somewhere only you can open (not a shared folder), and delete old copies you don't need.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text("I understand", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { GlowButton(onClick = onConfirm, enabled = understood) { Text("Choose where to save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The restore guard: what will happen, the picked file, and type-to-confirm with the NAS's hostname. Confirming
 * then asks for the fingerprint when "Confirm dangerous actions" is on.
 */
@Composable
fun RestoreGuardDialog(file: RestoreFile, hostname: String?, serverName: String?, onConfirm: () -> Unit, onDismiss: () -> Unit, initialTyped: String = "") {
    val guard = app.truenascompanion.ui.lock.LocalDangerGuard.current
    var typed by rememberSaveable { mutableStateOf(initialTyped) }
    val ok = file.refuse == null && ConfigBackupPolicy.confirmMatches(typed, hostname)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Restore, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Restore configuration to ${serverName ?: hostname ?: "the NAS"}?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) { RestoreGuardBody(file, hostname, typed) { typed = it } }
        },
        confirmButton = {
            DestructiveButton(onClick = { guard.guard("Restore the NAS configuration", onConfirm) }, enabled = ok, modifier = Modifier.testTag(RESTORE_CONFIRM_TAG)) {
                Text("Restore & reboot", maxLines = 1)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The guard's explanation and type-to-confirm field (also rendered on its own by the screenshot test). */
@Composable
fun RestoreGuardBody(file: RestoreFile, hostname: String?, typed: String, onTyped: (String) -> Unit) {
    Column {
                RestoreFileFacts(file)
                Spacer(Modifier.height(10.dp))
                if (file.refuse != null) {
                    InfoBanner(file.refuse, health = Health.CRITICAL)
                } else {
                    Text("What happens:", fontWeight = FontWeight.SemiBold)
                    listOf(
                        "Every setting on the NAS is replaced by the file's: users, shares, tasks, network, apps settings.",
                        "The NAS reboots about 10 seconds after TrueNAS accepts the file. Shares, apps and VMs stop meanwhile.",
                        "If the file has a different network setup, the NAS may come back on another address.",
                        "Your data on the pools isn't touched. A file without the secret seed loses stored passwords and keys.",
                    ).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                    Spacer(Modifier.height(12.dp))
                    Text("Type the NAS's hostname to confirm:", style = MaterialTheme.typography.bodyMedium)
                    Text(hostname ?: "?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = typed, onValueChange = onTyped, singleLine = true,
                        label = { Text("Hostname") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().testTag(RESTORE_TYPE_TAG),
                    )
                }
    }
}

@Composable
private fun RestoreFileFacts(f: RestoreFile) {
    ElevatedSection(contentPadding = 12.dp) {
        Text(f.name, style = MaterialTheme.typography.titleSmall)
        Text(listOfNotNull(f.kind?.label ?: "Unknown file type", f.size.takeIf { it >= 0 }?.let { Format.bytes(it) }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RestoreProgressDialog(stage: RestoreStage, name: String?, onCancel: () -> Unit, onClose: () -> Unit) {
    val running = stage is RestoreStage.Uploading || stage == RestoreStage.Checking
    AlertDialog(
        onDismissRequest = { if (!running) onClose() },
        properties = DialogProperties(dismissOnBackPress = !running, dismissOnClickOutside = false),
        icon = { Icon(Icons.Rounded.Restore, null) },
        title = {
            Text(when (stage) {
                is RestoreStage.Uploading -> "Sending configuration…"
                RestoreStage.Checking -> "TrueNAS is checking the file…"
                RestoreStage.Rebooting -> "Restored. ${name ?: "The NAS"} is rebooting"
                is RestoreStage.Failed -> "Restore didn't happen"
            })
        },
        text = {
            Column {
                when (stage) {
                    is RestoreStage.Uploading -> {
                        val p = if (stage.total > 0) (stage.done.toFloat() / stage.total).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text("${Format.bytes(stage.done)} of ${Format.bytes(stage.total)}")
                    }
                    RestoreStage.Checking -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text("Nothing has changed yet. If the file isn't valid, TrueNAS stops here and keeps the current settings.")
                    }
                    RestoreStage.Rebooting -> Text("TrueNAS accepted the file and reboots in about 10 seconds. It usually takes a few minutes; YTN reconnects by itself when it's back. If the restored settings use another address, edit the server in YTN.")
                    is RestoreStage.Failed -> Text("TrueNAS kept its current settings.\n\n${stage.message}")
                }
            }
        },
        confirmButton = {
            if (running) { if (stage is RestoreStage.Uploading) TextButton(onClick = onCancel) { Text("Cancel") } }
            else TextButton(onClick = onClose) { Text("Close") }
        },
    )
}

@Composable
fun ExportSettingsDialog(onExport: (CharArray, Boolean) -> Unit, onDismiss: () -> Unit, initialSecrets: Boolean = false) {
    var pw by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var secrets by remember { mutableStateOf(initialSecrets) }
    val problem = SettingsTransfer.passwordProblem(pw, repeat)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export YTN settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Choose a password for the file. You'll need it to import; it can't be recovered.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(repeat, { repeat = it }, label = { Text("Repeat password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                    supportingText = { if (pw.isNotEmpty() && problem != null) Text(problem) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = secrets, onCheckedChange = { secrets = it })
                    Text("Include saved passwords, API keys and WireGuard configs", style = MaterialTheme.typography.bodyMedium)
                }
                if (secrets) InfoBanner("Anyone with this file and its password can sign in to your NAS as you. Use a strong password and keep the file private.", health = Health.CRITICAL)
            }
        },
        confirmButton = { GlowButton(onClick = { onExport(pw.toCharArray(), secrets) }, enabled = problem == null) { Text("Choose where to save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ImportPasswordDialog(onOpen: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import YTN settings") },
        text = {
            Column {
                Text("Enter the file's password, then pick the file. You'll see what it contains before anything changes.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { GlowButton(onClick = { onOpen(pw.toCharArray()) }, enabled = pw.isNotEmpty()) { Text("Choose file") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ImportPreviewDialog(p: SettingsTransfer.Preview, onMerge: () -> Unit, onReplace: () -> Unit, onDismiss: () -> Unit) {
    val guard = app.truenascompanion.ui.lock.LocalDangerGuard.current
    var replace by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import settings?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val from = listOfNotNull(p.appVersion.takeIf { it.isNotBlank() }?.let { "YTN $it" }, p.exportedAt.takeIf { it > 0 }?.let { Format.relativeTime(it) }).joinToString(", ")
                if (from.isNotBlank()) Text("Exported from $from", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text("${p.serverNames.size} server${if (p.serverNames.size == 1) "" else "s"}: ${p.serverNames.joinToString().ifBlank { "none" }}")
                if (p.alreadySaved.isNotEmpty()) Text("Already on this phone: ${p.alreadySaved.joinToString()}", style = MaterialTheme.typography.bodyMedium)
                Text(if (p.includesSecrets) "Includes passwords/keys for ${p.serversWithSecrets} server(s)." else "No passwords or keys: you'll sign in again on this phone.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = !replace, onClick = { replace = false })
                    Column { Text("Merge"); Text("Add the servers that aren't here yet (${p.newServers.size}). Keep everything else.", style = MaterialTheme.typography.bodySmall) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = replace, onClick = { replace = true })
                    Column { Text("Replace"); Text("Remove this phone's servers and use the file's servers and general settings.", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            if (replace) DestructiveButton(onClick = { guard.guard("Replace YTN settings", onReplace) }) { Text("Replace") }
            else GlowButton(onClick = onMerge, enabled = p.newServers.isNotEmpty()) { Text("Merge") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
