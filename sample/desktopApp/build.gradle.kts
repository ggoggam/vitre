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
            // For `CefRuntime`: starting Chromium is the app's job, done once in `main`, and
            // :sample:composeApp does not re-export the library it is built on.
            implementation(project(":vitre-core"))
        }
    }
}

// The runtime this app runs on, and the reason it is not just "whatever JDK Gradle has": Chromium
// lives *inside* it. A JetBrains Runtime with JCEF (`jbr_jcef`) carries the `jcef` module, the
// Chromium framework and its helper processes, so an app packaged on it ships Chromium in its
// installer and never downloads anything at runtime. On any other JDK the desktop target has no
// browser at all — `CefRuntime.init` says so rather than crashing.
//
// Provisioned through the toolchain resolver in settings.gradle.kts, which fetches the `jbrsdk_jcef`
// build for the current OS and architecture on first use. Lazily: nothing below asks for it until a
// task that launches or packages the app is actually going to run, so configuring this project —
// which every build of the repo does, CI's included — does not download a JDK.
val jbr =
    javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(libs.versions.jbr.get())
        vendor = JvmVendorSpec.JETBRAINS
    }
val jbrHome = jbr.map { it.metadata.installationPath.asFile.absolutePath }

compose.desktop {
    application {
        mainClass = "dev.ggoggam.vitre.sample.desktop.MainKt"

        nativeDistributions {
            packageName = "Vitre"
            // jlink copies only the modules listed here into the installed app's runtime, and
            // `jcef` is the one that matters: it carries Chromium, so leaving it out produces an app
            // that starts and has no browser. This is what `suggestRuntimeModules` reports; rerun
            // it after adding a dependency that reaches for another part of the JDK.
            modules("java.instrument", "jcef", "jdk.management", "jdk.unsupported")
        }
    }
}

// `compose.desktop.application.javaHome` is a plain string, so setting it from the toolchain would
// resolve — and download — the JDK while the build is still being configured. Each task that uses it
// is pointed at the toolchain instead, as it is realised, which only happens to tasks that will run.
afterEvaluate {
    tasks.withType<JavaExec>().configureEach {
        executable(
            jbr
                .get()
                .executablePath.asFile.absolutePath,
        )
    }
    tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJvmToolOperationTask>().configureEach {
        javaHome.set(jbrHome)
    }
    tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractSuggestModulesTask>().configureEach {
        javaHome.set(jbrHome)
    }
    tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask>().configureEach {
        javaHome.set(jbrHome)
    }
    tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractCheckNativeDistributionRuntime>().configureEach {
        jdkHome.set(jbrHome)
    }

    // On macOS, jlink is not enough. The `jcef` module it copies is only the Java half and its JNI
    // library; the Chromium framework and the helper apps it launches live beside the JDK's module
    // image, in the runtime bundle's `Contents/Frameworks`, and jlink knows nothing about them. An
    // app image without them starts, fails to dlopen `Chromium Embedded Framework`, and dies with a
    // SIGSEGV inside JCEF's own `FindClass` before the window appears.
    //
    // So they are copied into the app's runtime after jpackage has laid it out — with `ditto`,
    // because the framework is full of symlinks a Gradle copy would flatten into duplicates — and
    // the app is then re-signed ad hoc, since adding to a signed bundle invalidates its signature.
    // A host that signs with a real identity has to sign after this step, not inside jpackage.
    //
    // macOS only: Linux and Windows JBR builds keep Chromium inside the module image itself.
    if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
        val frameworks =
            jbr.map {
                it.metadata.installationPath.asFile.parentFile
                    .resolve("Frameworks")
            }
        tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
            if (targetFormat != org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage) return@configureEach
            val appImage = destinationDir
            val appName = packageName
            doLast {
                val app = appImage.get().asFile.resolve("${appName.get()}.app")
                val target = app.resolve("Contents/runtime/Contents/Frameworks")

                fun run(vararg command: String) {
                    val exit = ProcessBuilder(*command).inheritIO().start().waitFor()
                    check(exit == 0) { "${command.first()} exited with $exit" }
                }
                run("ditto", frameworks.get().absolutePath, target.absolutePath)
                run("codesign", "--force", "--deep", "--sign", "-", app.absolutePath)
            }
        }
    }
}
