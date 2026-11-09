package app.truenascompanion

import app.truenascompanion.data.apps.ComposeEdit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/** snakeyaml-engine 3.x: the compose editor's YAML checks and formatting (behaviour pinned against 2.10). */
class ComposeEditYamlTest {
    private fun err(yaml: String) = ComposeEdit.check(yaml).error

    @Test fun validFileListsServices() {
        val c = ComposeEdit.check("services:\n  web:\n    image: nginx\n  db:\n    image: postgres\n")
        assertTrue(c.ok)
        assertEquals(listOf("web", "db"), c.services)
        assertTrue(c.warnings.isEmpty())
    }

    @Test fun duplicateKeysAreRejectedWithPosition() {
        val e = err("services:\n  web:\n    image: a\n    image: b\n")!!
        assertTrue(e.message, e.message.contains("duplicate key image"))
        assertEquals(4, e.line); assertEquals(5, e.column)
        val s = err("services:\n  web:\n    image: a\n  web:\n    image: b\n")!!
        assertEquals(4, s.line); assertEquals(3, s.column)
    }

    @Test fun syntaxErrorsCarryLineAndColumn() {
        val flow = err("services:\n  web:\n    image: nginx\n    ports: [\"80:80\"\n")!!
        assertEquals(5, flow.line); assertEquals(1, flow.column)
        assertTrue(flow.text.startsWith("Line 5, column 1: "))
        val indent = err("services:\n  web:\n    image: nginx\n   ports: x\n")!!
        assertEquals(4, indent.line); assertEquals(4, indent.column)
        val key = err("services:\n  web:\n    image: nginx\n  : x\n")!!
        assertEquals(4, key.line)
    }

    @Test fun aliasesWorkButAliasBombsAreStopped() {
        val ok = ComposeEdit.check("x-env: &env\n  A: 1\nservices:\n  web:\n    image: nginx\n    environment: *env\n")
        assertTrue(ok.error?.text, ok.ok)
        val bomb = buildString {
            append("a: &a [x, x, x, x, x, x, x, x, x, x]\n")
            for (i in 1..60) append("b$i: &b$i [*a]\n")
            append("services:\n  web:\n    image: n\n")
        }
        val e = err(bomb)!!
        assertTrue(e.message, e.message.contains("aliases"))
    }

    @Test fun nonScalarKeysAreRejected() {
        // 3.0 enforces scalar mapping keys; 2.10 accepted these silently. Compose keys are always strings.
        assertNotNull(err("services:\n  web:\n    image: nginx\n? [a, b]\n: c\n"))
        assertNotNull(err("services:\n  web:\n    image: nginx\n? {a: b}\n: c\n"))
    }

    @Test fun blockScalarsAndCommentsAroundThemParse() {
        assertTrue(ComposeEdit.check("services:\n  web:\n    image: nginx\n    command: |\n      sh -c 'echo hi\n        && sleep 1'\n").ok)
        assertTrue(ComposeEdit.check("services:\n  web:\n    image: nginx\n    command: >-\n      a\n      b\n").ok)
        // Shapes fixed in 3.2 (comment on a block-scalar indicator in a sequence; a comment indented less than the block).
        assertTrue(ComposeEdit.check("services:\n  web:\n    image: nginx\n    command:\n      - | # run\n        echo hi\n").ok)
        assertTrue(ComposeEdit.check("services:\n  web:\n    image: nginx\n    command: |\n      echo hi\n  # note\n    restart: always\n").ok)
        assertTrue(ComposeEdit.check("# top\nservices: # s\n  web:\n    image: nginx # pinned\n... # end\n").ok)
    }

    @Test fun tabsAndDocumentsStillChecked() {
        // 3.2 accepts some tabs as whitespace; the editor still refuses tab indentation itself.
        assertEquals(2, err("services:\n\tweb:\n    image: nginx\n")!!.line)
        assertTrue(ComposeEdit.check("services:\n  web:\n    image: nginx\n    command: \"a\tb\"\n").ok)
        assertNotNull(err("services: {}\n---\nservices: {}\n"))
        assertNotNull(err(""))
        assertNotNull(err("x".repeat(ComposeEdit.MAX_CHARS + 1)))
    }

    @Test fun trickyInputsNeverThrow() {
        listOf(
            "---\nservices:\n  web:\n    image: nginx\n--- # c\n",
            "services:\n  web:\n    image: nginx\n    x: !!null\n\n",
            "services:\n  web:\n    image: nginx\t\n",
            "? : \n", "&a [*a]\n", "!!binary x\n", "services: !!map [a]\n", "\"\\u\"\n", "- [\n", "{a: [}\n",
        ).forEach { y -> ComposeEdit.check(y) }
    }

    @Test fun formattedYamlLoadsBackToTheSameData() {
        val json = """{"name":"stack","services":{"web":{"image":"nginx:1","command":"sh -c 'echo a'\nsleep 1\n",
            "environment":{"FLAG":"yes","OFF":"off","NUM":"1.0","EMPTY":"","NULLS":"null","COLON":"a: b","HASH":"#x"},
            "ports":["8080:80"],"privileged":false,"cpus":1.5,"replicas":2,"labels":{"x":null}}},
            "volumes":{"data":{}}}"""
        val cfg = Json.parseToJsonElement(json) as JsonObject
        val yaml = ComposeEdit.toYaml(cfg)
        val check = ComposeEdit.check(yaml)
        assertTrue(check.error?.text, check.ok)
        assertEquals(listOf("web"), check.services)
        val back = Load(LoadSettings.builder().build()).loadFromString(yaml) as Map<*, *>
        val web = (back["services"] as Map<*, *>)["web"] as Map<*, *>
        assertEquals("sh -c 'echo a'\nsleep 1\n", web["command"])
        val env = web["environment"] as Map<*, *>
        assertEquals(mapOf("FLAG" to "yes", "OFF" to "off", "NUM" to "1.0", "EMPTY" to "", "NULLS" to "null", "COLON" to "a: b", "HASH" to "#x"), env)
        assertEquals(false, web["privileged"]); assertEquals(1.5, web["cpus"]); assertEquals(2, web["replicas"])
        assertEquals(listOf("8080:80"), web["ports"])
        assertTrue((web["labels"] as Map<*, *>).containsKey("x") && (web["labels"] as Map<*, *>)["x"] == null)
        assertEquals(listOf("name", "services", "volumes"), back.keys.toList())
        // Block style for non-empty collections (an empty mapping can only be written as {}).
        assertFalse(yaml, yaml.contains("["))
        assertTrue(yaml, yaml.contains("data: {}"))
    }
}
