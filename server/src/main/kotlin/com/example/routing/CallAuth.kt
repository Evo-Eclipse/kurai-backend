package com.example.routing

import com.example.ErrorDetail
import com.example.ErrorResponse
import com.example.application.auth.AuthService
import com.github.benmanes.caffeine.cache.Caffeine
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import java.time.Duration

/**
 * Session-aware identity carried by an authenticated request: the
 * stable [userId] from the JWT `sub` claim and the [sessionId] from
 * the JWT `sid` claim. Handlers should never construct this directly —
 * always obtain it through [SessionAuthenticator.requireAuthenticatedSession].
 */
data class CallerIdentity(
    val userId: Long,
    val sessionId: String,
)

/**
 * Bridges JWT validation (which Ktor's auth plugin already performed)
 * with the session-revocation check that lives in [AuthService].
 *
 * Caches the active/inactive verdict per `sessionId` in a small
 * Caffeine cache so a torrent of requests on one session does not
 * hammer the DB. The TTL is intentionally short (30 s): a revoked
 * session keeps working for at most one cache window, which is
 * acceptable for logout UX while saving most of the DB round-trips.
 */
class SessionAuthenticator(
    private val authService: AuthService,
    cacheTtl: Duration = Duration.ofSeconds(30),
    cacheMaxSize: Long = 10_000,
) {
    private val activeSessionCache =
        Caffeine
            .newBuilder()
            .expireAfterWrite(cacheTtl)
            .maximumSize(cacheMaxSize)
            .build<String, Boolean>()

    suspend fun requireAuthenticatedSession(call: ApplicationCall): CallerIdentity? {
        val principal = call.principal<JWTPrincipal>()
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorDetail("UNAUTHORIZED")))
            return null
        }
        val sub =
            principal.payload
                .getClaim("sub")
                .asString()
                .toLongOrNull()
        val sid =
            principal.payload
                .getClaim("sid")
                .asString()
        if (sub == null || sid.isNullOrBlank()) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorDetail("UNAUTHORIZED")))
            return null
        }
        val active = activeSessionCache.get(sid) { authService.isSessionActive(sid) }
        if (active != true) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorDetail("INVALID_SESSION")))
            return null
        }
        return CallerIdentity(userId = sub, sessionId = sid)
    }

    /** Invalidate the cache entry immediately on revoke. */
    fun invalidate(sessionId: String) {
        activeSessionCache.invalidate(sessionId)
    }
}
