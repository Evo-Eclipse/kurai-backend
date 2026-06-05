package com.example.auth

import com.example.ErrorDetail
import com.example.ErrorResponse
import com.example.application.auth.AuthService
import com.github.benmanes.caffeine.cache.AsyncLoadingCache
import com.github.benmanes.caffeine.cache.Caffeine
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
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
 * Caches the active/inactive verdict per `sessionId` in a Caffeine
 * [AsyncLoadingCache] so a torrent of requests on one session does not
 * hammer the DB. The loader is the *suspend* [AuthService.isSessionActive]
 * run on [scope] — so the JWT `validate` path awaits the verdict without
 * blocking a thread (no `runBlocking`). The TTL is intentionally short
 * (30 s): a revoked session keeps working for at most one cache window,
 * which is acceptable for logout UX while saving most DB round-trips.
 */
class SessionAuthenticator(
    private val authService: AuthService,
    cacheTtl: Duration = Duration.ofSeconds(30),
    cacheMaxSize: Long = 10_000,
) : AutoCloseable {
    /** Owns the coroutine that runs the async cache loader; cancelled by [close]. */
    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val activeSessionCache: AsyncLoadingCache<String, Boolean> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(cacheTtl)
            .maximumSize(cacheMaxSize)
            .buildAsync { sessionId, _ -> loaderScope.future { authService.isSessionActive(sessionId) } }

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
        if (!isActive(sid)) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorDetail("INVALID_SESSION")))
            return null
        }
        return CallerIdentity(userId = sub, sessionId = sid)
    }

    /**
     * Cached active/revoked verdict for a session id. Backs the JWT
     * `validate` block in `Application.configure`, so the revocation
     * check applies to every `authenticate("kurai")` route — not just
     * the ones that call [requireAuthenticatedSession] directly. Suspends
     * on the async cache instead of blocking a thread.
     */
    suspend fun isActive(sessionId: String): Boolean = activeSessionCache.get(sessionId).await()

    /** Invalidate the cache entry immediately on revoke. */
    fun invalidate(sessionId: String) {
        activeSessionCache.synchronous().invalidate(sessionId)
    }

    /** Cancels the loader scope; called on application shutdown. */
    override fun close() {
        loaderScope.cancel()
    }
}
