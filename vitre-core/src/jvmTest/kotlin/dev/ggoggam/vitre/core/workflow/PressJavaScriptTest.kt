package dev.ggoggam.vitre.core.workflow

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Executes the generated scripts against an event recorder; no browser defaults are simulated. */
class PressJavaScriptTest {
    @Test
    fun keypress_uses_character_codes_without_changing_keydown_or_keyup() {
        val scripts =
            buildJsonObject {
                for (key in listOf("a", "A", "1", "!", "é", "Space", "Enter", "Escape")) {
                    put(key, InputJs.script(WorkflowStep.Input.Press("#q", key), key))
                }
            }
        val runner = requireNotNull(javaClass.getResource("/press-events.cjs")).readText()
        val process = ProcessBuilder("node", "-e", runner).redirectErrorStream(true).start()
        try {
            process.outputStream.bufferedWriter().use { it.write(scripts.toString()) }
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Press JavaScript tests timed out")
            assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
