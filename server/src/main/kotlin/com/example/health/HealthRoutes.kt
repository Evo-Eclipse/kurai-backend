package com.example.health

import com.example.ReadinessGate
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.configureHealthRoutes(readinessGate: ReadinessGate) {
    routing {
        get("/health/live") {
            call.respond(HttpStatusCode.OK)
        }
        get("/health/ready") {
            val status = if (readinessGate.isReady()) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(status)
        }
    }
}
