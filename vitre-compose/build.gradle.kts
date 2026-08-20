plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

group = "dev.ggoggam.vitre"

kotlin {
    androidLibrary {
        namespace = "dev.ggoggam.vitre.compose"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
    }

    jvm()

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":vitre-core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.webkit)
        }
        jvmMain.dependencies {
            // A lane renders offscreen and is drawn as a Skia image, so this target needs the
            // desktop half of Compose for skiko — the multiplatform `compose.ui` artifact does not
            // carry `org.jetbrains.skia`.
            implementation(compose.desktop.currentOs)
        }
    }
}
