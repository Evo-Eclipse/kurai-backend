package com.example.infrastructure.sqlite

import com.example.domain.auth.AuthSession
import com.example.domain.auth.AuthSessionPort
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

class AuthSessionRepository(
    private val db: Database,
) : AuthSessionPort {
    override fun findById(id: String): AuthSession? =
        transaction(db) {
            AuthSessions
                .selectAll()
                .where { AuthSessions.id eq id }
                .firstOrNull()
                ?.let(::rowToSession)
        }

    override fun insert(
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

    override fun revoke(
        id: String,
        now: Long,
    ): Int =
        transaction(db) {
            AuthSessions.update({ AuthSessions.id eq id }) {
                it[AuthSessions.revokedAt] = now
            }
        }

    override fun rotateIfActive(
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

    override fun revokeAllForUser(
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

    override fun deleteExpiredBefore(cutoff: Long): Int =
        transaction(db) {
            AuthSessions.deleteWhere { AuthSessions.expiresAt less cutoff }
        }

    private fun rowToSession(row: org.jetbrains.exposed.v1.core.ResultRow): AuthSession =
        AuthSession(
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
