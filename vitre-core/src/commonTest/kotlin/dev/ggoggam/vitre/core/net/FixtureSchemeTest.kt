package dev.ggoggam.vitre.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The URL mapping that lets one workflow drive a fixture on both platforms.
 *
 * Worth testing without a WebView because every failure here is invisible until it is a blank lane:
 * an unmapped URL leaves iOS looking for a hostname that does not resolve, and a URL that fails to
 * map *back* reaches a [RequestHandler] in a form it does not recognise and is declined.
 */
class FixtureSchemeTest {
    @Test
    fun `moves an https url onto the private scheme and back`() {
        val original = "https://alpha-hardware.test/search?q=mechanical%20keyboard"
        val encoded = FixtureScheme.encode(original)

        assertEquals("${FixtureScheme.SCHEME}://alpha-hardware.test/search?q=mechanical%20keyboard", encoded)
        assertEquals(original, FixtureScheme.decode(encoded))
    }

    @Test
    fun `keeps host and path intact so relative urls resolve back into the scheme`() {
        // The form on the Keyclack fixture submits to `/catalog`. If the host were encoded into the
        // path — or dropped — that relative action would resolve against the wrong origin and the
        // shop's own search would leave the scheme handler's world.
        val encoded = FixtureScheme.encode("https://keyclack.test/catalog?term=switch")

        assertTrue(encoded.startsWith("${FixtureScheme.SCHEME}://keyclack.test/"))
        assertEquals("keyclack.test", hostOf(encoded))
    }

    @Test
    fun `leaves a real site alone`() {
        // The whole point of the iOS pool: github.com is loaded top-level over real https, and
        // nothing about it should be routed through a handler that could not answer for it anyway.
        val live = "https://github.com/"
        assertFalse(FixtureScheme.isFixtureUrl(live))
        assertEquals(live, FixtureScheme.decode(live))
    }

    @Test
    fun `declines to encode a scheme it cannot map back`() {
        // http and https would both decode to https, so an http URL that round-tripped would come
        // back as a different request than the one the page made.
        assertEquals("http://plain.test/a", FixtureScheme.encode("http://plain.test/a"))
        assertEquals("about:blank", FixtureScheme.encode("about:blank"))
    }

    @Test
    fun `each fixture host keeps its own origin`() {
        // Distinct origins are what make the sample's four shops genuinely cross-origin on iOS, the
        // same as they are on Android. One shared origin would make the demo prove nothing.
        val shop = FixtureScheme.encode("https://nordicparts.test/find")
        val api = FixtureScheme.encode("https://api.nordicparts.test/items")

        assertEquals("${FixtureScheme.SCHEME}://nordicparts.test", originOf(shop))
        assertEquals("${FixtureScheme.SCHEME}://api.nordicparts.test", originOf(api))
    }
}
