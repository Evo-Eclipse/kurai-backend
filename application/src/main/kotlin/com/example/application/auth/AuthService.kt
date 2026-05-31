package com.example.application.auth

import com.example.infrastructure.sqlite.AuthIdentityRepository
import com.example.infrastructure.sqlite.AuthProvider
import com.example.infrastructure.sqlite.AuthSessionRepository
import com.example.infrastructure.sqlite.EmailKind
import com.example.infrastructure.sqlite.LoginChallengeRepository
import com.example.infrastructure.sqlite.UserRepository
import java.util.UUID

/**
 * Magic-link / OTP authentication use cases.
 *
 * Wraps the four auth repositories with the small bit of policy that
 * defines the flow:
 *  - challenge: per-email rate limit + OTP hashing + persistence;
 *  - verify: code check, find-or-create user, session+refresh issuance;
 *  - refresh: refresh-token check and lastSeen update;
 *  - revoke: explicit logout.
 *
 * The service is transport-agnostic: no Ktor types reach this layer.
 * JWT minting lives in the [com.example.routing.handlers.AuthHandler]
 * because the signing key is a server-side concern.
 */
class AuthService(
    private val users: UserRepository,
    private val identities: AuthIdentityRepository,
    private val sessions: AuthSessionRepository,
    private val challenges: LoginChallengeRepository,
    private val sender: MagicLinkSender,
    private val challengeTtlMs: Long,
    private val sessionTtlMs: Long,
    private val challengeRateLimitWindowMs: Long = DEFAULT_CHALLENGE_RATE_LIMIT_WINDOW_MS,
    private val challengeRateLimitMax: Int = DEFAULT_CHALLENGE_RATE_LIMIT_MAX,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun issueChallenge(email: String): IssueChallengeResult {
        val normalized = email.trim().lowercase()
        if (!isPlausibleEmail(normalized)) return IssueChallengeResult.InvalidEmail
        val now = clock()
        val activeCount = challenges.countActiveSince(normalized, now - challengeRateLimitWindowMs)
        if (activeCount >= challengeRateLimitMax) return IssueChallengeResult.RateLimited

        val challengeId = UUID.randomUUID().toString()
        val code = AuthCodecs.generateOtp()
        challenges.insert(
            id = challengeId,
            email = normalized,
            codeHash = AuthCodecs.sha256Hex(code),
            expiresAt = now + challengeTtlMs,
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
        if (challenge.codeHash != AuthCodecs.sha256Hex(code)) return VerifyChallengeResult.Invalid

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

        val sessionId = UUID.randomUUID().toString()
        val refreshToken = AuthCodecs.generateRefreshToken()
        sessions.insert(
            id = sessionId,
            userId = userId,
            deviceLabel = deviceLabel,
            refreshHash = AuthCodecs.sha256Hex(refreshToken),
            expiresAt = now + sessionTtlMs,
            now = now,
        )
        return VerifyChallengeResult.Ok(userId = userId, sessionId = sessionId, refreshToken = refreshToken)
    }

    suspend fun refreshSession(
        sessionId: String,
        refreshToken: String,
    ): RefreshResult {
        val now = clock()
        val session = sessions.findById(sessionId) ?: return RefreshResult.Invalid
        if (!session.isActive(now)) return RefreshResult.Invalid
        if (session.refreshHash != AuthCodecs.sha256Hex(refreshToken)) return RefreshResult.Invalid
        users.touchLastSeen(session.userId, now)
        return RefreshResult.Ok(userId = session.userId, sessionId = sessionId)
    }

    fun revokeSession(sessionId: String) {
        sessions.revoke(sessionId, clock())
    }

    /**
     * Returns true iff the session id is present, not revoked, and not
     * expired. Used by `CallAuth.requireUserId` to back the per-request
     * session check (with a small Caffeine cache).
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
    data class Ok(
        val userId: Long,
        val sessionId: String,
    ) : RefreshResult

    data object Invalid : RefreshResult
}
