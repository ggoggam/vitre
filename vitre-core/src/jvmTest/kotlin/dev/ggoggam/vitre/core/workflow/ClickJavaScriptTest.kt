package dev.ggoggam.vitre.core.workflow

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Executes generated production expressions; the fixture models DOM state, not browser input. */
class ClickJavaScriptTest {
    @Test
    fun click_validates_the_target_and_dispatches_once_in_one_turn() {
        val scripts =
            buildJsonObject {
                put("css", ClickJs.click(css("button[data-name=\"it's safe\"]")))
                put("xpath", ClickJs.click(xpath("//button")))
                put("handle", ClickJs.click(handle("e1")))
            }
        val runner = requireNotNull(javaClass.getResource("/click-safety.cjs")).readText()
        val process = ProcessBuilder("node", "-e", runner).redirectErrorStream(true).start()
        try {
            process.outputStream.bufferedWriter().use { it.write(scripts.toString()) }
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Click JavaScript tests timed out")
            assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
