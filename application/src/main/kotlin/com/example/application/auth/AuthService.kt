package com.example.application.auth

import com.example.domain.auth.AuthIdentityPort
import com.example.domain.auth.AuthProvider
import com.example.domain.auth.AuthSessionPort
import com.example.domain.auth.EmailKind
import com.example.domain.auth.LoginChallengePort
import com.example.domain.auth.UserPort
import java.util.UUID

/**
 * Magic-link / OTP authentication use cases.
 *
 * Wraps the auth repositories with the small bit of policy that defines
 * the flow:
 *  - challenge: per-email rate limit + OTP hashing + persistence;
 *  - verify: code check, find-or-create user, session+refresh issuance;
 *  - refresh: rotating refresh token with reuse detection;
 *  - key: issue/verify/disable opaque self-issued login keys;
 *  - revoke: explicit logout.
 *
 * The service is transport-agnostic: no Ktor types reach this layer.
 * JWT minting lives in the [com.example.auth.AuthHandler]
 * because the signing key is a server-side concern.
 *
 * Repository calls here are blocking JDBC, run on the caller's dispatcher —
 * consistent with every other repo-touching path in the app. Moving that
 * I/O off the dispatcher is a systemic concern to be solved once at the
 * repository layer (see roadmap), not special-cased per method here.
 */
