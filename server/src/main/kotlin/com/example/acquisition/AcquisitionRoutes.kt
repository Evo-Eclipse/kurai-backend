package com.example.acquisition

import com.example.acquisition.AcquisitionHandler
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
