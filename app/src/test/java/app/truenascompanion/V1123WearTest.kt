package app.truenascompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 1.12.3 (issue #12): release builds shrink resources, and R8 dropped `@array/android_wear_capabilities` (a `tools:keep`
 * in res/values isn't honoured), so the phone and the watch never saw each other. The fix keeps the array from
 * res/raw/keep.xml in both apps. CI builds debug only (not shrunk), so this test guards the keep rule itself; the
 * release APKs are checked with aapt2 (docs/BUILDING.md, "Wear OS capability check").
 */
class V1123WearTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun doc(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(root, path)).documentElement

    private fun assertKeeps(module: String, capability: String) {
        val keep = doc("$module/src/main/res/raw/keep.xml")
        val rule = keep.getAttributeNS("http://schemas.android.com/tools", "keep")
        assertTrue("$module keep.xml must keep the capability array, was '$rule'",
            rule.split(',').map { it.trim() }.contains("@array/android_wear_capabilities"))
        val wear = doc("$module/src/main/res/values/wear.xml")
        val arrays = wear.getElementsByTagName("string-array")
        val caps = (0 until arrays.length).map { arrays.item(it) as org.w3c.dom.Element }
            .single { it.getAttribute("name") == "android_wear_capabilities" }
        val items = caps.getElementsByTagName("item")
        assertEquals(listOf(capability), (0 until items.length).map { items.item(it).textContent.trim() })
    }

    @Test fun phoneKeepsItsCapability() = assertKeeps("app", "ytn_phone")
    @Test fun watchKeepsItsCapability() = assertKeeps("wear", "ytn_watch")
}
