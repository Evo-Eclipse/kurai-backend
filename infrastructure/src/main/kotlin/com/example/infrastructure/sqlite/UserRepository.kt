package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

data class UserRow(
    val id: Long,
    val email: String?,
    val emailVerifiedAt: Long?,
    val emailKind: String?,
    val createdAt: Long,
    val lastSeenAt: Long,
    val deletedAt: Long?,
)

class UserRepository(
    private val db: Database,
) {
    fun findByEmail(email: String): UserRow? =
        transaction(db) {
            Users
                .selectAll()
                .where { Users.email eq email }
                .firstOrNull()
                ?.let(::rowToUser)
        }

    fun findById(id: Long): UserRow? =
        transaction(db) {
            Users
                .selectAll()
                .where { Users.id eq id }
                .firstOrNull()
                ?.let(::rowToUser)
        }

    /**
     * Insert a new e-mail-only user and return the generated id.
     * Sets `email_verified_at = now` because callers reach this path
     * only after a successful magic-link / OTP verification.
     */
    fun insertVerifiedEmail(
        email: String,
        emailKind: String,
        now: Long,
    ): Long =
        transaction(db) {
            Users.insert {
                it[Users.email] = email
                it[Users.emailVerifiedAt] = now
                it[Users.emailKind] = emailKind
                it[Users.createdAt] = now
                it[Users.lastSeenAt] = now
            } get Users.id
        }

    /**
     * Insert a user with no e-mail and return the generated id. Used by the
     * `key` flow, where the opaque key is the only credential and there is
     * no verified address to record.
     */
    fun insertAnonymous(now: Long): Long =
        transaction(db) {
            Users.insert {
                it[Users.createdAt] = now
                it[Users.lastSeenAt] = now
            } get Users.id
        }

    fun touchLastSeen(
        userId: Long,
        now: Long,
    ) {
        transaction(db) {
            Users.update({ Users.id eq userId }) {
                it[Users.lastSeenAt] = now
            }
        }
    }

    private fun rowToUser(row: org.jetbrains.exposed.v1.core.ResultRow): UserRow =
        UserRow(
            id = row[Users.id],
            email = row[Users.email],
            emailVerifiedAt = row[Users.emailVerifiedAt],
            emailKind = row[Users.emailKind],
            createdAt = row[Users.createdAt],
            lastSeenAt = row[Users.lastSeenAt],
            deletedAt = row[Users.deletedAt],
        )
}
