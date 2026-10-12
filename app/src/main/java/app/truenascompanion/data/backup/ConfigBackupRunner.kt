package app.truenascompanion.data.backup

import android.net.Uri
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.awaitJob
import app.truenascompanion.data.files.FileTransfers
import app.truenascompanion.data.files.FilesApi
import app.truenascompanion.data.files.SaveTarget
import app.truenascompanion.data.files.SaveTargets
import app.truenascompanion.data.net.HttpClients
import app.truenascompanion.data.net.Keepalive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray

/** Runs a config backup or restore over the active server's route (same address and certificate pin as the socket). */
object ConfigBackupRunner {

    /** Streams `config.save` into [uri]; deletes the half-written file on failure. Returns the byte count. */
    suspend fun backup(c: AppContainer, uri: Uri, secretSeed: Boolean, rootKeys: Boolean, fileName: String, onProgress: (Long) -> Unit): Long {
        val resolver = c.context.contentResolver
        var ok = false
        try {
            val bytes = withContext(Dispatchers.IO) {
                val out = resolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("Can't write to the chosen file.")
                out.use { stream ->
                    c.repository.withEndpoint { api, target ->
                        val ticket = ConfigBackupApi(api).startDownload(secretSeed, rootKeys, fileName)
                        try {
                            val (client, _) = HttpClients.create(target, Keepalive.NONE)
                            FileTransfers(client, target.url).download(ticket.url, stream, -1) { done, _ -> onProgress(done) }
                        } catch (e: Throwable) {
                            withContext(NonCancellable) { FilesApi(api).abortJob(ticket.jobId) }
                            throw e
                        }
                    }
                }
            }
            if (bytes <= 0) throw IllegalStateException("TrueNAS sent an empty file.")
            ok = true
            c.repository.activeServer.value?.id?.let { c.settings.setLastConfigBackup(it) }
            return bytes
        } finally {
            if (!ok) withContext(NonCancellable + Dispatchers.IO) { SaveTargets.discard(resolver, SaveTarget.Document(uri)) }
        }
    }

    /**
     * Sends [uri] to `config.upload` and waits for TrueNAS to check and apply it. Returns normally once TrueNAS
     * accepted the file (it then reboots by itself); throws with TrueNAS's reason when it refused (nothing changed).
     */
    suspend fun restore(c: AppContainer, uri: Uri, name: String, size: Long, onUpload: (Long, Long) -> Unit, onChecking: () -> Unit) {
        val resolver = c.context.contentResolver
        c.repository.withEndpoint { api, target ->
            val token = FilesApi(api).uploadToken()
            val (client, _) = HttpClients.create(target, Keepalive.NONE)
            val jobId = FileTransfers(client, target.url).uploadTo(
                ConfigBackupApi.UPLOAD_METHOD, JsonArray(emptyList()), token, name, size,
                open = { resolver.openInputStream(uri) ?: throw IllegalStateException("Can't read the chosen file.") },
                onProgress = onUpload,
            )
            onChecking()
            api.awaitJob(jobId, ConfigBackupApi.UPLOAD_METHOD, timeoutMs = 5 * 60_000L)
        }
    }
}
