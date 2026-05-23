package com.example

import com.example.routing.configureHealthRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages

// Entry point loaded by Ktor EngineMain via application.yaml.
fun Application.configure() {
    val gate = ReadinessGate()
    configure(AppConfig.load(), gate)
    gate.markReady()
}

// Testable entry point — caller controls config and readiness state.
fun Application.configure(
    @Suppress("UNUSED_PARAMETER") config: AppConfig,
    readinessGate: ReadinessGate,
) {
    install(ContentNegotiation) { json() }
    install(CallLogging)
    install(StatusPages) { errorMapping() }

    configureHealthRoutes(readinessGate)
}
