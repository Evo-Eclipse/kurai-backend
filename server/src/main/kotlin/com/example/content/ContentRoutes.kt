package com.example.content

import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureContentRoutes(handler: ContentHandler) {
    routing {
        authenticate("kurai") {
            post("/content/proxy") { handler.handleProxy(call) }
            post("/content/shuttle") { handler.handleShuttle(call) }
        }
    }
}
