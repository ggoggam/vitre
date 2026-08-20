plugins {
    // Multiplatform with a single target rather than the plain `kotlin("jvm")` plugin: the Kotlin
    // plugin is already on this build's classpath from the library modules, and asking for the JVM
    // variant by version there fails to resolve rather than reusing it.
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":sample:composeApp"))
            implementation(compose.desktop.currentOs)
            // The startup screen is Material 3, and :sample:composeApp keeps its own copy
            // `implementation`-scoped, so it does not arrive from there.
            implementation(libs.compose.material3)
            implementation(libs.kotlinx.coroutines.core)
            // KCEF's own types are used directly here: the app owns `KCEF.init`, because
            // downloading and unpacking Chromium is a visible, once-per-machine event that belongs
            // to the app's startup UI rather than to a library that would have to guess where to
            // put a progress bar.
            implementation(libs.kcef)
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.ggoggam.vitre.sample.desktop.MainKt"
    }
}

// JCEF reaches into AWT's internals, and every package below is one the JDK closed off in 9 and has
// kept closed since. Without them CEF fails during startup — on macOS as a SIGSEGV inside its own
// JNI class lookup, which names nothing and points nowhere — so these are not optional and they are
// not tuning. This is the set compose-webview-multiplatform runs with.
//
// On the task rather than in `application { jvmArgs }` so it covers every way the module is
// launched, including running the main function straight from an IDE.
afterEvaluate {
    tasks.withType<JavaExec>().configureEach {
        jvmArgs("--add-opens", "java.desktop/sun.awt=ALL-UNNAMED")
        jvmArgs("--add-opens", "java.desktop/java.awt.peer=ALL-UNNAMED")
        if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
            // JCEF's window handling goes through the LWAWT peer, which is where a Mac keeps the
            // native window a CEF browser attaches to.
            jvmArgs("--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED")
            jvmArgs("--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
        }
    }
}
