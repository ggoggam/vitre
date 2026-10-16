package dev.ggoggam.vitre.core.webview

import com.jetbrains.cef.JCefAppConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.cef.CefApp
import org.cef.CefClient
import java.io.File

/**
 * Chromium for the desktop target, as the JetBrains Runtime ships it.
 *
 * The desktop host runs on a **JetBrains Runtime with JCEF** (`jbr_jcef`): a JDK whose `jcef` module
 * carries the Java bindings, the Chromium framework and its helper processes. Nothing is downloaded
 * or unpacked — Chromium is part of the runtime the app was installed with, the way a WebView is
 * part of the phone — so starting it is a local, sub-second step rather than a first-run event the
 * host has to build a progress screen for.
 *
 * Call [init] once, before anything asks for a browser: `CefWebViewPool.create`, the desktop
 * `VitreWebView` and `VitreFrameHost` all get their clients from [newClient], which waits for an
 * [init] already under way but fails outright if none was ever started. Call [dispose] when the
 * app is done with Chromium, or its helper processes outlive the window and the JVM does not exit.
 *
 * ### A host that starts CEF itself
 *
 * If `CefApp` already exists when [init] runs — an app that configured CEF its own way — it is
 * adopted as it is rather than started a second time, which CEF does not allow. [init] still has to
 * be called, so that [newClient] knows CEF is ready rather than guessing.
 */
object CefRuntime {
    private val state = MutableStateFlow<State>(State.New)
    private val starting = Mutex()

    /**
     * True when this JVM carries JCEF — that is, when it is a JetBrains Runtime with JCEF rather than
     * a plain JDK. Checked by [init]; exposed so a host can say something more useful than a stack
     * trace when it was launched on the wrong runtime.
     */
    val isAvailable: Boolean
        get() = ModuleLayer.boot().findModule(JCEF_MODULE).isPresent

    /**
     * Starts Chromium, or adopts the `CefApp` a host already started. Idempotent: a second call after
     * a successful one returns at once.
     *
     * [cachePath] is Chromium's on-disk profile — cookies, local storage, the HTTP cache. Give it a
     * path so a lane's storage survives a restart the way a browser profile would; left null, CEF
     * runs incognito and every run starts from nothing.
     *
     * @throws IllegalStateException if this JVM has no JCEF (see [isAvailable]), if CEF would not
     *   start, or after [dispose].
     */
    suspend fun init(cachePath: File? = null) {
        starting.withLock {
            when (state.value) {
                is State.Ready -> return

                State.Disposed -> throw IllegalStateException("CefRuntime was disposed; CEF cannot be started twice in one process")

                // A failed start may be retried; Starting cannot be seen here, under the lock.
                else -> Unit
            }
            state.value = State.Starting
            // Off the caller's thread: startup loads Chromium's native libraries, and a caller on the
            // EDT would be a window that stops painting for the length of it.
            val result = runCatching { withContext(Dispatchers.IO) { start(cachePath) } }
            state.value = result.fold(onSuccess = { State.Ready(it) }, onFailure = { State.Failed(it) })
            result.getOrThrow()
        }
    }

    /**
     * A new client — one per browser, because a `CefClient` holds exactly one load handler and one
     * request handler, and [CefWebViewController] claims both.
     *
     * Waits for an [init] in progress. Throws, rather than waiting, when [init] has never been called:
     * a caller that mounted a lane before starting Chromium has a bug that a wait would only hide.
     */
    suspend fun newClient(): CefClient {
        val ready = state.first { it != State.Starting }
        return when (ready) {
            is State.Ready -> ready.app.createClient()
            State.New -> throw IllegalStateException("CefRuntime.init has not been called; start Chromium before creating a browser")
            is State.Failed -> throw IllegalStateException("Chromium failed to start", ready.cause)
            State.Disposed -> throw IllegalStateException("CefRuntime was disposed")
            State.Starting -> error("unreachable")
        }
    }

    /**
     * Shuts Chromium down. Dispose every browser and client first — `CefWebViewPool.dispose` does
     * that for a pool. Safe to call when [init] never ran or failed.
     */
    fun dispose() {
        val current = state.value
        state.value = State.Disposed
        if (current is State.Ready) runCatching { current.app.dispose() }
    }

    private suspend fun start(cachePath: File?): CefApp {
        check(isAvailable) {
            "This JVM has no JCEF. Vitre's desktop target runs on the JetBrains Runtime with JCEF " +
                "(jbr_jcef); java.home is ${System.getProperty("java.home")}"
        }
        CefApp.getInstanceIfAny()?.let { return it }

        // The runtime's own arguments and settings, which carry the paths to the Chromium framework
        // and helper processes inside the JDK. Everything below only adds to them.
        val config = JCefAppConfig.getInstance()
        val args = config.appArgs
        val settings =
            config.cefSettings.apply {
                // Every lane renders offscreen — see CefSurface — and CEF refuses an offscreen
                // browser in an app that did not ask for windowless rendering at startup.
                windowless_rendering_enabled = true
                if (cachePath != null) cache_path = cachePath.absolutePath
            }
        check(CefApp.startup(args)) { "CefApp.startup failed" }
        val app = CefApp.getInstance(args, settings)

        // Clients are only usable once the browser process has its context; waiting here is what
        // lets newClient promise a usable one. Bounded, because a CEF that never reports in should
        // surface as a failure to start rather than as a lane that hangs on its first navigate.
        val initialized = CompletableDeferred<Unit>()
        app.onInitialization { appState -> if (appState == CefApp.CefAppState.INITIALIZED) initialized.complete(Unit) }
        // And checked after registering, in case it got there first.
        if (CefApp.getState() == CefApp.CefAppState.INITIALIZED) initialized.complete(Unit)
        withTimeoutOrNull(STARTUP_TIMEOUT_MS) { initialized.await() }
            ?: throw IllegalStateException("Chromium did not finish initialising within ${STARTUP_TIMEOUT_MS}ms")
        return app
    }

    private sealed interface State {
        data object New : State

        data object Starting : State

        class Ready(
            val app: CefApp,
        ) : State

        class Failed(
            val cause: Throwable,
        ) : State

        data object Disposed : State
    }

    private const val JCEF_MODULE = "jcef"
    private const val STARTUP_TIMEOUT_MS = 30_000L
}
