package com.example.routing.routes

import com.example.routing.handlers.AcquisitionHandler
import io.ktor.server.application.Application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureAcquisitionRoutes(handler: AcquisitionHandler) {
    routing {
        post("/acquisition/run") { handler.handleRun(call) }
        get("/acquisition/jobs/{id}") { handler.handleGetJob(call) }
    }
}
