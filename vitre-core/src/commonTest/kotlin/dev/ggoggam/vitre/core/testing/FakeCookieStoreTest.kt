package dev.ggoggam.vitre.core.testing

import dev.ggoggam.vitre.core.webview.Cookie
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The fake's scoping, tested for the same reason [FakeWebViewController]'s ordering is the
 * production one: a double that is laxer than the contract is where a bug in the code under test
 * hides. Everything below is a rule `CookieStore` states, checked on the double the layers above
 * will be written against.
 */
class FakeCookieStoreTest {
    private val store = FakeCookieStore()

    @Test
    fun `a cookie is only sent to the host it was set on`() =
        runTest {
            store.write("https://shop.example/cart", Cookie(name = "session", value = "abc"))

            assertEquals(listOf("session"), store.read("https://shop.example/cart").map { it.name })
            assertEquals(emptyList(), store.read("https://other.example/cart").map { it.name })
        }

    /**
     * The difference a real jar records with a leading dot. Getting this wrong in a double is worse
     * than getting it wrong in one platform, because it is then the contract every test believes.
     */
    @Test
    fun `a host-only cookie stays off subdomains and a domain cookie reaches them`() =
        runTest {
            store.write("https://example.com/", Cookie(name = "host-only", value = "1"))
            store.write("https://example.com/", Cookie(name = "shared", value = "2", domain = "example.com"))

            assertEquals(listOf("host-only", "shared"), store.read("https://example.com/").map { it.name })
            assertEquals(listOf("shared"), store.read("https://cdn.example.com/").map { it.name })
        }

    /** And therefore: resetting a subdomain must not log the parent site out. */
    @Test
    fun `clearing a subdomain leaves the parent host's own cookie alone`() =
        runTest {
            store.write("https://example.com/", Cookie(name = "session", value = "abc"))

            store.clear("https://cdn.example.com/")

            assertEquals(listOf("session"), store.read("https://example.com/").map { it.name })
        }

    @Test
    fun `a path-scoped cookie is left out of a request below that path`() =
        runTest {
            store.write("https://shop.example/", Cookie(name = "checkout", value = "1", path = "/checkout"))

            assertEquals(listOf("checkout"), store.read("https://shop.example/checkout/pay").map { it.name })
            assertEquals(emptyList(), store.read("https://shop.example/browse").map { it.name })
        }

    /** Null and `/` are the same scope, so they have to be the same cookie. */
    @Test
    fun `writing the same name again replaces it whether or not the path was spelled out`() =
        runTest {
            store.write("https://shop.example/", Cookie(name = "session", value = "first"))
            store.write("https://shop.example/", Cookie(name = "session", value = "second", path = "/"))

            assertEquals(listOf("second"), store.read("https://shop.example/").map { it.value })
        }

    @Test
    fun `a secure cookie is not sent over http`() =
        runTest {
            store.write("https://shop.example/", Cookie(name = "session", value = "abc", secure = true))

            assertEquals(emptyList(), store.read("http://shop.example/").map { it.name })
            assertEquals(listOf("session"), store.read("https://shop.example/").map { it.name })
        }

    @Test
    fun `a cookie that has expired is no longer sent`() =
        runTest {
            store.write("https://shop.example/", Cookie(name = "session", value = "abc", expiresAtMs = 1_000))

            store.nowMs = 999
            assertEquals(listOf("session"), store.read("https://shop.example/").map { it.name })
            store.nowMs = 1_001
            assertEquals(emptyList(), store.read("https://shop.example/").map { it.name })
        }

    @Test
    fun `clear removes exactly what read would have returned`() =
        runTest {
            store.write("https://shop.example/", Cookie(name = "session", value = "abc"))
            store.write("https://other.example/", Cookie(name = "session", value = "xyz"))

            store.clear("https://shop.example/")

            assertEquals(emptyList(), store.read("https://shop.example/").map { it.name })
            assertEquals(listOf("session"), store.read("https://other.example/").map { it.name })
        }

    /** The double enforces the same refusals production does, or it is not standing in for it. */
    @Test
    fun `a domain the site does not own is refused`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                store.write("https://shop.example/", Cookie(name = "session", value = "abc", domain = "evil.example"))
            }
        }
}
