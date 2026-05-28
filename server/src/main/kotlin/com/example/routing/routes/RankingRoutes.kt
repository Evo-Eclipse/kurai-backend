package com.example.routing.routes

import com.example.routing.handlers.RankingHandler
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureRankingRoutes(handler: RankingHandler) {
    routing {
        authenticate("kurai") {
            post("/ranking/score") { handler.handleScore(call) }
        }
    }
}
