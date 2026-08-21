plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

group = "dev.ggoggam.vitre"

kotlin {
    androidLibrary {
        namespace = "dev.ggoggam.vitre.agent"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        // Same reasoning as :vitre-core — leases are concurrency code, and the two JVM-family
        // runtimes schedule coroutines differently enough that testing one is not testing both.
        withHostTest {}
    }

    jvm()

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api: a host registers its own WebViewController here, and PageDriver hands back
            // core's PageSnapshot and Locator, so core's types are this module's surface rather
            // than an implementation detail.
            //
            // No serialization plugin and no kotlinx-serialization-json: nothing here is
            // @Serializable. Encoding and decoding a page belongs to core (PageSnapshot.decode) on
            // one side and to each adapter's wire format on the other, and this module sits between
            // them without a wire of its own.
            api(project(":vitre-core"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
