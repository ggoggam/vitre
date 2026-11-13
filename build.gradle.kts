plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.dokka) apply false
}

// Release candidates go to GitHub Packages rather than Maven Central. Central counts every release
// and every file against the organisation's monthly free tier, and a vitre version is several
// hundred files, so an -rc.N there costs as much of the month as the release it precedes. Stable
// versions still go to Central through the publish plugin's own repository; this one is only ever
// published to by `mise run publish:rc`.
//
// `credentials(PasswordCredentials::class)` reads the gradle properties GitHubPackagesUsername and
// GitHubPackagesPassword, which the release workflow supplies as ORG_GRADLE_PROJECT_* from its
// GITHUB_TOKEN. Gradle only asks for them when a task publishing to this repository is in the graph,
// so builds and `publish:local` never need them.
subprojects {
    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/ggoggam/vitre")
                    credentials(PasswordCredentials::class)
                }
            }
        }
    }
}
