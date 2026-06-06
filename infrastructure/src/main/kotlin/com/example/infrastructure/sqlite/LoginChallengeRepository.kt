package com.example.infrastructure.sqlite

import com.example.domain.auth.LoginChallenge
import com.example.domain.auth.LoginChallengePort
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

class LoginChallengeRepository(
    private val db: Database,
) : LoginChallengePort {
    override suspend fun insert(
        id: String,
        email: String,
        codeHash: String,
        expiresAt: Long,
        now: Long,
    ) {
        sqliteTransaction(db) {
            LoginChallenges.insert {
                it[LoginChallenges.id] = id
                it[LoginChallenges.email] = email
                it[LoginChallenges.codeHash] = codeHash
                it[LoginChallenges.expiresAt] = expiresAt
                it[LoginChallenges.createdAt] = now
            }
        }
    }

    override suspend fun findById(id: String): LoginChallenge? =
        sqliteTransaction(db) {
            LoginChallenges
                .selectAll()
                .where { LoginChallenges.id eq id }
                .firstOrNull()
                ?.let {
                    LoginChallenge(
                        id = it[LoginChallenges.id],
                        email = it[LoginChallenges.email],
                        codeHash = it[LoginChallenges.codeHash],
                        expiresAt = it[LoginChallenges.expiresAt],
                        consumedAt = it[LoginChallenges.consumedAt],
                        attempts = it[LoginChallenges.attempts],
                        createdAt = it[LoginChallenges.createdAt],
                    )
                }
        }

    override suspend fun incrementAttempts(id: String): Int =
        sqliteTransaction(db) {
            LoginChallenges.update({ LoginChallenges.id eq id }) {
                it[LoginChallenges.attempts] = LoginChallenges.attempts + 1
            }
        }

    override suspend fun markConsumedIfPending(
        id: String,
        now: Long,
    ): Int =
        sqliteTransaction(db) {
            LoginChallenges.update({
                (LoginChallenges.id eq id) and LoginChallenges.consumedAt.isNull()
            }) {
                it[LoginChallenges.consumedAt] = now
            }
        }

    override suspend fun countCreatedSince(
        email: String,
        sinceMillis: Long,
    ): Long =
        sqliteTransaction(db) {
            LoginChallenges
                .selectAll()
                .where {
                    (LoginChallenges.email eq email) and
                        (LoginChallenges.createdAt greaterEq sinceMillis)
                }.count()
        }
}
