plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

group = "dev.ggoggam.vitre"

kotlin {
    androidLibrary {
        namespace = "dev.ggoggam.vitre.koog"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        withHostTest {}
        // The one test in this repo that runs on a device, because it is the only way to prove the
        // claim the module makes: a Koog agent, its tool registry and the lease feature driving a
        // real Android WebView through real JavaScript. Everything under it is faked in the host
        // tests, so this is what says the seam between them holds.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    jvm()

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api: a host registers its WebViewControllers with this module's session registry and
            // hands the tools to a ToolRegistry it owns, so both sides are on the public surface.
            api(project(":vitre-agent"))
            // Spelled out from the catalog rather than passed as a provider: the source-set DSL has
            // no `api(Provider, configure)` overload, and the exclusions below have to be on the
            // dependency to be published.
            api(
                libs.koog.agents.core
                    .get()
                    .toString(),
            ) {
                // `agents-core` publishes a runtime edge to `ai.koog:serialization-jackson`, and
                // jackson-module-kotlin uses `MethodHandle.invoke`, which D8 will not dex below
                // minSdk 26. This module's minSdk is 24 and stays there, and nothing here asks for a
                // Jackson serializer — Koog's kotlinx one is the default — so the edge is dropped.
                //
                // Excluded on the dependency rather than on a configuration, so that it is published
                // in this module's own metadata and a consuming app inherits it. A consumer that
                // wants Jackson can add it back; one that does not should not have its build fail on
                // a transitive it never asked for.
                exclude(group = "ai.koog", module = "serialization-jackson")
                exclude(group = "com.fasterxml.jackson.module", module = "jackson-module-kotlin")
            }
            // The MCP bridge — `vitreMcpTools`, which turns an already-running McpServer's
            // `tools/list` into Koog tools. `api` because a host that uses it holds the server.
            api(project(":vitre-mcp"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.koog.agents.test)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.junit)
        }
        configurations
            .named { it.startsWith("androidDeviceTest") }
            .configureEach {
                // `kotlinx-coroutines-test` installs a main-dispatcher factory that replaces
                // Android's, and then refuses to hand it out unless `Dispatchers.setMain` was
                // called. A WebView genuinely needs the platform main thread — that is the whole
                // point of this test — so the test dispatcher has to stay off the device.
                //
                // The Jackson exclusion that used to live here is on `agents-core` in commonMain
                // instead: that is where the dependency comes from, and putting it here left the
                // published artifact carrying what the comment claimed it never had.
                exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test")
            }
    }
}
