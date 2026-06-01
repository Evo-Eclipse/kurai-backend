package com.example.auth

import com.example.auth.AuthHandler
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureAuthRoutes(handler: AuthHandler) {
    routing {
        route("/auth") {
            // Public endpoints — no JWT required.
            post("/challenge") { handler.handleChallenge(call) }
            post("/verify") { handler.handleVerify(call) }
            post("/refresh") { handler.handleRefresh(call) }
            post("/legacy/verify") { handler.handleLegacyVerify(call) }

            // Authenticated: revokes the caller's own session.
            authenticate("kurai") {
                post("/logout") { handler.handleLogout(call) }
            }
        }
    }
}
