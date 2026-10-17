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
include(":harness-runtime-android")
include(":harness-interop")
include(":harness-device-android")

include(":ui-contract")
include(":ui-host")

// 朝夕 E8.17 独立视觉模块；业务逻辑保留在 App Feature Owner。
include(":ui-pack-zhaoxi")
