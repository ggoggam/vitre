package dev.ggoggam.vitre.core.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsyncScriptTest {
    private val script = AsyncScript(nonce = "abc")

    @Test
    fun `wrapped script returns the pending sentinel it will be compared against`() {
        // The two have to agree exactly: the wrapper emits this literal and ScriptResults decides
        // whether to wait by comparing the platform evaluate's answer against the same text.
        assertTrue(script.wrap("fetch('/x')", cid = 7).contains("return ${script.pendingResult(7)};"))
        assertEquals("\"__wv_pending:abc:7\"", script.pendingResult(7))
    }

    @Test
    fun `wrapped script keeps the caller's expression as an expression`() {
        val wrapped = script.wrap("document.title", cid = 1)
        assertTrue(wrapped.contains("var v = (document.title);"))
        assertTrue(wrapped.startsWith("(function () {"))
        assertTrue(wrapped.endsWith("})()"))
    }

    @Test
    fun `wrapped script reports under this controller's nonce`() {
        assertTrue(script.wrap("fetch('/x')", cid = 3).contains("nonce: 'abc'"))
    }

    @Test
    fun `a message of another type is not a settled report`() {
        assertNull(script.parse("""{"id":"x","type":"lane:ready","payload":{"cid":4}}"""))
    }

    @Test
    fun `a page message that merely mentions the type string still belongs to the page`() {
        assertNull(script.parse("the page said script:result in passing"))
    }

    @Test
    fun `a settled report carries its correlation fields`() {
        val settled = script.parse(report(cid = 4, nonce = "abc", ok = true, value = "{\\\"a\\\":1}"))!!
        assertEquals(4L, settled.cid)
        assertEquals("abc", settled.nonce)
        assertEquals("""{"a":1}""", settled.valueOrThrow())
    }

    @Test
    fun `a rejected promise carries the page's own message`() {
        val settled =
            script.parse(
                """{"id":"x","type":"script:result","payload":{"cid":1,"nonce":"abc","ok":false,"error":"TypeError: nope"}}""",
            )!!
        val failure = assertFailsWith<ScriptFailedException> { settled.valueOrThrow() }
        assertEquals("TypeError: nope", failure.message)
    }

    @Test
    fun `an unreadable payload is claimed but can never be credited`() {
        // Non-null: the message named the settle plane's type, so it must not leak to the inbox.
        // Null nonce: no forged or mangled report can match a real controller's nonce.
        val settled = script.parse("""{"id":"x","type":"script:result","payload":"not an object"}""")!!
        assertNull(settled.nonce)
        assertNull(settled.cid)
        assertFailsWith<ScriptFailedException> { settled.valueOrThrow() }
    }

    private fun report(
        cid: Long,
        nonce: String,
        ok: Boolean,
        value: String,
    ): String =
        """{"id":"script:result#$cid","type":"script:result","payload":{"cid":$cid,"nonce":"$nonce","ok":$ok,"value":"$value","error":null}}"""
}
