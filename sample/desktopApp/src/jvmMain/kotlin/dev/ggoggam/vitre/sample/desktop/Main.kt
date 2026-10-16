package dev.ggoggam.vitre.sample.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.ggoggam.vitre.core.webview.CefRuntime
import dev.ggoggam.vitre.sample.App
import dev.ggoggam.vitre.sample.ui.VitreTheme
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The desktop sample: the same gallery the Android and iOS samples show, in a window.
 *
 * Chromium is started before the window opens. It ships inside the JetBrains Runtime this app runs
 * on, so there is nothing to download and starting it takes well under a second — not long enough
 * to be worth a screen of its own. Starting it first is what lets `VitreWebView` and
 * `VitreFrameHost` ask for a browser the moment they mount: asking before `CefRuntime.init` has
 * been called fails outright rather than queueing.
 *
 * What does deserve a screen is it failing, which in practice means the app was launched on a JDK
 * without JCEF — from an IDE run configuration pointing at the wrong JDK, say, rather than through
 * Gradle. A window that says so beats a stack trace in a terminal nobody is looking at.
 */
fun main() {
    val started =
        runCatching {
            runBlocking {
                // Chromium's profile — cookies, storage, the HTTP cache — beside the user's other
                // caches, so a lane's storage survives a restart the way a browser profile would.
                CefRuntime.init(cachePath = File(System.getProperty("user.home"), ".vitre/cache"))
            }
        }

    application {
        // Releases the CEF processes. Without it the renderers outlive the window and the JVM does
        // not exit.
        DisposableEffect(Unit) {
            onDispose { CefRuntime.dispose() }
        }

        Window(
            onCloseRequest = ::exitApplication,
            title = "Vitre",
            state = rememberWindowState(width = 1280.dp, height = 860.dp),
        ) {
            started.fold(
                onSuccess = { App() },
                onFailure = { failure -> StartupFailed(failure.message ?: failure.toString()) },
            )
        }
    }
}

@Composable
private fun StartupFailed(reason: String) {
    VitreTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(48.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Vitre", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Chromium could not be started",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 520.dp),
                )
            }
        }
    }
}
