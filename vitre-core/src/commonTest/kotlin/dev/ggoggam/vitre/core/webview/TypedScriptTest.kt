package dev.ggoggam.vitre.core.webview

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `evaluate` is `evaluateJs` plus a decode, so what these assert is that it submits the same script
 * and reads the JSON encoding the platforms already agreed to produce — including the cases a
 * hand-written `== "true"` gets wrong.
 */
class TypedScriptTest {
    @Serializable
    data class Product(
        val sku: String,
        val price: Double,
    )

    @Test
    fun decodes_a_boolean_rather_than_comparing_strings() =
        runTest {
            val controller = FakeWebViewController()
            controller.nextEvalResult = { "true" }

            assertTrue(controller.evaluate<Boolean>("document.querySelector('#buy')!==null"))
            assertEquals(
                listOf("document.querySelector('#buy')!==null"),
                controller.evaluatedScripts,
                "the script reaches the WebView unchanged — the decode happens on the way back",
            )
        }

    /**
     * The reason the typed read exists. A JS `1` is truthy and its JSON encoding is not `"true"`, so
     * the string comparison this replaces read it as false; here it is a decode failure, which names
     * the problem instead of silently reporting the element as absent.
     */
    @Test
    fun refuses_a_result_that_is_not_the_requested_type() =
        runTest {
            val controller = FakeWebViewController()
            controller.nextEvalResult = { "1" }

            assertFailsWith<SerializationException> { controller.evaluate<Boolean>("el!==null") }
        }

    @Test
    fun decodes_a_class_from_what_the_page_returned() =
        runTest {
            val controller = FakeWebViewController()
            controller.nextEvalResult = { """{"sku":"kb-1","price":39.99,"colour":"black"}""" }

            assertEquals(
                Product(sku = "kb-1", price = 39.99),
                controller.evaluate<Product>("readProduct()"),
                "an unknown key is ignored, so a page that adds a field does not break the call",
            )
        }

    @Test
    fun decodes_a_list() =
        runTest {
            val controller = FakeWebViewController()
            controller.nextEvalResult = { """[{"sku":"a","price":1.0},{"sku":"b","price":2.0}]""" }

            assertEquals(2, controller.evaluate<List<Product>>("rows()").size)
        }

    /**
     * `null` is what every generated expression produces for an element that is not there, so which
     * of the two this is has to be the caller's choice rather than the library's.
     */
    @Test
    fun null_decodes_into_a_nullable_and_fails_a_non_nullable() =
        runTest {
            val controller = FakeWebViewController()
            controller.nextEvalResult = { "null" }

            assertNull(controller.evaluate<String?>("document.querySelector('#gone')?.textContent"))
            assertFailsWith<SerializationException> {
                controller.evaluate<String>("document.querySelector('#gone')?.textContent")
            }
        }

    /** A script failure is the page's, and stays that rather than becoming a decode failure. */
    @Test
    fun a_failing_script_still_reports_its_own_error() =
        runTest {
            val controller = FakeWebViewController()
            controller.nextEvalResult = { throw ScriptFailedException("boom") }

            assertFailsWith<ScriptFailedException> { controller.evaluate<Boolean>("nope()") }
        }
}
