plugins {
    alias(libs.plugins.kotlin.jvm)
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
    implementation(project(":infrastructure"))

    implementation(libs.caffeine)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.exposed.core)
    testImplementation(libs.exposed.jdbc)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.h2database.h2)
    testImplementation(libs.sqlite.jdbc)
}
