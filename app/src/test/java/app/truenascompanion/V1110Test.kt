package app.truenascompanion

import app.truenascompanion.data.apps.ComposeEdit
import app.truenascompanion.data.apps.ImageCleanup
import app.truenascompanion.data.apps.NasImage
import app.truenascompanion.data.backup.ConfigBackupPolicy
import app.truenascompanion.data.backup.ConfigFileKind
import app.truenascompanion.data.checkup.*
import app.truenascompanion.data.store.ServerExtras
import app.truenascompanion.data.store.SettingsExport
import app.truenascompanion.data.store.SettingsTransfer
import app.truenascompanion.quick.Automation
import app.truenascompanion.quick.QuickAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class V1110Test {
    private val day = ConfigBackupPolicy.DAY_MS
    private val now = 1_800_000_000_000L

    // ---- config backup policy
    @Test fun fileNames() {
        val n = ConfigBackupPolicy.fileName("My NAS", archive = true, date = LocalDate.of(2026, 10, 12))
        assertTrue(n.startsWith("truenas-config-") && n.endsWith("-2026-10-12.tar"))
        assertFalse(n.contains(' '))
        assertTrue(ConfigBackupPolicy.fileName(null, false, LocalDate.of(2026, 1, 2)).endsWith("2026-01-02.db"))
    }
    @Test fun reminders() {
        assertTrue(ConfigBackupPolicy.reminderDue(now - 40 * day, 30, now))
        assertFalse(ConfigBackupPolicy.reminderDue(now - 10 * day, 30, now))
        assertFalse(ConfigBackupPolicy.reminderDue(now - 400 * day, 0, now))
        assertFalse(ConfigBackupPolicy.shouldRemind(now - 40 * day, 30, now - day, now))
        assertTrue(ConfigBackupPolicy.shouldRemind(now - 40 * day, 30, now - 8 * day, now))
        assertEquals(3, ConfigBackupPolicy.daysSince(now - 3 * day - 5, now))
        assertNull(ConfigBackupPolicy.daysSince(null, now))
    }
    @Test fun restoreGuard() {
        assertTrue(ConfigBackupPolicy.confirmMatches(" NAS ", "nas"))
        assertFalse(ConfigBackupPolicy.confirmMatches("nas2", "nas"))
        assertFalse(ConfigBackupPolicy.confirmMatches("", null))
    }
    @Test fun sniffAndRefuse() {
        assertEquals(ConfigFileKind.DATABASE, ConfigBackupPolicy.sniff("SQLite format 3\u0000xx".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(ConfigFileKind.GZIP, ConfigBackupPolicy.sniff(byteArrayOf(0x1f, 0x8b.toByte(), 8)))
        val tar = ByteArray(512).also { "ustar".toByteArray().copyInto(it, 257) }
        assertEquals(ConfigFileKind.TAR, ConfigBackupPolicy.sniff(tar))
        assertNull(ConfigBackupPolicy.sniff("hello".toByteArray()))
        assertNotNull(ConfigBackupPolicy.refuseReason(0, ConfigFileKind.TAR))
        assertNotNull(ConfigBackupPolicy.refuseReason(11L * 1024 * 1024, ConfigFileKind.TAR))
        assertNotNull(ConfigBackupPolicy.refuseReason(100, null))
        assertNull(ConfigBackupPolicy.refuseReason(100, ConfigFileKind.DATABASE))
    }

    // ---- settings transfer
    private val export = SettingsExport(includesSecrets = true, perServer = mapOf("a" to ServerExtras(apiKey = "k")), appVersion = "1.11.0")
    @Test fun settingsRoundTrip() {
        val file = SettingsTransfer.encrypt(SettingsTransfer.encode(export), "correct horse".toCharArray(), iterations = 100_000)
        assertTrue(SettingsTransfer.looksLikeSettingsFile(file))
        val back = SettingsTransfer.decode(SettingsTransfer.decrypt(file, "correct horse".toCharArray()))
        assertEquals(export, back)
    }
    @Test fun settingsWrongPasswordAndTamper() {
        val file = SettingsTransfer.encrypt(SettingsTransfer.encode(export), "correct horse".toCharArray(), iterations = 100_000)
        assertTrue(runCatching { SettingsTransfer.decrypt(file, "wrong pass".toCharArray()) }.isFailure)
        val t = file.copyOf().also { it[8] = (it[8] + 1).toByte() } // iteration header byte (AAD)
        assertTrue(runCatching { SettingsTransfer.decrypt(t, "correct horse".toCharArray()) }.isFailure)
        assertTrue(runCatching { SettingsTransfer.decrypt("nope".toByteArray(), "x".toCharArray()) }.isFailure)
    }
    @Test fun settingsPasswordRules() {
        assertNotNull(SettingsTransfer.passwordProblem("short", "short"))
        assertNotNull(SettingsTransfer.passwordProblem("longenough", "different1"))
        assertNull(SettingsTransfer.passwordProblem("longenough", "longenough"))
        assertEquals("ytn-settings-2026-10-12.ytnsettings", SettingsTransfer.fileName(LocalDate.of(2026, 10, 12)))
    }
    @Test fun noSecretsFlag() {
        assertTrue(ServerExtras(password = "p").hasSecrets)
        assertFalse(ServerExtras(layout = "x").hasSecrets)
    }

    // ---- checkup
    private val good = CheckupInput(
        datasets = listOf("tank", "tank/media"),
        snapshotTasks = listOf(SnapTaskIn(1, "tank", true, emptyList(), true, false)),
        offBox = listOf(OffBoxIn("repl", listOf("tank"), true, true)),
        pools = listOf(PoolIn("tank", now - 3 * day, true)),
        smartSchedulesEnabled = 1,
        smb = listOf(SmbIn("media", true, false)), nfs = emptyList(), certificates = emptyList(), apiKeys = emptyList(),
        sshRunning = false, sshPasswordAuth = false, users = emptyList(), signedInUser = "admin", twoFactorEnabled = true,
        updateAvailable = false, boot = BootIn(true, "ONLINE", 100, 10), lastConfigBackup = now - day,
    )
    @Test fun checkupAllGood() {
        val r = Checkup.evaluate(good, now)
        val s = Checkup.score(r, emptyMap())
        assertEquals(r.filter { it.status == CheckStatus.FAIL || it.status == CheckStatus.WARN }.toString(), 0, s.issues)
        assertEquals(100, s.score)
    }
    @Test fun checkupFindsProblems() {
        val bad = good.copy(smb = listOf(SmbIn("pub", true, true)), snapshotTasks = emptyList(), lastConfigBackup = null)
        val r = Checkup.evaluate(bad, now)
        assertNotEquals(CheckStatus.PASS, r.first { it.id == CheckId.SMB_GUEST }.status)
        assertNotEquals(CheckStatus.PASS, r.first { it.id == CheckId.SNAPSHOT_COVERAGE }.status)
        assertNotEquals(CheckStatus.PASS, r.first { it.id == CheckId.CONFIG_BACKUP }.status)
        val s = Checkup.score(r, emptyMap())
        assertTrue(s.score < 100 && s.issues >= 3)
        val accepted = Checkup.score(r, mapOf(CheckId.SMB_GUEST.name to CheckMark.ACCEPTED))
        assertTrue(accepted.score > s.score)
        val hidden = Checkup.score(r, mapOf(CheckId.SMB_GUEST.name to CheckMark.HIDDEN))
        assertEquals(s.counted - 1, hidden.counted)
    }
    @Test fun checkupUnknownExcluded() {
        val r = Checkup.evaluate(CheckupInput(lastConfigBackupKnown = false), now)
        assertTrue(r.all { it.status == CheckStatus.UNKNOWN })
        assertEquals(0, Checkup.score(r, emptyMap()).counted)
    }
    @Test fun coverage() {
        assertTrue(Checkup.covered("tank/a", SnapTaskIn(1, "tank", true, emptyList(), true, false)))
        assertFalse(Checkup.covered("tank/a", SnapTaskIn(1, "tank", false, emptyList(), true, false)))
        assertFalse(Checkup.covered("tank/a", SnapTaskIn(1, "tank", true, listOf("tank/a"), true, false)))
        assertFalse(Checkup.covered("tank2", SnapTaskIn(1, "tank", true, emptyList(), true, false)))
    }

    // ---- compose editor
    @Test fun composeCheck() {
        assertTrue(ComposeEdit.check("services:\n  web:\n    image: nginx\n").ok)
        assertFalse(ComposeEdit.check("services:\n\tweb:\n").ok)
        assertFalse(ComposeEdit.check("a: 1\n").ok)
        assertFalse(ComposeEdit.check("services: [\n").ok)
        assertNotNull(ComposeEdit.check("services: [\n").error?.line)
        assertFalse(ComposeEdit.check("services: {}\n---\nservices: {}\n").ok)
        assertTrue(ComposeEdit.check("services:\n  web:\n    build: .\n").warnings.isNotEmpty() ||
            ComposeEdit.check("services:\n  web: {}\n").warnings.isNotEmpty())
    }
    @Test fun composeDiff() {
        val d = ComposeEdit.diff("a\nb\nc\n", "a\nB\nc\nd\n")
        assertEquals(2 to 1, ComposeEdit.stats(d))
        assertEquals(ComposeEdit.Op.SAME, d.first().op)
        assertEquals("d", d.last().text)
        val long = (1..40).joinToString("\n") { "l$it" }
        val h = ComposeEdit.hunks(ComposeEdit.diff(long, long.replace("l20", "x")))
        assertTrue(h.size < 15 && h.contains(null))
    }
    @Test fun composeYamlRoundTrip() {
        val cfg = Json.parseToJsonElement("""{"services":{"web":{"image":"nginx:1","ports":["8080:80"]}}}""") as JsonObject
        val y = ComposeEdit.toYaml(cfg)
        assertTrue(ComposeEdit.check(y).ok)
        assertTrue(y.contains("image: nginx:1"))
    }

    // ---- image cleanup
    @Test fun imageNormalize() {
        assertEquals("nginx:latest", ImageCleanup.normalize("nginx"))
        assertEquals("nginx:1", ImageCleanup.normalize("docker.io/library/nginx:1"))
        assertEquals("ghcr.io/a/b:latest", ImageCleanup.normalize("ghcr.io/a/b"))
        assertEquals("localhost:5000/x:latest", ImageCleanup.normalize("localhost:5000/x"))
    }
    @Test fun imageUnused() {
        val apps = listOf(Json.parseToJsonElement("""{"active_workloads":{"images":["nginx"],"container_details":[{"image":"redis:7"}]}}""") as JsonObject)
        val used = ImageCleanup.usedRefs(apps)
        val imgs = listOf(
            NasImage("sha256:1", listOf("nginx:latest"), emptyList(), 10, null, false),
            NasImage("sha256:2", listOf("redis:7"), emptyList(), 20, null, false),
            NasImage("sha256:3", listOf("old:1"), emptyList(), 30, null, false),
            NasImage("sha256:4", emptyList(), emptyList(), 40, null, true),
        )
        val u = ImageCleanup.unused(imgs, used)
        assertEquals(listOf("sha256:4", "sha256:3"), u.map { it.id })
        assertEquals(70, ImageCleanup.total(u))
    }

    // ---- automation
    @Test fun automationParse() {
        assertEquals(Automation.Request.Refresh, Automation.parse(Automation.REFRESH, null, null, null))
        assertNotNull(Automation.parse(Automation.OPEN, "ALERTS", null, null))
        assertNull(Automation.parse(Automation.OPEN, "nonsense", null, null))
        assertNull(Automation.parse("DELETE", null, null, null))
        val q = Automation.parse(Automation.QUICK_ACTION, null, QuickAction.SCRUB_POOL.id, " tank ") as Automation.Request.Quick
        assertEquals("tank", q.target)
        assertNull((Automation.parse(Automation.QUICK_ACTION, null, QuickAction.ALERTS.id, "x") as Automation.Request.Quick).target)
        assertNull(Automation.parse(Automation.QUICK_ACTION, null, QuickAction.SCRUB_POOL.id, "x".repeat(200))?.let { (it as Automation.Request.Quick).target })
    }
}
