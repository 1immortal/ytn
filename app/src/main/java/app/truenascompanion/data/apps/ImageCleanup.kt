package app.truenascompanion.data.apps

import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.arr
import app.truenascompanion.data.api.long
import app.truenascompanion.data.api.obj
import app.truenascompanion.data.api.parseDate
import app.truenascompanion.data.api.prim
import app.truenascompanion.data.api.str
import app.truenascompanion.data.api.bool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A Docker image on the NAS (`app.image.query`). */
data class NasImage(
    val id: String,
    val tags: List<String>,
    val digests: List<String>,
    val size: Long,
    val createdMillis: Long?,
    val dangling: Boolean,
) {
    val label: String get() = tags.firstOrNull { it != "<none>:<none>" } ?: (digests.firstOrNull()?.substringBefore('@')?.let { "$it (untagged)" } ?: "Untagged image")
    val shortId: String get() = id.removePrefix("sha256:").take(12)
}

/**
 * 1.11.0: Apps › Unused images. TrueNAS 25.10 has no prune call, so the phone works out which images no app uses
 * (`app.query` → `active_workloads.images` and each container's image) and deletes the chosen ones one by one with
 * `app.image.delete(id, {force: false})`. Without `force` Docker itself refuses to remove an image any container
 * (running or stopped) still uses, so a mistake here can't break an app; such images are reported as "in use".
 */
object ImageCleanup {

    fun image(o: JsonObject): NasImage? {
        val id = o.str("id") ?: return null
        return NasImage(
            id = id,
            tags = o["repo_tags"].arr()?.mapNotNull { it.prim()?.contentOrNull }.orEmpty(),
            digests = o["repo_digests"].arr()?.mapNotNull { it.prim()?.contentOrNull }.orEmpty(),
            size = o.long("size") ?: 0,
            createdMillis = parseDate(o["created"]),
            dangling = o.bool("dangling") ?: false,
        )
    }

    /** `nginx` → `nginx:latest`, `docker.io/library/nginx:1` → `nginx:1`; digests (`repo@sha256:…`) kept as is. */
    fun normalize(ref: String): String {
        var r = ref.trim()
        r = r.removePrefix("docker.io/").removePrefix("index.docker.io/").removePrefix("registry-1.docker.io/")
        r = r.removePrefix("library/")
        if ('@' in r) return r
        val lastSlash = r.lastIndexOf('/')
        return if (r.indexOf(':', lastSlash + 1) < 0) "$r:latest" else r
    }

    /** Every image reference the apps use (normalized). */
    fun usedRefs(apps: List<JsonObject>): Set<String> = buildSet {
        apps.forEach { a ->
            val w = a["active_workloads"].obj() ?: return@forEach
            w["images"].arr()?.forEach { it.prim()?.contentOrNull?.let { r -> add(normalize(r)) } }
            w["container_details"].arr()?.forEach { c -> c.obj()?.str("image")?.let { r -> add(normalize(r)) } }
        }
    }

    fun isUsed(img: NasImage, used: Set<String>): Boolean =
        used.contains(img.id) || used.contains(img.id.removePrefix("sha256:")) ||
            img.tags.any { normalize(it) in used } || img.digests.any { normalize(it) in used }

    fun unused(images: List<NasImage>, used: Set<String>): List<NasImage> =
        images.filterNot { isUsed(it, used) }.sortedByDescending { it.size }

    fun total(images: List<NasImage>): Long = images.sumOf { it.size }

    suspend fun load(api: TrueNasApi): Pair<List<NasImage>, Set<String>> {
        val images = api.rpc("app.image.query").arr()?.mapNotNull { it.obj()?.let(::image) }.orEmpty()
        val apps = api.rpc("app.query").arr()?.mapNotNull { it.obj() }.orEmpty()
        return images to usedRefs(apps)
    }

    suspend fun delete(api: TrueNasApi, id: String) {
        api.rpc("app.image.delete", JsonPrimitive(id), buildJsonObject { put("force", false) })
    }

    /** Docker's "in use" refusal, in plain words. */
    fun friendlyDeleteError(message: String?): String = when {
        message == null -> "Couldn't delete"
        message.contains("conflict", true) || message.contains("in use", true) || message.contains("being used", true) -> "In use by a container"
        else -> message.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: "Couldn't delete"
    }
}
