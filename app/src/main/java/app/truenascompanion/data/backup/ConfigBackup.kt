package app.truenascompanion.data.backup

import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.arr
import app.truenascompanion.data.api.prim
import app.truenascompanion.data.model.DownloadTicket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * 1.11.0: TrueNAS configuration backup and restore (System › Backup & restore).
 *
 * - **Backup**: `core.download("config.save", [{secretseed, root_authorized_keys}], filename, true)` returns
 *   `[job_id, "/_download/…"]`; the phone streams the file into a place the user picked (Storage Access Framework).
 *   With no option set TrueNAS returns only the database (`.db`); with any option set it returns a tar holding the
 *   database plus the secret seed (`pwenc_secret`) and/or the admin users' `authorized_keys` (25.10 `config.save`).
 * - **Restore**: `POST /_upload` with `{"method":"config.upload","params":[]}` and the file (one-time token). TrueNAS
 *   (25.10 `config.upload`, FULL_ADMIN only, max 10 MB) checks the database with `migrate` *before* it replaces
 *   anything; an invalid file fails the job and the current settings stay. A valid one replaces every setting and the
 *   NAS reboots 10 seconds later.
 * - **Factory reset** (`config.reset`) is deliberately not offered (see docs/TECHNICAL.md).
 */
class ConfigBackupApi(private val api: TrueNasApi) {

    suspend fun startDownload(secretSeed: Boolean, rootKeys: Boolean, filename: String): DownloadTicket {
        val options = buildJsonObject {
            put("secretseed", secretSeed)
            put("root_authorized_keys", rootKeys)
        }
        val res = api.rpc(
            "core.download", JsonPrimitive("config.save"), JsonArray(listOf(options)), JsonPrimitive(filename), JsonPrimitive(true),
        ).arr()
        val id = res?.getOrNull(0)?.prim()?.longOrNull
        val url = res?.getOrNull(1)?.prim()?.contentOrNull
        if (id == null || url == null || !url.startsWith("/_download/")) throw TrueNasException.Rpc(0, null, "TrueNAS did not return a download link.")
        return DownloadTicket(id, url)
    }

    companion object {
        const val UPLOAD_METHOD = "config.upload"
    }
}

/** What a picked restore file looks like, from its first bytes. */
enum class ConfigFileKind(val label: String) { DATABASE("TrueNAS database (.db)"), TAR("Configuration archive (.tar)"), GZIP("Compressed archive (.tar.gz)") }

/** Pure rules behind the backup page (unit tested). */
object ConfigBackupPolicy {
    /** `config.upload` refuses anything bigger (25.10: `max_size = 10485760`). */
    const val MAX_UPLOAD_BYTES = 10L * 1024 * 1024
    const val DAY_MS = 24L * 60 * 60 * 1000
    val REMINDER_CHOICES = listOf(0, 7, 14, 30, 60, 90)
    const val DEFAULT_REMINDER_DAYS = 30

    fun safeHost(hostname: String?): String =
        hostname.orEmpty().lowercase().replace(Regex("[^a-z0-9.-]+"), "-").trim('-', '.').take(48).ifBlank { "nas" }

    /** `truenas-config-<hostname>-<yyyy-mm-dd>.tar` (with secret seed or keys) or `.db` (database only). */
    fun fileName(hostname: String?, archive: Boolean, date: LocalDate): String =
        "truenas-config-${safeHost(hostname)}-$date.${if (archive) "tar" else "db"}"

    fun mimeType(archive: Boolean) = if (archive) "application/x-tar" else "application/octet-stream"

    fun reminderDue(lastMillis: Long?, days: Int, now: Long): Boolean =
        days > 0 && (lastMillis == null || now - lastMillis > days * DAY_MS)

    fun daysSince(lastMillis: Long?, now: Long): Int? = lastMillis?.let { ((now - it).coerceAtLeast(0) / DAY_MS).toInt() }

    fun lastBackupText(lastMillis: Long?, now: Long): String = when (val d = daysSince(lastMillis, now)) {
        null -> "No backup from this phone yet"
        0 -> "Last backup from this phone: today"
        1 -> "Last backup from this phone: yesterday"
        else -> "Last backup from this phone: $d days ago"
    }

    /** Type-to-confirm for restore: the NAS hostname, ignoring case and surrounding spaces. */
    fun confirmMatches(typed: String, hostname: String?): Boolean =
        !hostname.isNullOrBlank() && typed.trim().equals(hostname.trim(), ignoreCase = true)

    /** SQLite database, tar (ustar magic at 257) or gzip; null for anything else (refused before upload). */
    fun sniff(head: ByteArray): ConfigFileKind? {
        val sqlite = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
        if (head.size >= sqlite.size && head.copyOfRange(0, sqlite.size).contentEquals(sqlite)) return ConfigFileKind.DATABASE
        if (head.size >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()) return ConfigFileKind.GZIP
        if (head.size >= 262 && String(head, 257, 5, Charsets.ISO_8859_1) == "ustar") return ConfigFileKind.TAR
        return null
    }

    /** Why a picked file can't be restored, or null when it may be sent. */
    fun refuseReason(size: Long, kind: ConfigFileKind?): String? = when {
        size <= 0 -> "The file is empty."
        size > MAX_UPLOAD_BYTES -> "The file is bigger than 10 MB, the most TrueNAS accepts for a configuration."
        kind == null -> "This doesn't look like a TrueNAS configuration backup (.db or .tar)."
        else -> null
    }
}
