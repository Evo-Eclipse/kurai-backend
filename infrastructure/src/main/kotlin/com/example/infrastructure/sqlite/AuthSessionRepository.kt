package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.core.eq
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

    private fun rowToSession(row: org.jetbrains.exposed.v1.core.ResultRow): AuthSessionRow =
        AuthSessionRow(
            id = row[AuthSessions.id],
            userId = row[AuthSessions.userId],
            deviceLabel = row[AuthSessions.deviceLabel],
            refreshHash = row[AuthSessions.refreshHash],
            expiresAt = row[AuthSessions.expiresAt],
            revokedAt = row[AuthSessions.revokedAt],
            createdAt = row[AuthSessions.createdAt],
        )
}
