package com.example

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond

/**
 * Extracts the user id from the JWT `sub` claim for ownership checks in
 * request handlers (e.g. that the caller owns the userId in the body).
 * Responds 401 and returns null on a missing/invalid principal.
 *
 * Callers under `authenticate("kurai")` can assume the JWT was already
 * validated (including `sid` revocation) by the global verifier.
 *
 * Lives at the top level — not in any bounded-context package — so every
 * handler can share it without `profile`/`ingestion`/… depending on `auth`
 * (which the ArchUnit slice rule forbids).
 */
suspend fun ApplicationCall.requireAuthenticatedUserId(): Long? {
    val principal =
        principal<JWTPrincipal>()
            ?: run {
                respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorDetail("UNAUTHORIZED")))
                return null
            }
    val sub =
        principal.payload
            .getClaim("sub")
            .asString()
            .toLongOrNull()
    if (sub == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorDetail("UNAUTHORIZED")))
        return null
    }
    return sub
}
