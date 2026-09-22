plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
    .substringAfterLast('.', "")
    .toIntOrNull()
    ?.coerceIn(0, 999)
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
    inputs.file(rootProject.file("tools/runtime/prepare-termux-node.sh"))
    inputs.property("runtimeAbis", bundledRuntimeAbiArgument)
    outputs.dir(generatedNodeRuntime)
    environment(
        "TERMUX_RUNTIME_CACHE",
        rootProject.file(".gradle/runtime-cache/termux-node").absolutePath,
    )
    environment("DSH_RUNTIME_ABIS", bundledRuntimeAbiArgument)
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
    inputs.file(rootProject.file("tools/runtime/prepare-termux-python.sh"))
    inputs.property("runtimeAbis", bundledRuntimeAbiArgument)
    outputs.dir(generatedPythonRuntime)
    environment(
        "TERMUX_RUNTIME_CACHE",
        rootProject.file(".gradle/runtime-cache/termux-python").absolutePath,
    )
    environment("DSH_RUNTIME_ABIS", bundledRuntimeAbiArgument)
    commandLine(
        "bash",
        rootProject.file("tools/runtime/prepare-termux-python.sh").absolutePath,
        generatedPythonRuntime.get().asFile.absolutePath,
    )
}

android {
    namespace = "com.labteto.dshmobile"
    compileSdk = 36

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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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

    sourceSets.getByName("main").apply {
        jniLibs.srcDir(generatedNodeRuntime.map { it.dir("jniLibs") })
        jniLibs.srcDir(generatedPythonRuntime.map { it.dir("jniLibs") })
        assets.srcDir(generatedNodeRuntime.map { it.dir("assets") })
        assets.srcDir(generatedPythonRuntime.map { it.dir("assets") })
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
        // English is the base resource set and Simplified Chinese is the maintained translation.
        // MissingTranslation stays fatal so the two language surfaces cannot silently drift.
        error += listOf("MissingTranslation", "ImpliedQuantity")
        // `HardcodedText` is deliberately absent: it only inspects XML layouts, and this app has
        // none. Compose string literals have to be caught in review.
    }
}

tasks.matching {
    it.name.startsWith("merge") &&
        (it.name.endsWith("JniLibFolders") || it.name.endsWith("Assets"))
}.configureEach {
    dependsOn(prepareBundledNodeRuntime, prepareBundledPythonRuntime)
}

// Lint model writers inspect the merged asset source set directly. Without this explicit edge
// Gradle 8 correctly rejects the graph as an undeclared generated-source dependency.
tasks.matching { it.name.contains("lint", ignoreCase = true) }.configureEach {
    dependsOn(prepareBundledNodeRuntime, prepareBundledPythonRuntime)
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
    // QR scanning for relay pairing. ZXing rather than ML Kit: it needs no Google Play Services, so
    // it works on a de-Googled device, and pairing is the one flow a user cannot route around.
    implementation(libs.zxing.android.embedded)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The mock harness carries this project's port of the host's answer-acceptance law. The
    // conformance test runs the real encoder through it rather than through a copy, because a copy
    // is a second thing to keep in step and the failure it guards against is a silent one.
    testImplementation(project(":mock-harness"))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
