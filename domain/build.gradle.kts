plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(25)
}

configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    version = "1.7.2"
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
