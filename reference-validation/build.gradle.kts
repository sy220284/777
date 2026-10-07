plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":harness-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}


tasks.register<JavaExec>("runAdvancedConformance") {
    group = "verification"
    description = "输出777原生高级语义差分结果"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.labteto.dshmobile.reference.AdvancedConformanceMainKt")
    val output = providers.gradleProperty("advancedOutput")
    doFirst {
        args(output.orNull ?: error("缺少 -PadvancedOutput=<path>"))
    }
}
