pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // Kotlin's 2.4.20 JVM plugin marker has different POM bytes on the
        // plugin portal and Maven Central. The pinned SHA-256 belongs to Maven
        // Central; exclude the portal's variant rather than weakening verification.
        gradlePluginPortal {
            content {
                excludeGroup("org.jetbrains.kotlin.jvm")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "deepseek-harness-android-777"

include(":app")
include(":core")
include(":harness-core")
include(":reference-validation")
include(":harness-runtime-android")
include(":harness-interop")
include(":harness-device-android")
