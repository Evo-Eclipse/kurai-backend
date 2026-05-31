package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

data class LoginChallengeRow(
    val id: String,
    val email: String,
    val codeHash: String,
    val expiresAt: Long,
    val consumedAt: Long?,
    val createdAt: Long,
)

class LoginChallengeRepository(
    private val db: Database,
) {
    fun insert(
        id: String,
        email: String,
        codeHash: String,
        expiresAt: Long,
        now: Long,
    ) {
        transaction(db) {
            LoginChallenges.insert {
                it[LoginChallenges.id] = id
                it[LoginChallenges.email] = email
                it[LoginChallenges.codeHash] = codeHash
                it[LoginChallenges.expiresAt] = expiresAt
                it[LoginChallenges.createdAt] = now
            }
        }
    }

    fun findById(id: String): LoginChallengeRow? =
        transaction(db) {
            LoginChallenges
                .selectAll()
                .where { LoginChallenges.id eq id }
                .firstOrNull()
                ?.let {
                    LoginChallengeRow(
                        id = it[LoginChallenges.id],
                        email = it[LoginChallenges.email],
                        codeHash = it[LoginChallenges.codeHash],
                        expiresAt = it[LoginChallenges.expiresAt],
                        consumedAt = it[LoginChallenges.consumedAt],
                        createdAt = it[LoginChallenges.createdAt],
                    )
                }
        }

    /**
     * Atomically marks the challenge as consumed if and only if it was
     * still pending. Returns the number of rows updated (0 if already
     * consumed or missing) so callers can distinguish a race.
     */
    fun markConsumedIfPending(
        id: String,
        now: Long,
    ): Int =
        transaction(db) {
            LoginChallenges.update({
                (LoginChallenges.id eq id) and LoginChallenges.consumedAt.isNull()
            }) {
                it[LoginChallenges.consumedAt] = now
            }
        }

    /**
     * Counts non-expired, non-consumed challenges for an e-mail issued
     * since [sinceMillis]. Powers the per-email rate-limit on
     * `POST /auth/challenge`.
     */
    fun countActiveSince(
        email: String,
        sinceMillis: Long,
    ): Long =
        transaction(db) {
            LoginChallenges
                .selectAll()
                .where {
                    (LoginChallenges.email eq email) and
                        (LoginChallenges.createdAt greaterEq sinceMillis)
                }.count()
        }
}
