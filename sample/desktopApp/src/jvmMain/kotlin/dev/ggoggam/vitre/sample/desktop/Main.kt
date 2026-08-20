package dev.ggoggam.vitre.sample.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.datlag.kcef.KCEF
import dev.ggoggam.vitre.sample.App
import dev.ggoggam.vitre.sample.ui.VitreTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The desktop sample: the same gallery the Android and iOS samples show, in a window.
 *
 * Everything before that window is useful is this file's only real job. Unlike a WebView, an
 * embedded Chromium is not already on the machine — KCEF downloads and unpacks a bundle of a few
 * hundred megabytes the first time the app runs, and initialises it once per process after that. A
 * host has to wait for both, and this is what waiting looks like: a progress screen, then the app.
 *
 * The gate is not decoration. `VitreWebView` and `VitreFrameHost` both ask KCEF for a
 * client as they mount, and asking before `KCEF.init` has finished fails outright rather than
 * queueing — so mounting the gallery early would produce a window full of lanes that report
 * themselves unavailable for reasons that have nothing to do with the library.
 */
fun main() =
    application {
        var startup by remember { mutableStateOf<Startup>(Startup.Preparing) }

        LaunchedEffect(Unit) {
            // Beside the user's other caches rather than in the working directory, which is
            // KCEF's default and means the bundle is re-downloaded for every directory the app is
            // ever launched from.
            val bundleRoot = File(System.getProperty("user.home"), ".vitre")
            // Off the EDT: the first run of this does hundreds of megabytes of download and a tar
            // extraction, and on the UI thread that is a window that does not paint.
            withContext(Dispatchers.IO) {
                KCEF.init(
                    builder = {
                        installDir(File(bundleRoot, "kcef-bundle"))
                        // Pinned rather than "latest", and the reasons are worth reading before
                        // bumping it — see JBR_RELEASE.
                        download { github { release(JBR_RELEASE) } }
                        // Chromium's own on-disk cache. Given a path so a lane's storage survives
                        // a restart the way a browser profile would; left unset, CEF runs
                        // incognito and every run re-fetches everything.
                        settings { cachePath = File(bundleRoot, "cache").absolutePath }
                        progress {
                            onDownloading { percent -> startup = Startup.Downloading(percent / 100f) }
                            onExtracting { startup = Startup.Unpacking }
                            onInitializing { startup = Startup.Unpacking }
                        }
                    },
                    onError = { failure ->
                        startup = Startup.Failed(failure?.message ?: failure?.toString() ?: "Chromium could not be initialised")
                    },
                    // Some platforms cannot finish initialising in the same process that installed
                    // the bundle. Saying so is the only useful thing to do about it.
                    onRestartRequired = { startup = Startup.Failed("Chromium was installed — restart the app to use it.") },
                )
            }
            if (startup !is Startup.Failed) startup = Startup.Ready
        }

        // Releases the CEF processes. Without it the renderers outlive the window and the JVM does
        // not exit.
        DisposableEffect(Unit) {
            onDispose { runCatching { KCEF.disposeBlocking() } }
        }

        Window(
            onCloseRequest = ::exitApplication,
            title = "Vitre",
            state = rememberWindowState(width = 1280.dp, height = 860.dp),
        ) {
            when (val current = startup) {
                Startup.Ready -> App()
                else -> StartupScreen(current)
            }
        }
    }

/**
 * The JetBrains Runtime release the native Chromium bundle is taken from.
 *
 * **Pin it, and pin it to something KCEF's vintage can unpack.** The default is whatever JetBrains
 * published most recently, which means the app can break on a day nothing in this repo changed —
 * and does: current runtime releases moved `Chromium Embedded Framework.framework` inside
 * `cef_server.app`, where KCEF does not look for it. Every way of getting this wrong fails the
 * same way, as a SIGSEGV at `pc=0` inside JCEF's own `FindClass` before a window ever appears,
 * which says nothing at all about the cause.
 *
 * This is the release compose-webview-multiplatform pins alongside the same KCEF version. It is
 * from the JDK 17 line, and that is not a mistake to "fix": the bundle supplies Chromium and its
 * helper processes, not the JVM this app runs on, so its Java version has nothing to do with the
 * toolchain. Treat the KCEF version in `libs.versions.toml` and this tag as one setting in two
 * files.
 */
private const val JBR_RELEASE = "jbr-release-17.0.12b1207.37"

/** What the window shows before the gallery can be shown at all. */
private sealed interface Startup {
    data object Preparing : Startup

    /** [fraction] is 0..1; KCEF reports the download as a percentage. */
    data class Downloading(
        val fraction: Float,
    ) : Startup

    data object Unpacking : Startup

    data object Ready : Startup

    data class Failed(
        val reason: String,
    ) : Startup
}

@Composable
private fun StartupScreen(startup: Startup) {
    VitreTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(48.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Vitre", style = MaterialTheme.typography.headlineSmall)
                when (startup) {
                    is Startup.Downloading -> {
                        Text(
                            "Downloading Chromium — ${(startup.fraction * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(
                            progress = { startup.fraction },
                            modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
                        )
                    }

                    is Startup.Failed -> {
                        Text(
                            startup.reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 420.dp),
                        )
                    }

                    else -> {
                        Text(
                            if (startup is Startup.Unpacking) "Unpacking Chromium…" else "Starting Chromium…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CircularProgressIndicator()
                    }
                }
                // Said once, on the screen where the wait actually happens. The bundle is a
                // one-off: every later launch goes straight to the gallery.
                if (startup !is Startup.Failed) {
                    Text(
                        "This happens once per machine.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