class AuthService(
    private val users: UserPort,
    private val identities: AuthIdentityPort,
    private val sessions: AuthSessionPort,
    private val challenges: LoginChallengePort,
    private val sender: MagicLinkSender,
    /**
     * Pull-based TTL accessors so operator updates to `runtime_config`
     * take effect on the next request without rebuilding the service.
     * Re-evaluated per call; tests pass `{ constant }`.
     */
    private val challengeTtlMs: () -> Long,
    private val sessionTtlMs: () -> Long,
    private val challengeRateLimitWindowMs: Long = DEFAULT_CHALLENGE_RATE_LIMIT_WINDOW_MS,
    private val challengeRateLimitMax: Int = DEFAULT_CHALLENGE_RATE_LIMIT_MAX,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun issueChallenge(email: String): IssueChallengeResult {
        val normalized = email.trim().lowercase()
        if (!isPlausibleEmail(normalized)) return IssueChallengeResult.InvalidEmail
        val now = clock()
        val recentCount = challenges.countCreatedSince(normalized, now - challengeRateLimitWindowMs)
        if (recentCount >= challengeRateLimitMax) return IssueChallengeResult.RateLimited

        val challengeId = UUID.randomUUID().toString()
        val code = AuthCodecs.generateOtp()
        challenges.insert(
            id = challengeId,
            email = normalized,
            codeHash = AuthCodecs.sha256Hex(code),
            expiresAt = now + challengeTtlMs(),
            now = now,
        )
        sender.send(normalized, challengeId, code)
        return IssueChallengeResult.Ok(challengeId)
    }

    suspend fun verifyChallenge(
        challengeId: String,
        code: String,
        deviceLabel: String?,
    ): VerifyChallengeResult {
        val now = clock()
        val challenge = challenges.findById(challengeId) ?: return VerifyChallengeResult.Invalid
        if (challenge.consumedAt != null) return VerifyChallengeResult.Invalid
        if (challenge.expiresAt <= now) return VerifyChallengeResult.Invalid
        // Lock the challenge once the guess budget is spent — even a
        // correct code is rejected past this point, so a known challengeId
        // cannot be brute-forced through the 10^6 OTP space.
        if (challenge.attempts >= MAX_VERIFY_ATTEMPTS) return VerifyChallengeResult.Invalid
        if (!AuthCodecs.constantTimeEquals(challenge.codeHash, AuthCodecs.sha256Hex(code))) {
            challenges.incrementAttempts(challengeId)
            return VerifyChallengeResult.Invalid
        }

        val claimed = challenges.markConsumedIfPending(challengeId, now)
        if (claimed == 0) return VerifyChallengeResult.Invalid

        val userId =
            identities.findBySubject(AuthProvider.EMAIL, challenge.email)?.let { existing ->
                users.touchLastSeen(existing.userId, now)
                existing.userId
            } ?: run {
                val newId = users.insertVerifiedEmail(challenge.email, EmailKind.REAL, now)
                identities.insert(newId, AuthProvider.EMAIL, challenge.email, now)
                newId
            }

        val (sessionId, refreshToken) = issueSession(userId, deviceLabel, now)
        return VerifyChallengeResult.Ok(userId = userId, sessionId = sessionId, refreshToken = refreshToken)
    }

    /**
     * Rotating refresh: a successful refresh issues a brand-new session
     * (new id + new secret) and supersedes the presented one via
     * `replaced_by`. Sliding expiration falls out of this — the successor
     * carries a fresh TTL while the old row expires on its own.
     *
     * Reuse detection: presenting the secret of an already-superseded
     * session means the token was captured and replayed. The whole session
     * chain for that user is revoked and the refresh is rejected.
     */
    suspend fun refreshSession(
        sessionId: String,
        refreshToken: String,
    ): RefreshResult {
        val now = clock()
        val session = sessions.findById(sessionId) ?: return RefreshResult.Invalid
        val presentedHash = AuthCodecs.sha256Hex(refreshToken)

        if (session.replacedBy != null) {
            if (AuthCodecs.constantTimeEquals(session.refreshHash, presentedHash)) {
                // Revoke the chain only if this superseded token is NOT the direct
                // predecessor of a still-active successor — so a legitimate
                // concurrent/retried refresh does not self-revoke the fresh session.
                // Trade-off: a stolen *immediate* predecessor replayed once, before the
                // user rotates again, is indistinguishable from that benign race and is
                // not flagged. It is still rejected (returns Invalid, grants nothing);
                // we favour availability over revoking the legitimate racer on an
                // ambiguous signal. Strict OAuth-BCP "revoke on any reuse" is the
                // alternative if detection is later valued over UX.
                val successor = session.replacedBy?.let { sessions.findById(it) }
                val isImmediatePredecessor =
                    successor != null &&
                        successor.replacedBy == null &&
                        successor.isActive(now)
                if (!isImmediatePredecessor) {
                    sessions.revokeAllForUser(session.userId, now)
                }
            }
            return RefreshResult.Invalid
        }
        if (!session.isActive(now)) return RefreshResult.Invalid
        if (!AuthCodecs.constantTimeEquals(session.refreshHash, presentedHash)) return RefreshResult.Invalid

        val newSessionId = UUID.randomUUID().toString()
        val newRefreshToken = AuthCodecs.generateRefreshToken()
        val rotated =
            sessions.rotateIfActive(
                sessionId = sessionId,
                successorId = newSessionId,
                userId = session.userId,
                deviceLabel = session.deviceLabel,
                refreshHash = AuthCodecs.sha256Hex(newRefreshToken),
                expiresAt = now + sessionTtlMs(),
                now = now,
            )
        if (!rotated) return RefreshResult.Invalid

        users.touchLastSeen(session.userId, now)
        return RefreshResult.Ok(
            userId = session.userId,
            sessionId = newSessionId,
            refreshToken = newRefreshToken,
        )
    }

    /**
     * Self-service key issuance: creates an e-mail-less user, stores only
     * the key's hash, and logs the user straight in. Returns the raw key
     * (shown once — seed-phrase semantics) together with a fresh session so
     * the caller is authenticated immediately and keeps the key for later.
     *
     * The HTTP surface for this is public; callers must rate-limit it (see
     * the abuse note on `configureAuthRoutes`).
     */
    suspend fun issueKey(deviceLabel: String?): KeyIssued {
        val now = clock()
        val userId = users.insertAnonymous(now)
        val rawKey = AuthCodecs.generateKey()
        identities.insert(userId, AuthProvider.KEY, AuthCodecs.sha256Hex(rawKey), now)
        val (sessionId, refreshToken) = issueSession(userId, deviceLabel, now)
        return KeyIssued(
            userId = userId,
            key = rawKey,
            sessionId = sessionId,
            refreshToken = refreshToken,
        )
    }

    /** Exchange a key for a session, unless its identity is disabled. */
    suspend fun verifyKey(
        rawKey: String,
        deviceLabel: String?,
    ): VerifyKeyResult {
        val now = clock()
        val identity =
            identities.findBySubject(AuthProvider.KEY, AuthCodecs.sha256Hex(rawKey))
                ?: return VerifyKeyResult.Invalid
        if (identity.disabledAt != null) return VerifyKeyResult.Invalid
        users.touchLastSeen(identity.userId, now)
        val (sessionId, refreshToken) = issueSession(identity.userId, deviceLabel, now)
        return VerifyKeyResult.Ok(
            userId = identity.userId,
            sessionId = sessionId,
            refreshToken = refreshToken,
        )
    }

    /** Operator action: retire a key so it can no longer log in. */
    suspend fun disableKey(rawKey: String): Boolean =
        identities.disable(AuthProvider.KEY, AuthCodecs.sha256Hex(rawKey), clock()) > 0

    /** Mint a fresh session + refresh secret; returns (sessionId, rawRefreshToken). */
    private fun issueSession(
        userId: Long,
        deviceLabel: String?,
        now: Long,
    ): Pair<String, String> {
        val sessionId = UUID.randomUUID().toString()
        val refreshToken = AuthCodecs.generateRefreshToken()
        sessions.insert(
            id = sessionId,
            userId = userId,
            deviceLabel = deviceLabel,
            refreshHash = AuthCodecs.sha256Hex(refreshToken),
            expiresAt = now + sessionTtlMs(),
            now = now,
        )
        return sessionId to refreshToken
    }

    fun revokeSession(sessionId: String) {
        sessions.revoke(sessionId, clock())
    }

    /**
     * Returns true iff the session id is present, not revoked, and not
     * expired. Backs [com.example.auth.SessionAuthenticator]'s cached
     * `isActive`, which the JWT `validate` block consults so a revoked
     * session is rejected on every `authenticate("kurai")` route.
     *
     * Intentionally non-suspend: it is invoked from a synchronous Caffeine
     * cache loader, so it cannot be `suspend` (and is rare — cache-gated).
     */
    fun isSessionActive(sessionId: String): Boolean {
        val session = sessions.findById(sessionId) ?: return false
        return session.isActive(clock())
    }

    private fun isPlausibleEmail(email: String): Boolean {
        if (email.length !in 3..320) return false
        val at = email.indexOf('@')
        return at in 1..(email.length - 2)
    }

    companion object {
        const val DEFAULT_CHALLENGE_RATE_LIMIT_WINDOW_MS: Long = 60_000
        const val DEFAULT_CHALLENGE_RATE_LIMIT_MAX: Int = 5

        /** Failed verify guesses allowed per challenge before it locks. */
        const val MAX_VERIFY_ATTEMPTS: Int = 5
    }
}

sealed interface IssueChallengeResult {
    data class Ok(
        val challengeId: String,
    ) : IssueChallengeResult

    data object InvalidEmail : IssueChallengeResult

    data object RateLimited : IssueChallengeResult
}

sealed interface VerifyChallengeResult {
    data class Ok(
        val userId: Long,
        val sessionId: String,
        val refreshToken: String,
    ) : VerifyChallengeResult

    data object Invalid : VerifyChallengeResult
}

sealed interface RefreshResult {
    /** [sessionId] / [refreshToken] are the rotated successor, not the input. */
    data class Ok(
        val userId: Long,
        val sessionId: String,
        val refreshToken: String,
    ) : RefreshResult

    data object Invalid : RefreshResult
}

data class KeyIssued(
    val userId: Long,
    val key: String,
    val sessionId: String,
    val refreshToken: String,
)

sealed interface VerifyKeyResult {
    data class Ok(
        val userId: Long,
        val sessionId: String,
        val refreshToken: String,
    ) : VerifyKeyResult

    data object Invalid : VerifyKeyResult
}
