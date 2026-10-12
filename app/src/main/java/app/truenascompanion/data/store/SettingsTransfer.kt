package app.truenascompanion.data.store

import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 1.11.0: encrypted settings files (System › Backup & restore › App settings).
 *
 * Format (`.ytnsettings`): `"YTNSET" (6) | version 1 (1) | PBKDF2 iterations (4, big endian) | salt (16) | IV (12) |
 * AES-256-GCM ciphertext+tag`. The key is PBKDF2-HMAC-SHA256 of the user's password (600 000 iterations, the OWASP
 * 2023 figure); the header is the GCM associated data, so changing any byte of it breaks decryption. A wrong password
 * and a damaged file look the same (the tag doesn't verify).
 */
object SettingsTransfer {
    const val ITERATIONS = 600_000
    /** Refuse files asking for absurd work (a hostile file could otherwise freeze the phone). */
    private const val MAX_ITERATIONS = 5_000_000
    private const val MIN_ITERATIONS = 100_000
    const val MIN_PASSWORD = 8
    const val EXTENSION = "ytnsettings"
    const val MIME = "application/octet-stream"
    private val MAGIC = "YTNSET".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val HEADER = 6 + 1 + 4 + 16 + 12

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    class BadFile(message: String) : Exception(message)

    fun passwordProblem(password: String, repeat: String): String? = when {
        password.length < MIN_PASSWORD -> "Use at least $MIN_PASSWORD characters."
        password != repeat -> "The passwords don't match."
        else -> null
    }

    fun fileName(date: java.time.LocalDate) = "ytn-settings-$date.$EXTENSION"

    fun encode(e: SettingsExport): ByteArray = json.encodeToString(SettingsExport.serializer(), e).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): SettingsExport {
        val e = runCatching { json.decodeFromString(SettingsExport.serializer(), bytes.toString(Charsets.UTF_8)) }.getOrNull()
            ?: throw BadFile("The file decrypted but its content isn't YTN settings.")
        if (e.format != "ytn-settings") throw BadFile("This isn't a YTN settings file.")
        if (e.version > 1) throw BadFile("This file comes from a newer YTN. Update the app first.")
        return e
    }

    fun encrypt(plain: ByteArray, password: CharArray, iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()): ByteArray {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION).putInt(iterations).put(salt).put(iv).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    fun looksLikeSettingsFile(head: ByteArray): Boolean = head.size >= MAGIC.size && head.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun decrypt(file: ByteArray, password: CharArray): ByteArray {
        if (file.size < HEADER + 16 || !looksLikeSettingsFile(file)) throw BadFile("This isn't a YTN settings file.")
        val b = ByteBuffer.wrap(file)
        b.position(MAGIC.size)
        if (b.get() != VERSION) throw BadFile("This file comes from a newer YTN. Update the app first.")
        val iterations = b.int
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw BadFile("The file's settings are out of range.")
        val salt = ByteArray(16).also { b.get(it) }
        val iv = ByteArray(12).also { b.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(file, 0, HEADER)
        return try {
            cipher.doFinal(file, HEADER, file.size - HEADER)
        } catch (e: AEADBadTagException) {
            throw BadFile("Wrong password, or the file is damaged.")
        }
    }

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** What an import would do, shown before anything changes. */
    data class Preview(
        val serverNames: List<String>,
        val newServers: List<String>,
        val alreadySaved: List<String>,
        val includesSecrets: Boolean,
        val serversWithSecrets: Int,
        val exportedAt: Long,
        val appVersion: String,
        val hasGeneral: Boolean,
    )

    fun preview(e: SettingsExport, savedIds: Set<String>): Preview = Preview(
        serverNames = e.servers.map { it.name },
        newServers = e.servers.filter { it.id !in savedIds }.map { it.name },
        alreadySaved = e.servers.filter { it.id in savedIds }.map { it.name },
        includesSecrets = e.includesSecrets,
        serversWithSecrets = e.perServer.values.count { it.hasSecrets },
        exportedAt = e.exportedAt,
        appVersion = e.appVersion,
        hasGeneral = e.global != GlobalExport(),
    )
}
