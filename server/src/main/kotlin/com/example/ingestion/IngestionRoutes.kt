package com.example.ingestion

import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureIngestionRoutes(handler: IngestionHandler) {
    routing {
        authenticate("kurai") {
            post("/ingestion/events") { handler.handleIngest(call) }
        }
    }
}
