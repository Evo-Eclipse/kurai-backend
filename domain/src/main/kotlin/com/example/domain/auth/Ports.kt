package com.example.domain.auth

interface UserPort {
    suspend fun insertVerifiedEmail(
        email: String,
        emailKind: String,
        now: Long,
    ): Long

    suspend fun insertAnonymous(now: Long): Long

    suspend fun touchLastSeen(
        userId: Long,
        now: Long,
    )
}

interface AuthIdentityPort {
    suspend fun findBySubject(
        provider: String,
        providerSubject: String,
    ): AuthIdentity?

    suspend fun insert(
        userId: Long,
        provider: String,
        providerSubject: String,
        now: Long,
    )

    suspend fun disable(
        provider: String,
        providerSubject: String,
        now: Long,
    ): Int
}

interface AuthSessionPort {
    suspend fun findById(id: String): AuthSession?

    suspend fun insert(
        id: String,
        userId: Long,
        deviceLabel: String?,
        refreshHash: String,
        expiresAt: Long,
        now: Long,
    )

    suspend fun revoke(
        id: String,
        now: Long,
    ): Int

    /**
     * Atomically supersedes an active session with a successor row. Returns
     * false when [sessionId] was already replaced or revoked.
     */
    suspend fun rotateIfActive(
        sessionId: String,
        successorId: String,
        userId: Long,
        deviceLabel: String?,
        refreshHash: String,
        expiresAt: Long,
        now: Long,
    ): Boolean

    suspend fun revokeAllForUser(
        userId: Long,
        now: Long,
    ): Int

    suspend fun deleteExpiredBefore(cutoff: Long): Int
}

interface LoginChallengePort {
    suspend fun insert(
        id: String,
        email: String,
        codeHash: String,
        expiresAt: Long,
        now: Long,
    )

    suspend fun findById(id: String): LoginChallenge?

    suspend fun incrementAttempts(id: String): Int

    suspend fun markConsumedIfPending(
        id: String,
        now: Long,
    ): Int

    suspend fun countCreatedSince(
        email: String,
        sinceMillis: Long,
    ): Long
}
