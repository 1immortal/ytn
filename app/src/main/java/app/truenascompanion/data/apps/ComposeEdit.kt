package app.truenascompanion.data.apps

import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.asJobId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.common.FlowStyle
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException
import org.snakeyaml.engine.v2.exceptions.YamlEngineException

/**
 * 1.11.0: editing a custom app's Docker Compose file as YAML (Apps › app › Edit compose).
 *
 * TrueNAS 25.10 keeps a custom app's compose file as its user config (`app.query` with `retrieve_config`), so the
 * editor shows that, formatted as YAML. Saving calls `app.update(name, {custom_compose_config_string: yaml})`; TrueNAS
 * parses the YAML and runs `docker compose config` before it touches anything (errors come back on
 * `custom_compose_config_string`), then redeploys the app if it isn't stopped. The phone checks the YAML first (syntax
 * and the basic compose shape) so most mistakes show up with a line number before anything is sent.
 */
object ComposeEdit {
    /** Big enough for any realistic compose file; keeps the editor and the diff fast. */
    const val MAX_CHARS = 200_000

    data class Problem(val message: String, val line: Int? = null, val column: Int? = null) {
        val text: String get() = (line?.let { "Line $it" + (column?.let { c -> ", column $c" } ?: "") + ": " } ?: "") + message
    }

    data class Check(val error: Problem?, val warnings: List<String> = emptyList(), val services: List<String> = emptyList()) {
        val ok: Boolean get() = error == null
    }

    private fun loadSettings() = LoadSettings.builder()
        .setAllowDuplicateKeys(false)
        .setMaxAliasesForCollections(50)
        .setCodePointLimit(MAX_CHARS * 4)
        .build()

    fun check(yaml: String): Check {
        if (yaml.isBlank()) return Check(Problem("The compose file is empty."))
        if (yaml.length > MAX_CHARS) return Check(Problem("The file is too long to edit on the phone."))
        val tabLine = yaml.lineSequence().indexOfFirst { line -> line.takeWhile { it == ' ' || it == '\t' }.contains('\t') }
        if (tabLine >= 0) return Check(Problem("YAML doesn't allow tabs for indentation. Use spaces.", tabLine + 1))
        val doc = try {
            val all = Load(loadSettings()).loadAllFromString(yaml).toList()
            if (all.size > 1) return Check(Problem("Only one YAML document is allowed (remove the extra '---')."))
            all.firstOrNull()
        } catch (e: MarkedYamlEngineException) {
            val mark = e.problemMark.orElse(null) ?: e.contextMark.orElse(null)
            return Check(Problem(e.problem ?: "Invalid YAML", mark?.line?.plus(1), mark?.column?.plus(1)))
        } catch (e: YamlEngineException) {
            return Check(Problem(e.message ?: "Invalid YAML"))
        }
        val root = doc as? Map<*, *> ?: return Check(Problem("A compose file must be a mapping with a 'services:' section."))
        val services = root["services"] as? Map<*, *> ?: return Check(Problem("Missing 'services:' section."))
        if (services.isEmpty()) return Check(Problem("'services:' has no services."))
        val warnings = mutableListOf<String>()
        services.forEach { (name, def) ->
            val m = def as? Map<*, *>
            when {
                m == null -> return Check(Problem("Service '$name' must be a mapping."))
                m["image"] == null && m["build"] == null -> return Check(Problem("Service '$name' needs an 'image:'."))
                m["build"] != null && m["image"] == null -> warnings += "Service '$name' builds an image; TrueNAS has to build it on the NAS."
            }
            if (m?.get("privileged") == true) warnings += "Service '$name' runs privileged (full access to the NAS)."
        }
        root.keys.filterIsInstance<String>().filter { it !in KNOWN_TOP }.takeIf { it.isNotEmpty() }?.let {
            warnings += "Unknown top-level key${if (it.size > 1) "s" else ""}: ${it.joinToString()} (Docker may reject ${if (it.size > 1) "them" else "it"})."
        }
        return Check(null, warnings, services.keys.map { it.toString() })
    }

    private val KNOWN_TOP = setOf("services", "volumes", "networks", "configs", "secrets", "name", "version", "include")

    /** The stored config (JSON) as block-style YAML for the editor. */
    fun toYaml(config: JsonObject): String {
        val settings = DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).setIndent(2).setIndicatorIndent(0).setWidth(200).build()
        return Dump(settings).dumpToString(toJava(config))
    }

    private fun toJava(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> LinkedHashMap<String, Any?>().also { m -> e.forEach { (k, v) -> m[k] = toJava(v) } }
        is JsonArray -> e.map { toJava(it) }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            e.longOrNull != null -> e.longOrNull
            e.doubleOrNull != null -> e.doubleOrNull
            else -> e.content
        }
    }

    // --- Line diff shown before saving ---

    enum class Op { SAME, ADD, REMOVE }
    data class DiffLine(val op: Op, val text: String, val oldLine: Int?, val newLine: Int?)

    /** LCS line diff; falls back to "all removed / all added" beyond [limit] lines per side. */
    fun diff(old: String, new: String, limit: Int = 3_000): List<DiffLine> {
        val a = old.trimEnd('\n').split('\n'); val b = new.trimEnd('\n').split('\n')
        if (a.size > limit || b.size > limit) {
            return a.mapIndexed { i, t -> DiffLine(Op.REMOVE, t, i + 1, null) } + b.mapIndexed { i, t -> DiffLine(Op.ADD, t, null, i + 1) }
        }
        val n = a.size; val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val out = ArrayList<DiffLine>(n + m)
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out += DiffLine(Op.SAME, a[i], i + 1, j + 1); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out += DiffLine(Op.REMOVE, a[i], i + 1, null); i++ }
                else -> { out += DiffLine(Op.ADD, b[j], null, j + 1); j++ }
            }
        }
        while (i < n) { out += DiffLine(Op.REMOVE, a[i], i + 1, null); i++ }
        while (j < m) { out += DiffLine(Op.ADD, b[j], null, j + 1); j++ }
        return out
    }

    /** Changed lines with [context] unchanged lines around them; gaps become a single "…" marker (null). */
    fun hunks(lines: List<DiffLine>, context: Int = 2): List<DiffLine?> {
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { idx, l -> if (l.op != Op.SAME) for (k in (idx - context).coerceAtLeast(0)..(idx + context).coerceAtMost(lines.size - 1)) keep[k] = true }
        val out = ArrayList<DiffLine?>()
        var gap = false
        lines.forEachIndexed { idx, l ->
            if (keep[idx]) { out += l; gap = false } else if (!gap) { out += null; gap = true }
        }
        return out
    }

    fun stats(lines: List<DiffLine>): Pair<Int, Int> = lines.count { it.op == Op.ADD } to lines.count { it.op == Op.REMOVE }

    /** Starts `app.update` with the new compose YAML; returns the job id. */
    suspend fun save(api: TrueNasApi, appName: String, yaml: String): Long =
        api.rpc("app.update", JsonPrimitive(appName), buildJsonObject { put("custom_compose_config_string", yaml) }).asJobId()
            ?: throw TrueNasException.Rpc(0, null, "TrueNAS did not start the update.")
}
