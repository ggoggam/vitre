package dev.ggoggam.vitre.core.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserAgentTest {
    /** The exact agent read off a device, so the case that matters is the one under test. */
    private val realWebViewAgent =
        "Mozilla/5.0 (Linux; Android 16; SM-S942N Build/BP4A.251205.006; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/151.0.7922.170 Mobile Safari/537.36"

    @Test
    fun `removes the wv token from a real WebView agent`() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 16; SM-S942N Build/BP4A.251205.006) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/151.0.7922.170 Mobile Safari/537.36",
            withoutWebViewToken(realWebViewAgent),
        )
    }

    @Test
    fun `leaves everything after the platform parenthetical alone`() {
        val stripped = withoutWebViewToken(realWebViewAgent)
        // Version/4.0 is the other WebView tell and is deliberately kept — see withoutWebViewToken.
        assertTrue("Version/4.0" in stripped)
        assertTrue("Chrome/151.0.7922.170 Mobile Safari/537.36" in stripped)
    }

    @Test
    fun `is a no-op on an agent that never had the token`() {
        val chrome =
            "Mozilla/5.0 (Linux; Android 16; SM-S942N) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/151.0.7922.170 Mobile Safari/537.36"
        assertEquals(chrome, withoutWebViewToken(chrome))
    }

    @Test
    fun `is idempotent`() {
        val once = withoutWebViewToken(realWebViewAgent)
        assertEquals(once, withoutWebViewToken(once))
    }

    /**
     * The reason the pattern is anchored on the closing paren. `wv` is two very common letters, and
     * a loose match would corrupt a build id or a model name that happens to contain them.
     */
    @Test
    fun `does not touch wv appearing anywhere but the token position`() {
        val agent =
            "Mozilla/5.0 (Linux; Android 16; wv-2000 Build/WV12; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36 wv"
        val stripped = withoutWebViewToken(agent)
        assertTrue("wv-2000 Build/WV12" in stripped, stripped)
        assertTrue(stripped.endsWith("Mobile Safari/537.36 wv"), stripped)
        assertTrue("; wv)" !in stripped, stripped)
    }

    @Test
    fun `tolerates spacing variants`() {
        assertEquals("(Linux; Android 16)", withoutWebViewToken("(Linux; Android 16;wv)"))
        assertEquals("(Linux; Android 16)", withoutWebViewToken("(Linux; Android 16;  wv )"))
    }

    @Test
    fun `a browser identity on Android is Chrome's reduced agent`() {
        val chrome =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36"
        assertEquals(chrome, chromeOnAndroid(realWebViewAgent))
        // Whether or not the wv has already gone, which is the order applyVitreWebSettings applies them in.
        assertEquals(chrome, chromeOnAndroid(withoutWebViewToken(realWebViewAgent)))
    }

    @Test
    fun `an agent that is not Android WebView's is not replaced by a guess`() {
        assertEquals("MyApp/1.0", chromeOnAndroid("MyApp/1.0"))
        val desktop =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
        assertEquals(desktop, chromeOnAndroid(desktop))
    }

    @Test
    fun `a browser identity on iOS ends the agent as Safari's`() {
        assertEquals("Version/27.2 Mobile/15E148 Safari/604.1", safariApplicationName("27.2"))
        assertEquals("Version/26.0 Mobile/15E148 Safari/604.1", safariApplicationName("26"))
        assertEquals("Version/26.1 Mobile/15E148 Safari/604.1", safariApplicationName("26.1.2"))
        assertEquals("Version/18.0 Mobile/15E148 Safari/604.1", safariApplicationName(""))
    }
}
