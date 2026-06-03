package com.example.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.ErrorDetail
import com.example.ErrorResponse
import com.example.application.auth.AuthService
import com.example.application.auth.IssueChallengeResult
import com.example.application.auth.RefreshResult
import com.example.application.auth.VerifyChallengeResult
import com.example.application.auth.VerifyKeyResult
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import java.util.Date
import java.util.UUID

@Serializable
data class ChallengeRequest(
    val email: String,
)

@Serializable
data class ChallengeResponse(
    val challengeId: String,
)

@Serializable
data class VerifyRequest(
    val challengeId: String,
    val code: String,
    val deviceLabel: String? = null,
)

@Serializable
data class VerifyResponse(
    val userId: Long,
    val sessionId: String,
    val jwt: String,
    val refreshToken: String,
)

@Serializable
data class RefreshRequest(
    val sessionId: String,
    val refreshToken: String,
)

@Serializable
data class RefreshResponse(
    val jwt: String,
    val sessionId: String,
    val refreshToken: String,
)

@Serializable
data class KeyIssueRequest(
    val deviceLabel: String? = null,
)

@Serializable
data class KeyIssueResponse(
    val userId: Long,
    val key: String,
    val jwt: String,
    val sessionId: String,
    val refreshToken: String,
)

@Serializable
data class KeyVerifyRequest(
    val key: String,
    val deviceLabel: String? = null,
)

/**
 * HTTP surface for the magic-link / OTP auth flow.
 *
 * JWT minting lives here (the signing secret is a server-side
 * concern); everything else delegates to [AuthService].
 */
class AuthHandler(
    private val authService: AuthService,
    private val sessionAuth: SessionAuthenticator,
    private val issueRateLimiter: FixedWindowRateLimiter,
    private val challengeIpRateLimiter: ChallengeIpRateLimiter,
    private val jwtSecret: String,
    private val jwtTtlMs: suspend () -> Long,
) {
    suspend fun handleChallenge(call: ApplicationCall) {
        if (!challengeIpRateLimiter.tryAcquire(call.request.origin.remoteHost)) {
            call.response.headers.append(
                HttpHeaders.RetryAfter,
                challengeIpRateLimiter.retryAfterSeconds().toString(),
            )
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse(ErrorDetail("TOO_MANY_REQUESTS")))
            return
        }
        val req = call.receive<ChallengeRequest>()
        when (val result = authService.issueChallenge(req.email)) {
            is IssueChallengeResult.Ok ->
                call.respond(ChallengeResponse(result.challengeId))

            IssueChallengeResult.InvalidEmail ->
                call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(ErrorDetail("INVALID_EMAIL")))

            IssueChallengeResult.RateLimited ->
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse(ErrorDetail("TOO_MANY_CHALLENGES")))
        }
    }

    suspend fun handleVerify(call: ApplicationCall) {
        val req = call.receive<VerifyRequest>()
        when (val result = authService.verifyChallenge(req.challengeId, req.code, req.deviceLabel)) {
            is VerifyChallengeResult.Ok ->
                call.respond(
                    VerifyResponse(
                        userId = result.userId,
                        sessionId = result.sessionId,
                        jwt = mintJwt(result.userId, result.sessionId),
                        refreshToken = result.refreshToken,
                    ),
                )

            VerifyChallengeResult.Invalid ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorDetail("INVALID_CODE")))
        }
    }

    suspend fun handleRefresh(call: ApplicationCall) {
        val req = call.receive<RefreshRequest>()
        when (val result = authService.refreshSession(req.sessionId, req.refreshToken)) {
            is RefreshResult.Ok ->
                call.respond(
                    RefreshResponse(
                        jwt = mintJwt(result.userId, result.sessionId),
                        sessionId = result.sessionId,
                        refreshToken = result.refreshToken,
                    ),
                )

            RefreshResult.Invalid ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorDetail("INVALID_SESSION")))
        }
    }

    suspend fun handleKeyIssue(call: ApplicationCall) {
        // Issuance is unauthenticated; throttle per client IP so one host
        // cannot flood the users table. Behind a proxy this needs
        // ForwardedHeaders installed for `remoteHost` to be the real client.
        if (!issueRateLimiter.tryAcquire(call.request.origin.remoteHost)) {
            call.response.headers.append(HttpHeaders.RetryAfter, issueRateLimiter.retryAfterSeconds().toString())
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse(ErrorDetail("TOO_MANY_REQUESTS")))
            return
        }
        val req = call.receive<KeyIssueRequest>()
        val issued = authService.issueKey(req.deviceLabel)
        call.respond(
            KeyIssueResponse(
                userId = issued.userId,
                key = issued.key,
                jwt = mintJwt(issued.userId, issued.sessionId),
                sessionId = issued.sessionId,
                refreshToken = issued.refreshToken,
            ),
        )
    }

    suspend fun handleKeyVerify(call: ApplicationCall) {
        val req = call.receive<KeyVerifyRequest>()
        when (val result = authService.verifyKey(req.key, req.deviceLabel)) {
            is VerifyKeyResult.Ok ->
                call.respond(
                    VerifyResponse(
                        userId = result.userId,
                        sessionId = result.sessionId,
                        jwt = mintJwt(result.userId, result.sessionId),
                        refreshToken = result.refreshToken,
                    ),
                )

            VerifyKeyResult.Invalid ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorDetail("INVALID_KEY")))
        }
    }

    suspend fun handleLogout(call: ApplicationCall) {
        val identity = sessionAuth.requireAuthenticatedSession(call) ?: return
        authService.revokeSession(identity.sessionId)
        sessionAuth.invalidate(identity.sessionId)
        call.respond(HttpStatusCode.NoContent)
    }

    private suspend fun mintJwt(
        userId: Long,
        sessionId: String,
    ): String {
        val now = System.currentTimeMillis()
        return JWT
            .create()
            .withSubject(userId.toString())
            .withClaim("sid", sessionId)
            // `iat`/`exp` are second-resolution; a `jti` nonce keeps two
            // tokens minted within the same second (e.g. rapid refresh)
            // distinct.
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + jwtTtlMs()))
            .sign(Algorithm.HMAC256(jwtSecret))
    }
}
