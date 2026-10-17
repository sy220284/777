plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// V6 alone owns its generated CJK fonts. The app and neutral Host need no visual resources.
val generatedV6Fonts = layout.buildDirectory.dir("generated/uiFontRes")
val generateUiFontSubset = tasks.register<Exec>("generateUiFontSubset") {
    group = "build setup"
    inputs.file(rootProject.file("tools/fonts/source/NotoSansSC-wght.ttf"))
    inputs.file(rootProject.file("tools/fonts/build_ui_font.py"))
    inputs.file(rootProject.file("tools/fonts/generate.sh"))
    inputs.dir(rootProject.file("app/src/main/res"))
    inputs.dir(rootProject.file("app/src/main/java/com/labteto/dshmobile/ui"))
    inputs.dir(rootProject.file("ui-pack-v6/src/main/kotlin"))
    outputs.dir(generatedV6Fonts)
    outputs.file(layout.buildDirectory.file("generated/ui-font-subset-manifest.json"))
    environment("DSH_FONT_OFFLINE", gradle.startParameter.isOffline.toString())
    commandLine("bash", rootProject.file("tools/fonts/generate.sh").absolutePath, generatedV6Fonts.get().asFile.absolutePath)
}
tasks.matching {
    (it.name.contains("Resources") || it.name.endsWith("SourceSetPaths") ||
        it.name.startsWith("extractDeepLinks") || it.name.startsWith("lint", ignoreCase = true)) &&
        it.name != "generateUiFontSubset"
}.configureEach { dependsOn(generateUiFontSubset) }

android {
    namespace = "com.labteto.dshmobile.uipack.v6"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 36 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
// AGP 9 Library source-set wrappers are not compatible with the old sourceSets API.
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.res?.addStaticSourceDirectory(generatedV6Fonts.get().asFile.absolutePath)
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
dependencies {
    implementation(project(":ui-host"))
    implementation(project(":ui-contract"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    constraints {
        // Independent resolution must use the same verified Lifecycle baseline as the App.
        // Constraints add no unused runtime dependency and allow newer transitive versions.
        listOf("lifecycle-common-java8", "lifecycle-process", "lifecycle-runtime",
            "lifecycle-runtime-ktx", "lifecycle-viewmodel", "lifecycle-viewmodel-ktx",
            "lifecycle-viewmodel-savedstate").forEach { artifact ->
            implementation("androidx.lifecycle:$artifact:${libs.versions.lifecycle.get()}")
        }
        implementation("androidx.tracing:tracing:1.2.0")
    }
}
