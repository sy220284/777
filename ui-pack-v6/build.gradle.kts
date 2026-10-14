plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}
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
