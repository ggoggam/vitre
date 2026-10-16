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
        // JetBrains' own repository, for the one artifact the desktop target needs from it: JCEF's
        // Java API, which is compile-only (see `jcef` in the version catalog). JetBrains publishes it
        // here and not on Maven Central. Narrowed to the one group for the same reason `google()`
        // above is: an unscoped extra repository is somewhere else every dependency in the build can
        // silently come from.
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
            mavenContent { includeGroup("org.jetbrains.intellij.deps.jcef") }
        }
    }
}

include(":vitre-core")
include(":vitre-agent")
include(":vitre-compose")
include(":vitre-mcp")
include(":vitre-koog")
include(":sample:composeApp")
include(":sample:androidApp")
include(":sample:desktopApp")
