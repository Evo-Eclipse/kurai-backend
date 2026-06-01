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
            // Self-service key onboarding (expo). Issuance is unauthenticated
            // by design — frictionless sign-up — so it creates a user on every
            // call. TODO: add a per-IP rate limit before this is abuse-exposed.
            post("/key/issue") { handler.handleKeyIssue(call) }
            post("/key/verify") { handler.handleKeyVerify(call) }

            // Authenticated: revokes the caller's own session.
            authenticate("kurai") {
                post("/logout") { handler.handleLogout(call) }
            }
        }
    }
}
