plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.labteto.dshmobile.uihost"
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
    api(project(":ui-contract"))
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.runtime:runtime")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    constraints {
        listOf("lifecycle-common-java8", "lifecycle-process", "lifecycle-runtime",
            "lifecycle-runtime-ktx", "lifecycle-viewmodel", "lifecycle-viewmodel-ktx",
            "lifecycle-viewmodel-savedstate").forEach { artifact ->
            implementation("androidx.lifecycle:$artifact:${libs.versions.lifecycle.get()}")
        }
        implementation("androidx.tracing:tracing:1.2.0")
        // Resolve the already checksummed version in gradle/verification-metadata.xml;
        // the earlier transitive 1.2.0 module has no verified metadata entry.
        implementation("androidx.emoji2:emoji2:1.4.0")
    }
}
