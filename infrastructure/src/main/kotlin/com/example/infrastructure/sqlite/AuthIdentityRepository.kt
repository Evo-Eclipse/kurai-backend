package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

data class AuthIdentityRow(
    val id: Long,
    val userId: Long,
    val provider: String,
    val providerSubject: String,
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
}
