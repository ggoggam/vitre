package dev.ggoggam.vitre.core.workflow

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Executes the actual generated expressions in Node's VM with a small DOM fixture. Requires Node. */
class SnapshotJavaScriptTest {
    @Test
    fun handles_and_redaction_survive_document_changes_and_dom_mutation() {
        val scripts =
            buildJsonObject {
                put("snapshot", SnapshotJs.snapshot(100, 120))
                put("nextSnapshot", SnapshotJs.snapshot(100, 120))
                put("redactedSnapshot", SnapshotJs.snapshot(100, 120, SnapshotPolicy(8, listOf(".secret"))))
                put("invalidSnapshot", SnapshotJs.snapshot(100, 120, SnapshotPolicy(redactSelectors = listOf("["))))
                put("resolve", SnapshotJs.resolve("REF"))
                put("status", SnapshotJs.statusOf("REF"))
                put("click", SnapshotJs.guarded(listOf("REF"), "${SnapshotJs.resolve("REF")}?.click()"))
            }
        val runner = requireNotNull(javaClass.getResource("/snapshot-safety.cjs")).readText()
        val process = ProcessBuilder("node", "-e", runner).redirectErrorStream(true).start()
        try {
            process.outputStream.bufferedWriter().use { it.write(scripts.toString()) }
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "JavaScript regression suite timed out")
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), output)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
