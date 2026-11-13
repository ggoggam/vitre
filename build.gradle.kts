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

// Every release, stable or prerelease, goes to this repository's GitHub Packages registry. Releases
// up to 0.3.1 went to Maven Central and stay there, but Central now caps how much an organisation
// can publish a month, and a vitre version is several hundred files. This is the only remote
// repository the library modules publish to; `mise run publish` is what uploads to it.
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
