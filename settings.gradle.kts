rootProject.name = "vitre"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // JOGL, which JCEF — and so KCEF, and so the desktop target — depends on at runtime for
        // its offscreen renderer. It is not on Maven Central and never has been, so without this
        // the desktop app compiles and then fails to resolve its own runtime classpath. Narrowed to
        // the one group for the same reason `google()` above is: an unscoped extra repository is
        // somewhere else every dependency in the build can silently come from.
        maven("https://jogamp.org/deployment/maven") {
            mavenContent { includeGroupAndSubgroups("org.jogamp") }
        }
    }
}

include(":vitre-core")
include(":vitre-compose")
include(":vitre-mcp")
include(":sample:composeApp")
include(":sample:androidApp")
include(":sample:desktopApp")
