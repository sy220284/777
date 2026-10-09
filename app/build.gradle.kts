import java.time.Duration

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * The release version, taken from the git tag the release workflow is building.
 *
 * `DSH_VERSION_NAME` is the tag without its leading `v`. Hardcoding it here meant every tag after
 * the first shipped an APK still claiming to be the first — same `versionCode`, so Android saw no
 * upgrade at all. The code is derived from the name so it rises with semver on its own; the
 * fallback is what a local `assembleRelease` builds.
 */
val dshVersionName: String = System.getenv("DSH_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: rootProject.file(".github/release-version").readText().trim()

val dshForkRevision: Int = dshVersionName
    .substringAfter('-', "")
    .takeIf(String::isNotBlank)
    ?.let { suffix ->
        val revision = suffix.substringAfterLast('.', "").toIntOrNull()
            ?: error("发布版本后缀缺少数字 revision：$dshVersionName")
        require(revision in 0..999) {
            "发布 revision 必须在 0..999，避免 versionCode 碰撞：$dshVersionName"
        }
        revision
    }
    ?: 0

val dshVersionCode: Int = dshVersionName
    .substringBefore('-')
    .split('.')
    .mapNotNull { it.toIntOrNull() }
    .let { parts ->
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        (major * 10_000 + minor * 100 + patch) * 1_000 + dshForkRevision
    }
    .coerceAtLeast(1)

val bundledRuntimeAbis = (System.getenv("DSH_RUNTIME_ABIS") ?: "arm64-v8a")
    .split(',')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()
val supportedRuntimeAbis = setOf("arm64-v8a", "x86_64")
require(bundledRuntimeAbis.isNotEmpty() && bundledRuntimeAbis.all(supportedRuntimeAbis::contains)) {
    "DSH_RUNTIME_ABIS 仅支持 arm64-v8a 或 x86_64：${bundledRuntimeAbis.joinToString()}"
}
val bundledRuntimeAbiArgument = bundledRuntimeAbis.joinToString(",")

val generatedNodeRuntime = layout.buildDirectory.dir("generated/nodeRuntime")
val prepareBundledNodeRuntime = tasks.register<Exec>("prepareBundledNodeRuntime") {
    group = "build setup"
    description = "Fetches and verifies the bundled Android Node.js runtime from Termux."
    inputs.file(rootProject.file("tools/runtime/runtime-download.sh"))
    inputs.file(rootProject.file("tools/runtime/verify-termux-signature.sh"))
    inputs.file(rootProject.file("tools/runtime/prepare-termux-node.sh"))
    inputs.property("runtimeAbis", bundledRuntimeAbiArgument)
    outputs.dir(generatedNodeRuntime)
    environment(
        "TERMUX_RUNTIME_CACHE",
        rootProject.file(".gradle/runtime-cache/termux-node").absolutePath,
    )
    environment("DSH_RUNTIME_ABIS", bundledRuntimeAbiArgument)
    environment("DSH_RUNTIME_OFFLINE", gradle.startParameter.isOffline.toString())
    commandLine(
        "bash",
        rootProject.file("tools/runtime/prepare-termux-node.sh").absolutePath,
        generatedNodeRuntime.get().asFile.absolutePath,
    )
}

val generatedPythonRuntime = layout.buildDirectory.dir("generated/pythonRuntime")
val prepareBundledPythonRuntime = tasks.register<Exec>("prepareBundledPythonRuntime") {
    group = "build setup"
    description = "Fetches and verifies the bundled Android Python runtime from Termux."
    inputs.file(rootProject.file("tools/runtime/runtime-download.sh"))
    inputs.file(rootProject.file("tools/runtime/verify-termux-signature.sh"))
    inputs.file(rootProject.file("tools/runtime/prepare-termux-python.sh"))
    inputs.property("runtimeAbis", bundledRuntimeAbiArgument)
    outputs.dir(generatedPythonRuntime)
    environment(
        "TERMUX_RUNTIME_CACHE",
        rootProject.file(".gradle/runtime-cache/termux-python").absolutePath,
    )
    environment("DSH_RUNTIME_ABIS", bundledRuntimeAbiArgument)
    environment("DSH_RUNTIME_OFFLINE", gradle.startParameter.isOffline.toString())
    commandLine(
        "bash",
        rootProject.file("tools/runtime/prepare-termux-python.sh").absolutePath,
        generatedPythonRuntime.get().asFile.absolutePath,
    )
}

val generatedGitRuntime = layout.buildDirectory.dir("generated/gitRuntime")
val prepareBundledGitRuntime = tasks.register<Exec>("prepareBundledGitRuntime") {
    group = "build setup"
    description = "Fetches and verifies the bundled Android Git runtime from Termux."
    inputs.file(rootProject.file("tools/runtime/runtime-download.sh"))
    inputs.file(rootProject.file("tools/runtime/verify-termux-signature.sh"))
    inputs.file(rootProject.file("tools/runtime/prepare-termux-git.sh"))
    inputs.property("runtimeAbis", bundledRuntimeAbiArgument)
    outputs.dir(generatedGitRuntime)
    environment(
        "TERMUX_RUNTIME_CACHE",
        rootProject.file(".gradle/runtime-cache/termux-git").absolutePath,
    )
    environment("DSH_RUNTIME_ABIS", bundledRuntimeAbiArgument)
    environment("DSH_RUNTIME_OFFLINE", gradle.startParameter.isOffline.toString())
    commandLine(
        "bash",
        rootProject.file("tools/runtime/prepare-termux-git.sh").absolutePath,
        generatedGitRuntime.get().asFile.absolutePath,
    )
}

// Generated fixed UI fonts are never copied from the complete source into the APK.
val generatedUiFontRes = layout.buildDirectory.dir("generated/uiFontRes")
val generateUiFontSubset = tasks.register<Exec>("generateUiFontSubset") {
    group = "build setup"
    description = "Produce Noto Sans SC UI glyph subsets in real font weight files."
    inputs.file(rootProject.file("tools/fonts/source/NotoSansSC-wght.ttf"))
    inputs.file(rootProject.file("tools/fonts/build_ui_font.py"))
    inputs.file(rootProject.file("tools/fonts/generate.sh"))
    inputs.dir(rootProject.file("app/src/main/res"))
    inputs.dir(rootProject.file("app/src/main/java/com/labteto/dshmobile/ui"))
    outputs.dir(generatedUiFontRes)
    outputs.file(layout.buildDirectory.file("generated/ui-font-subset-manifest.json"))
    environment("DSH_FONT_OFFLINE", gradle.startParameter.isOffline.toString())
    commandLine("bash", rootProject.file("tools/fonts/generate.sh").absolutePath, generatedUiFontRes.get().asFile.absolutePath)
}

val generatedBundledRuntimeAssets = layout.buildDirectory.dir("generated/bundledRuntimeAssets")
val compactBundledRuntimeAssets = tasks.register<Exec>("compactBundledRuntimeAssets") {
    group = "build setup"
    description = "Deduplicates bundled runtime shared libraries by content hash."
    dependsOn(
        prepareBundledNodeRuntime,
        prepareBundledPythonRuntime,
        prepareBundledGitRuntime,
    )
    inputs.file(rootProject.file("tools/runtime/compact-runtime-assets.py"))
    inputs.dir(generatedNodeRuntime.map { it.dir("assets") })
    inputs.dir(generatedPythonRuntime.map { it.dir("assets") })
    inputs.dir(generatedGitRuntime.map { it.dir("assets") })
    outputs.dir(generatedBundledRuntimeAssets)
    commandLine(
        "python3",
        rootProject.file("tools/runtime/compact-runtime-assets.py").absolutePath,
        generatedBundledRuntimeAssets.get().asFile.absolutePath,
        generatedNodeRuntime.get().dir("assets").asFile.absolutePath,
        generatedPythonRuntime.get().dir("assets").asFile.absolutePath,
        generatedGitRuntime.get().dir("assets").asFile.absolutePath,
    )
}

val generatedUpdatePatcher = layout.buildDirectory.dir("generated/updatePatcher")
val prepareUpdatePatcher = tasks.register<Exec>("prepareUpdatePatcher") {
    group = "build setup"
    description = "Fetches and verifies the pinned HDiffPatch Android patch runtime."
    inputs.file(rootProject.file("tools/runtime/runtime-download.sh"))
    inputs.file(rootProject.file("tools/runtime/prepare-hdiffpatch.sh"))
    inputs.property("runtimeAbis", bundledRuntimeAbiArgument)
    outputs.dir(generatedUpdatePatcher)
    environment(
        "HDIFFPATCH_CACHE",
        rootProject.file(".gradle/runtime-cache/hdiffpatch").absolutePath,
    )
    environment("DSH_RUNTIME_ABIS", bundledRuntimeAbiArgument)
    environment("DSH_RUNTIME_OFFLINE", gradle.startParameter.isOffline.toString())
    commandLine(
        "bash",
        rootProject.file("tools/runtime/prepare-hdiffpatch.sh").absolutePath,
        generatedUpdatePatcher.get().asFile.absolutePath,
    )
}

android {
    namespace = "com.labteto.dshmobile"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        // Keep this fork installable alongside the upstream DSH Mobile app.
        applicationId = "com.sy220284.dshmobile"
        // This fork deliberately uses Android 16 platform behavior directly.
        minSdk = 36
        targetSdk = 36
        versionCode = dshVersionCode
        versionName = dshVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters.addAll(bundledRuntimeAbis) }
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        create("optimized") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".debug"
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Optional release signing: provide DSH_KEYSTORE / DSH_KEYSTORE_PASSWORD /
    // DSH_KEY_ALIAS / DSH_KEY_PASSWORD (env vars, e.g. from GitHub secrets).
    // Signing activates only when the keystore file actually exists, so a
    // missing keystore silently falls back to an unsigned release APK.
    signingConfigs {
        val keystore = System.getenv("DSH_KEYSTORE")
        if (!keystore.isNullOrBlank() && file(keystore).exists()) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("DSH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DSH_KEY_ALIAS")
                keyPassword = System.getenv("DSH_KEY_PASSWORD")
            }
            buildTypes.getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // AAPT's default asset filter contains <dir>_* and silently drops Python 3.14's
        // Lib/compression/_common package. Keep the other default ignores, but allow
        // underscore-prefixed directories because Python uses them as real packages.
        ignoreAssetsPattern =
            "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~"
    }

    sourceSets.getByName("test").resources.srcDir(
        rootProject.file("reference-validation/src/test/resources"),
    )

    sourceSets.getByName("main").res.srcDir(generatedUiFontRes.get().asFile)

    sourceSets.getByName("main").apply {
        // AGP 9 rejects Provider-backed entries in the legacy SourceSet API. These providers only
        // describe deterministic build-directory paths; generating task edges remain explicit
        // below, so resolve the paths here without weakening AGP's source-set checks.
        jniLibs.srcDir(generatedNodeRuntime.get().dir("jniLibs").asFile)
        jniLibs.srcDir(generatedPythonRuntime.get().dir("jniLibs").asFile)
        jniLibs.srcDir(generatedGitRuntime.get().dir("jniLibs").asFile)
        jniLibs.srcDir(generatedUpdatePatcher.get().dir("jniLibs").asFile)
        assets.srcDir(generatedBundledRuntimeAssets.get().dir("assets").asFile)
    }

    packaging {
        // Android target 29+ may only exec app binaries from nativeLibraryDir. Force extraction
        // instead of leaving the bundled executable mapped directly from the APK.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Chinese is the single user-facing base resource set; there is no locale translation surface.
        error += listOf("ImpliedQuantity")
        // `HardcodedText` is deliberately absent: it only inspects XML layouts, and this app has
        // none. Compose string literals have to be caught in review.
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.matching {
    it.name.startsWith("merge") &&
        (it.name.endsWith("JniLibFolders") || it.name.endsWith("Assets"))
}.configureEach {
    dependsOn(
        prepareBundledNodeRuntime,
        prepareBundledPythonRuntime,
        prepareBundledGitRuntime,
        compactBundledRuntimeAssets,
        prepareUpdatePatcher,
    )
}

// Resource and lint model consumers need declared generation dependencies.
tasks.matching {
    (it.name.contains("Resources") || it.name.endsWith("SourceSetPaths") ||
        it.name.startsWith("extractDeepLinks") ||
        it.name.startsWith("lint", ignoreCase = true)) && it.name != "generateUiFontSubset"
}.configureEach { dependsOn(generateUiFontSubset) }

// Lint model writers inspect the merged asset source set directly. Without this explicit edge
// Gradle 8 correctly rejects the graph as an undeclared generated-source dependency.
tasks.matching { it.name.contains("lint", ignoreCase = true) }.configureEach {
    dependsOn(
        compactBundledRuntimeAssets,
        prepareUpdatePatcher,
    )
}

/*
 * CI 必须能指出长时间运行的 JVM 单测停在哪个用例。这里只记录开始与失败事件，
 * 不改变测试选择、超时、并行度或通过条件。
 */
if (System.getenv("CI") == "true") {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        testLogging {
            events("started", "failed")
            showStandardStreams = false
        }
        if (name == "testDebugUnitTest") {
            // 该任务在健康基线中约半秒完成；两分钟只用于快速识别挂死/死锁。
            timeout.set(Duration.ofMinutes(2))
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":harness-core"))
    implementation(project(":harness-runtime-android"))
    implementation(project(":harness-interop"))
    implementation(project(":harness-device-android"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The mock harness carries this project's port of the host's answer-acceptance law. The
    // conformance test runs the real encoder through it rather than through a copy, because a copy
    // is a second thing to keep in step and the failure it guards against is a silent one.
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
