package com.example.domain.auth

interface UserPort {
    fun insertVerifiedEmail(
        email: String,
        emailKind: String,
        now: Long,
    ): Long

    fun insertAnonymous(now: Long): Long

    fun touchLastSeen(
        userId: Long,
        now: Long,
    )
}

interface AuthIdentityPort {
    fun findBySubject(
        provider: String,
        providerSubject: String,
    ): AuthIdentity?

    fun insert(
        userId: Long,
        provider: String,
        providerSubject: String,
        now: Long,
    )

    fun disable(
        provider: String,
        providerSubject: String,
        now: Long,
    ): Int
}

interface AuthSessionPort {
    fun findById(id: String): AuthSession?

    fun insert(
        id: String,
        userId: Long,
        deviceLabel: String?,
        refreshHash: String,
        expiresAt: Long,
        now: Long,
    )

    fun revoke(
        id: String,
        now: Long,
    ): Int

    /**
     * Atomically supersedes an active session with a successor row. Returns
     * false when [sessionId] was already replaced or revoked.
     */
    fun rotateIfActive(
        sessionId: String,
        successorId: String,
        userId: Long,
        deviceLabel: String?,
        refreshHash: String,
        expiresAt: Long,
        now: Long,
    ): Boolean

    fun revokeAllForUser(
        userId: Long,
        now: Long,
    ): Int

    fun deleteExpiredBefore(cutoff: Long): Int
}

interface LoginChallengePort {
    fun insert(
        id: String,
        email: String,
        codeHash: String,
        expiresAt: Long,
        now: Long,
    )

    fun findById(id: String): LoginChallenge?

    fun incrementAttempts(id: String): Int

    fun markConsumedIfPending(
        id: String,
        now: Long,
    ): Int

    fun countCreatedSince(
        email: String,
        sinceMillis: Long,
    ): Long
}
