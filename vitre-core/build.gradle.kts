plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

group = "dev.ggoggam.vitre"

kotlin {
    androidLibrary {
        namespace = "dev.ggoggam.vitre.core"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        // Without this, `allTests` runs commonTest on iOS only and the JVM half of a multiplatform
        // library ships untested — which for the concurrency code is the half most likely to
        // differ, since the two runtimes schedule coroutines differently.
        withHostTest {}
    }

    // The desktop target. `jvm()` rather than a `desktop()` alias so the source set is named
    // `jvmMain`, which is what every Compose Multiplatform and KCEF sample assumes.
    jvm()

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // Android and desktop are both the JVM, and both intercept by refetching the resource
    // themselves — the same redirect-following, header-sanitising, cookie-carrying code. It gets a
    // source set of its own so there is one copy of it rather than two that drift; only the
    // platform's request/response types and its cookie jar differ, and those stay in the leaves.
    //
    // Declared as a *template extension* rather than by wiring `dependsOn` by hand. Doing it by
    // hand switches the default hierarchy off for the whole project, and the way that surfaces is
    // memorable: `iosMain` silently stops being a source set, and every iOS `actual` in the module
    // is reported missing by a compiler that can no longer see the files.
    applyDefaultHierarchyTemplate {
        common {
            group("jvmCommon") {
                withJvm()
                // Matched by target name rather than with `withAndroidTarget()`: that predicate is
                // for the old `com.android.library` Kotlin target, and this module is built with
                // AGP's multiplatform-library plugin, whose target is a different type entirely —
                // so the tidy-looking call silently matches nothing and leaves androidMain out of
                // the group.
                withCompilations { it.target.name == "android" }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: SharedFlow and CoroutineDispatcher are part of the public
            // surface (WebViewBridge.messages, WorkflowEngine's dispatcher), so consumers cannot
            // compile against this module without coroutines on their own compile classpath.
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.webkit)
            // Dispatchers.Main — what WebViewDispatcher confines every WebView call to, and the
            // Android counterpart of the Swing dependency below. An app usually has it already,
            // through Compose or lifecycle, which is why its absence stays hidden until something
            // depends on this module alone: then `Dispatchers.Main.immediate` throws
            // "Module with the Main dispatcher is missing" on the first navigate.
            implementation(libs.kotlinx.coroutines.android)
        }
        jvmMain.dependencies {
            // api, not implementation: a desktop host has to hand the pool the `KCEFClient` it
            // initialised, and mounts the browser's AWT component itself, so KCEF's types are on
            // this module's public surface rather than behind it.
            api(libs.kcef)
            // Dispatchers.Swing — the desktop equivalent of Dispatchers.Main, and what
            // WebViewDispatcher confines every CEF call to.
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}
