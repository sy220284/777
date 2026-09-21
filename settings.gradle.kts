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
        gradlePluginPortal()
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
include(":mock-harness")
include(":harness-runtime-android")
include(":harness-interop")
include(":harness-device-android")
