package com.example.infrastructure.sqlite

import com.example.domain.auth.UserPort
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
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
) : UserPort {
    suspend fun findById(id: Long): UserRow? =
        sqliteTransaction(db) {
            Users
                .selectAll()
                .where { Users.id eq id }
                .firstOrNull()
                ?.let(::rowToUser)
        }

    override suspend fun insertVerifiedEmail(
        email: String,
        emailKind: String,
        now: Long,
    ): Long =
        sqliteTransaction(db) {
            Users.insert {
                it[Users.email] = email
                it[Users.emailVerifiedAt] = now
                it[Users.emailKind] = emailKind
                it[Users.createdAt] = now
                it[Users.lastSeenAt] = now
            } get Users.id
        }

    override suspend fun insertAnonymous(now: Long): Long =
        sqliteTransaction(db) {
            Users.insert {
                it[Users.createdAt] = now
                it[Users.lastSeenAt] = now
            } get Users.id
        }

    override suspend fun touchLastSeen(
        userId: Long,
        now: Long,
    ) {
        sqliteTransaction(db) {
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
