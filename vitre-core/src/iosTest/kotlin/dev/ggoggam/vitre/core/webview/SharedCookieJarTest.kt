package dev.ggoggam.vitre.core.webview

import dev.ggoggam.vitre.core.frame.IosWebViewPool
import dev.ggoggam.vitre.core.testing.LaneWindow
import dev.ggoggam.vitre.core.testing.runMainLoopTest
import kotlin.test.AfterTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether two lanes of one [IosWebViewPool] really share a session.
 *
 * The claim is made in three places — [IosWebViewPool.newWebView] hands every lane
 * `WKWebsiteDataStore.defaultDataStore()`, [CookieStore] documents the jar as belonging to the
 * store rather than the WebView, and `docs/PARALLEL-LANES.md` leans on it — and until now it was
 * asserted nowhere. That was tolerable while a workflow owned its lane for its whole life. It stops
 * being tolerable the moment a step can fan out across lanes, because *then* "lane A logs in, lanes
 * B–D read the account" is the ordinary case rather than an occasional convenience.
 *
 * Two different claims are under test here and they are not the same strength:
 *
 *  - A cookie written through [CookieStore.write] goes into the jar WebKit's networking process
 *    owns, and the write's completion handler is a real barrier. This one should simply hold.
 *  - A cookie written by page script lands in a *content* process first and reaches the networking
 *    process on WebKit's own schedule. Nothing documents that as synchronous, so
 *    [documentCookieOnOneLaneReachesAnother] is the one whose answer decides whether a fan-out has
 *    to flush the jar before it dispatches children.
 *
 * ### Why this is @Ignore'd
 *
 * **WebKit does not work in a Kotlin/Native test binary, at all**, and it fails silently rather
 * than loudly. `iosSimulatorArm64Test` runs the tests as a bare Mach-O executable rather than as an
 * application, and WebKit's work happens in XPC services — a WebContent process for the page, a
 * Networking process for the jar — that the simulator will only launch on behalf of a real host
 * app. With no host, every WebKit call is accepted and simply never calls back:
 *
 *  - `loadHTMLString` produces neither `didFinishNavigation` nor `didFailNavigation`, so
 *    `pool.open()` ends in a 30s `PageLoadException`.
 *  - `evaluateJavaScript`'s completion handler never runs.
 *  - `WKHTTPCookieStore.setCookie` and `getAllCookies` never run theirs either — so not even the
 *    two jar-only tests here can be salvaged by skipping the page load.
 *
 * Mounting the lanes ([LaneWindow]) is necessary but nowhere near sufficient; it was measured, and
 * a raw `WKWebView` with a raw delegate behaves identically mounted and unmounted.
 *
 * **To enable this, the module needs an XCTest bundle with a host application** — an iOS app target
 * whose `Test Host` is set, linking the shared framework. Nothing in the test bodies below changes
 * when that exists; only where they are compiled and run does.
 */
@Ignore
class SharedCookieJarTest {
    private val url = "https://cookies.test/"

    private var pool: IosWebViewPool? = null
    private var window: LaneWindow? = null

    /**
     * Lanes for one test, already blanked.
     *
     * Two rather than [dev.ggoggam.vitre.core.frame.Lanes.MAX_LANES]: sharing is a property of the
     * store, so a third lane would prove nothing a second does not and would cost another content
     * process on a simulator that is already running one per lane.
     */
    private suspend fun lanes(): Pair<WebViewController, WebViewController> {
        val created = IosWebViewPool(laneCount = 2)
        pool = created
        window = LaneWindow().apply { mount(created.webViews) }
        created.open()
        return created.pool.lane("a") to created.pool.lane("b")
    }

    /**
     * The default data store is *persistent* and belongs to the process, so a cookie left behind
     * outlives the test that wrote it and is visible to every test after it. Clearing through lane
     * a is enough — there is only one jar, which is the thing being tested.
     */
    @AfterTest
    fun tearDown() {
        val created = pool ?: return
        pool = null
        val mounted = window
        window = null
        runMainLoopTest {
            runCatching {
                created.pool
                    .lane("a")
                    .jar
                    .clear(url)
            }
            created.pool.allLanes.forEach { runCatching { it.close() } }
            mounted?.dismiss()
        }
    }

    @Test
    fun writeOnOneLaneIsReadableFromAnother() =
        runMainLoopTest {
            val (a, b) = lanes()

            a.jar.write(url, Cookie(name = "session", value = "abc123", domain = "cookies.test", path = "/"))

            val onB = b.jar.read(url).firstOrNull { it.name == "session" }
            assertNotNull(onB, "lane b should see the cookie lane a wrote — both lanes share defaultDataStore()")
            assertEquals("abc123", onB.value)
        }

    /**
     * The fan-out case, and the one worth having.
     *
     * A login flow that ends in page script rather than in a `Set-Cookie` header is common enough
     * that a fan-out cannot assume otherwise, and the failure mode if this does not hold is the
     * worst kind: load-dependent, so some children of a fan-out come back logged out and the rest
     * do not.
     *
     * The read goes through lane b's *jar* rather than through its `document.cookie`, because a
     * document on lane b would have to be loaded for the same origin to see the cookie at all, and
     * that would be testing the same-origin rule rather than the store.
     */
    @Test
    fun documentCookieOnOneLaneReachesAnother() =
        runMainLoopTest {
            val (a, b) = lanes()

            // A real origin, not `about:blank`: `document.cookie` is inert on an opaque origin, so
            // the assertion below would fail for a reason that has nothing to do with sharing.
            a.loadHtml("<!doctype html><meta charset=utf-8><title>a</title>", baseUrl = url)
            val written = a.evaluateJs("(function(){document.cookie='pagecookie=xyz789; path=/';return document.cookie;})()")
            assertTrue(written.contains("pagecookie"), "the page did not manage to set a cookie at all: $written")

            val onB = b.jar.read(url).firstOrNull { it.name == "pagecookie" }
            assertNotNull(
                onB,
                "lane b did not see a document.cookie written on lane a. If this is the only failure, " +
                    "the shared store is fine and the content-process write simply had not propagated — " +
                    "which is exactly the race a fan-out barrier has to flush.",
            )
            assertEquals("xyz789", onB.value)
        }

    /**
     * The other half of sharing, and the half a test suite usually forgets: if the jar is shared
     * then clearing it on one lane must clear it everywhere, or a workflow that logs out leaves
     * three lanes still authenticated.
     */
    @Test
    fun clearingOnOneLaneClearsTheOther() =
        runMainLoopTest {
            val (a, b) = lanes()

            a.jar.write(url, Cookie(name = "session", value = "abc123", domain = "cookies.test", path = "/"))
            assertNotNull(b.jar.read(url).firstOrNull { it.name == "session" }, "precondition: lane b sees it")

            b.jar.clear(url)

            assertNull(
                a.jar.read(url).firstOrNull { it.name == "session" },
                "lane a still sees a cookie lane b cleared — the jars are not shared after all",
            )
        }
}

/**
 * The lane's cookie jar, asserted present once rather than `!!`-ed at every call site.
 *
 * [WebViewController.cookies] is nullable because the desktop controller has no single jar to
 * offer — CEF keeps two — and iOS always does. A null here would mean the iOS controller had
 * stopped wiring one up, which is worth a sentence rather than a `NullPointerException`.
 */
private val WebViewController.jar: CookieStore
    get() = assertNotNull(cookies, "an iOS lane should always have a cookie jar")
