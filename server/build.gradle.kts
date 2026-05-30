plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

group = "com.example"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}

configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    version = "1.7.2"
}

val smokeTestSourceSet =
    sourceSets.create("smokeTest") {
        kotlin.srcDir("src/smokeTest/kotlin")
        resources.srcDir("src/smokeTest/resources")
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }

configurations["smokeTestImplementation"].extendsFrom(
    configurations["implementation"],
    configurations["testImplementation"],
)

tasks.register<Test>("smokeTest") {
    description = "Runs smoke tests (embedded Ktor server + real HTTP)"
    group = "verification"
    testClassesDirs = smokeTestSourceSet.output.classesDirs
    classpath = smokeTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(tasks["smokeTest"])
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":infrastructure"))

    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.auth)
    implementation(ktorLibs.server.auth.jwt)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.conditionalHeaders)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.defaultHeaders)
    implementation(ktorLibs.server.di)
    implementation(ktorLibs.server.forwardedHeader)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.statusPages)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.h2database.h2)
    testImplementation(ktorLibs.client.mock)
    testImplementation(ktorLibs.server.testHost)
}
