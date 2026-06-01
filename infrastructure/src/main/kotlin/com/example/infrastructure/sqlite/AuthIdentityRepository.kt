package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

data class AuthIdentityRow(
    val id: Long,
    val userId: Long,
    val provider: String,
    val providerSubject: String,
    val disabledAt: Long?,
    val createdAt: Long,
)

class AuthIdentityRepository(
    private val db: Database,
) {
    fun findBySubject(
        provider: String,
        providerSubject: String,
    ): AuthIdentityRow? =
        transaction(db) {
            AuthIdentities
                .selectAll()
                .where {
                    (AuthIdentities.provider eq provider) and
                        (AuthIdentities.providerSubject eq providerSubject)
                }.firstOrNull()
                ?.let {
                    AuthIdentityRow(
                        id = it[AuthIdentities.id],
                        userId = it[AuthIdentities.userId],
                        provider = it[AuthIdentities.provider],
                        providerSubject = it[AuthIdentities.providerSubject],
                        disabledAt = it[AuthIdentities.disabledAt],
                        createdAt = it[AuthIdentities.createdAt],
                    )
                }
        }

    fun insert(
        userId: Long,
        provider: String,
        providerSubject: String,
        now: Long,
    ) {
        transaction(db) {
            AuthIdentities.insert {
                it[AuthIdentities.userId] = userId
                it[AuthIdentities.provider] = provider
                it[AuthIdentities.providerSubject] = providerSubject
                it[AuthIdentities.createdAt] = now
            }
        }
    }

    /**
     * Retires an identity by stamping `disabled_at` (only if not already
     * set). Used to turn off a compromised or banned `key`.
     */
    fun disable(
        provider: String,
        providerSubject: String,
        now: Long,
    ): Int =
        transaction(db) {
            AuthIdentities.update({
                (AuthIdentities.provider eq provider) and
                    (AuthIdentities.providerSubject eq providerSubject) and
                    AuthIdentities.disabledAt.isNull()
            }) {
                it[AuthIdentities.disabledAt] = now
            }
        }
}
