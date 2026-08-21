plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

group = "dev.ggoggam.vitre"

kotlin {
    androidLibrary {
        namespace = "dev.ggoggam.vitre.mcp"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        // Same reasoning as :vitre-core — the protocol layer is pure common code, so running
        // commonTest on one runtime only would leave the JVM half of a multiplatform library
        // untested for no saving.
        withHostTest {}
    }

    jvm()

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api: a host registers its own WebViewController with the session registry, and the
            // tools hand back core's snapshots, so both modules' types are part of this one's
            // surface rather than an implementation detail.
            api(project(":vitre-agent"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
