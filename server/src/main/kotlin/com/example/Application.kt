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
    // Fail-fast validation: ensure required env vars are present before serving.
    // The loaded config will be wired into routes once auth-jwt consumes it.
    AppConfig.load()
    val gate = ReadinessGate()
    configure(gate)
    gate.markReady()
}

// Testable entry point — caller controls readiness state.
fun Application.configure(readinessGate: ReadinessGate) {
    install(ContentNegotiation) { json() }
    install(CallLogging)
    install(StatusPages) { errorMapping() }

    configureHealthRoutes(readinessGate)
}
