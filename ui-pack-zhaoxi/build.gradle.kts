plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// 朝夕独立 UI 包（E8.11）。与 ui-pack-v6 平级，不依赖 app/V6/shenyan.designsystem。
android {
    namespace = "com.labteto.dshmobile.uipack.zhaoxi"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 36 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
dependencies {
    // 只允许这两个内部模块：契约 + 宿主 SDK。禁止 project(":app")、project(":ui-pack-v6")。
    implementation(project(":ui-host"))
    implementation(project(":ui-contract"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(kotlin("test"))
    constraints {
        listOf("lifecycle-common-java8", "lifecycle-process", "lifecycle-runtime",
            "lifecycle-runtime-ktx", "lifecycle-viewmodel", "lifecycle-viewmodel-ktx",
            "lifecycle-viewmodel-savedstate").forEach { artifact ->
            implementation("androidx.lifecycle:$artifact:${libs.versions.lifecycle.get()}")
        }
        implementation("androidx.tracing:tracing:1.2.0")
    }
}
