package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

data class AuthSessionRow(
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

class AuthSessionRepository(
    private val db: Database,
) {
    fun findById(id: String): AuthSessionRow? =
        transaction(db) {
            AuthSessions
                .selectAll()
                .where { AuthSessions.id eq id }
                .firstOrNull()
                ?.let(::rowToSession)
        }

    fun insert(
        id: String,
        userId: Long,
        deviceLabel: String?,
        refreshHash: String,
        expiresAt: Long,
        now: Long,
    ) {
        transaction(db) {
            AuthSessions.insert {
                it[AuthSessions.id] = id
                it[AuthSessions.userId] = userId
                it[AuthSessions.deviceLabel] = deviceLabel
                it[AuthSessions.refreshHash] = refreshHash
                it[AuthSessions.lastUsedAt] = now
                it[AuthSessions.expiresAt] = expiresAt
                it[AuthSessions.createdAt] = now
            }
        }
    }

    fun revoke(
        id: String,
        now: Long,
    ): Int =
        transaction(db) {
            AuthSessions.update({ AuthSessions.id eq id }) {
                it[AuthSessions.revokedAt] = now
            }
        }

    /**
     * Atomically supersedes an active session with a successor row. Returns
     * false when [sessionId] was already replaced or revoked (e.g. a
     * concurrent refresh won the race).
     */
    fun rotateIfActive(
        sessionId: String,
        successorId: String,
        userId: Long,
        deviceLabel: String?,
        refreshHash: String,
        expiresAt: Long,
        now: Long,
    ): Boolean =
        transaction(db) {
            val updated =
                AuthSessions.update({
                    (AuthSessions.id eq sessionId) and
                        AuthSessions.replacedBy.isNull() and
                        AuthSessions.revokedAt.isNull()
                }) {
                    it[AuthSessions.replacedBy] = successorId
                    it[AuthSessions.lastUsedAt] = now
                }
            if (updated == 0) return@transaction false
            AuthSessions.insert {
                it[AuthSessions.id] = successorId
                it[AuthSessions.userId] = userId
                it[AuthSessions.deviceLabel] = deviceLabel
                it[AuthSessions.refreshHash] = refreshHash
                it[AuthSessions.lastUsedAt] = now
                it[AuthSessions.expiresAt] = expiresAt
                it[AuthSessions.createdAt] = now
            }
            true
        }

    /**
     * Revokes every still-active session for a user. Called on refresh-token
     * reuse, when the whole chain is assumed compromised.
     */
    fun revokeAllForUser(
        userId: Long,
        now: Long,
    ): Int =
        transaction(db) {
            AuthSessions.update({
                (AuthSessions.userId eq userId) and AuthSessions.revokedAt.isNull()
            }) {
                it[AuthSessions.revokedAt] = now
            }
        }

    private fun rowToSession(row: org.jetbrains.exposed.v1.core.ResultRow): AuthSessionRow =
        AuthSessionRow(
            id = row[AuthSessions.id],
            userId = row[AuthSessions.userId],
            deviceLabel = row[AuthSessions.deviceLabel],
            refreshHash = row[AuthSessions.refreshHash],
            replacedBy = row[AuthSessions.replacedBy],
            lastUsedAt = row[AuthSessions.lastUsedAt],
            expiresAt = row[AuthSessions.expiresAt],
            revokedAt = row[AuthSessions.revokedAt],
            createdAt = row[AuthSessions.createdAt],
        )
}
