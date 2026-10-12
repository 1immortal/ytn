package app.truenascompanion.screenshots

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.truenascompanion.R
import app.truenascompanion.data.apps.NasImage
import app.truenascompanion.data.backup.ConfigFileKind
import app.truenascompanion.data.checkup.*
import app.truenascompanion.data.model.Health
import app.truenascompanion.widget.WidgetSnapshot
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.quick.StatusTileText
import app.truenascompanion.ui.apps.ComposeEditorContent
import app.truenascompanion.ui.apps.ImagesContent
import app.truenascompanion.ui.apps.ImagesState
import app.truenascompanion.ui.backup.BackupPageActions
import app.truenascompanion.ui.backup.BackupPageContent
import app.truenascompanion.ui.backup.BackupPageState
import app.truenascompanion.ui.backup.RestoreFile
import app.truenascompanion.ui.backup.RestoreGuardBody
import app.truenascompanion.ui.checkup.CheckupContent
import app.truenascompanion.ui.checkup.CheckupState
import app.truenascompanion.ui.theme.TrueNasTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 1.11.0: backup page, restore guard, protection checkup, Quick Settings tile, compose editor, image cleanup. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class V1110ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "v1110_$name.png").absolutePath
    private val now = System.currentTimeMillis()
    private val day = 24L * 60 * 60 * 1000

    private fun setUp(dark: Boolean, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TrueNasTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        repeat(8) { rule.mainClock.advanceTimeBy(250); shadowOf(android.os.Looper.getMainLooper()).idle() }
    }
    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) { setUp(dark, content); rule.onRoot().captureRoboImage(out(name)) }

    @Test fun backupPage() = shot("backup", true) {
        BackupPageContent(
            BackupPageState("Example NAS", "nas.example.com", connected = true, lastBackup = now - 41 * day, now = now,
                reminderDays = 30, secretSeed = true, rootKeys = false),
            BackupPageActions(),
        )
    }

    /** The dialog itself doesn't settle under Robolectric; its body is the same composable, shown in a dialog-like card. */
    @Test fun restoreGuard() = shot("restore_guard", true) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).padding(20.dp), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(24.dp)) {
                    Text("Restore configuration to Example NAS?", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    RestoreGuardBody(
                        RestoreFile(android.net.Uri.parse("content://example/config.tar"), "truenas-config-nas-2026-10-01.tar", 48_000, ConfigFileKind.TAR, null),
                        hostname = "nas", typed = "na", onTyped = {},
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text("Cancel", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
                        Text("Restore & reboot (disabled until it matches)", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f), modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }

    @Test fun checkup() {
        val input = CheckupInput(
            datasets = listOf("tank", "tank/media", "tank/photos", "scratch"),
            snapshotTasks = listOf(SnapTaskIn(1, "tank", true, emptyList(), true, false)),
            offBox = listOf(OffBoxIn("Cloud sync", listOf("tank/photos"), false, true)),
            pools = listOf(PoolIn("tank", now - 10 * day, true), PoolIn("scratch", now - 60 * day, false)),
            smartSchedulesEnabled = 1,
            smb = listOf(SmbIn("media", true, false), SmbIn("public", true, true)), nfs = emptyList(),
            certificates = listOf(CertIn("truenas_default", now - 2 * day)),
            apiKeys = listOf(ApiKeyIn("old-script", "admin", now - 500 * day, null, false)),
            sshRunning = false, sshPasswordAuth = false, users = emptyList(), signedInUser = "admin", twoFactorEnabled = true,
            updateAvailable = true, updateVersion = "25.10.1", boot = BootIn(true, "ONLINE", 100, 30), lastConfigBackup = now - 3 * day,
        )
        shot("checkup", true) {
            CheckupContent(CheckupState(results = Checkup.evaluate(input, now), at = now),
                marks = mapOf(CheckId.UPDATE.name to CheckMark.ACCEPTED), onFix = {}, onMark = { _, _ -> })
        }
    }

    @Test fun quickSettingsTile() = shot("qs_tile", true) {
        val tile = StatusTileText.of(WidgetSnapshot("Example NAS", Health.HEALTHY, "2 healthy", 0, updatedAt = now))
        Column(Modifier.fillMaxSize().background(Color(0xFF101418)).padding(horizontal = 12.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            @Composable fun Tile(icon: Int, title: String, subtitle: String, active: Boolean, m: Modifier) {
                Row(m.clip(RoundedCornerShape(28.dp)).background(if (active) Color(0xFFB8C8FF) else Color(0xFF2E3640)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(icon), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(if (active) Color(0xFF0B1A4A) else Color(0xFFE6E9EF)))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(title, color = if (active) Color(0xFF0B1A4A) else Color(0xFFE6E9EF), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(subtitle, color = if (active) Color(0xFF243466) else Color(0xFFAAB2BF), fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(R.drawable.ic_tile_status, tile.label, tile.subtitle, tile.active, Modifier.weight(1f))
                Tile(R.drawable.ic_tile_scrub, "Scrub a pool", "TrueNAS", false, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Text("The status tile now refreshes itself when its data is older than 15 minutes, even without a home-screen widget. " +
                "Automation apps (Tasker plugin “YTN”) can refresh it too.", color = Color(0xFFAAB2BF), fontSize = 13.sp)
        }
    }

    @Test fun composeEditor() = shot("compose_editor", true) {
        ComposeEditorContent(
            text = "services:\n  web:\n    image: nginx:1.27\n    ports:\n      - \"8080:80\"\n    volumes:\n      - /mnt/tank/www:/usr/share/nginx/html:ro\n    restart: unless-stopped\n",
            onEdit = {}, saving = false, saveError = null,
        )
    }

    @Test fun imageCleanup() = shot("image_cleanup", true) {
        val imgs = listOf(
            NasImage("sha256:aa11", listOf("ghcr.io/example/app:1.2.0"), emptyList(), 812_000_000, now - 90 * day, false),
            NasImage("sha256:bb22", listOf("postgres:15"), emptyList(), 420_000_000, now - 200 * day, false),
            NasImage("sha256:cc33", emptyList(), listOf("redis@sha256:0123"), 120_000_000, now - 30 * day, true),
        )
        ImagesContent(ImagesState(loading = false, unused = imgs, totalImages = 11, selected = setOf("sha256:aa11", "sha256:cc33")),
            onToggle = {}, onSelectAll = {}, onDeleteSelected = {}, onDeleteAll = {})
    }
}
