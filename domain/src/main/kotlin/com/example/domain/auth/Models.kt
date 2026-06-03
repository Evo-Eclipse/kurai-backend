package com.example.domain.auth

data class AuthIdentity(
    val id: Long,
    val userId: Long,
    val provider: String,
    val providerSubject: String,
    val disabledAt: Long?,
    val createdAt: Long,
)

data class AuthSession(
    val id: String,
    val userId: Long,
    val deviceLabel: String?,
    val refreshHash: String,
    val replacedBy: String?,
    val lastUsedAt: Long,
    val expiresAt: Long,
    val revokedAt: Long?,
    val createdAt: Long,
) {
    fun isActive(now: Long): Boolean = revokedAt == null && expiresAt > now
}

data class LoginChallenge(
    val id: String,
    val email: String,
    val codeHash: String,
    val expiresAt: Long,
    val consumedAt: Long?,
    val attempts: Int,
    val createdAt: Long,
)
