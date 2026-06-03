plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    version = "1.7.2"
}

dependencies {
    implementation(project(":domain"))

    // `api` for types that :server constructs via `dependencies.provide<…>`:
    // Database, HttpClient, the CIO engine. Without `api`, server would need
    // its own copy of these dependencies to even reference the types.
    api(ktorLibs.client.cio)
    api(ktorLibs.client.contentNegotiation)
    api(ktorLibs.client.core)
    api(libs.exposed.core)
    api(libs.exposed.jdbc)

    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)
    implementation(libs.lucene.core)
    implementation(libs.onnxruntime)
    runtimeOnly(libs.sqlite.jdbc)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.h2database.h2)
    testImplementation(ktorLibs.client.mock)
}
