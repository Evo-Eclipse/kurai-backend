package com.example.infrastructure.sqlite

import com.example.domain.auth.AuthIdentity
import com.example.domain.auth.AuthIdentityPort
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

class AuthIdentityRepository(
    private val db: Database,
) : AuthIdentityPort {
    override suspend fun findBySubject(
        provider: String,
        providerSubject: String,
    ): AuthIdentity? =
        sqliteTransaction(db) {
            AuthIdentities
                .selectAll()
                .where {
                    (AuthIdentities.provider eq provider) and
                        (AuthIdentities.providerSubject eq providerSubject)
                }.firstOrNull()
                ?.let {
                    AuthIdentity(
                        id = it[AuthIdentities.id],
                        userId = it[AuthIdentities.userId],
                        provider = it[AuthIdentities.provider],
                        providerSubject = it[AuthIdentities.providerSubject],
                        disabledAt = it[AuthIdentities.disabledAt],
                        createdAt = it[AuthIdentities.createdAt],
                    )
                }
        }

    override suspend fun insert(
        userId: Long,
        provider: String,
        providerSubject: String,
        now: Long,
    ) {
        sqliteTransaction(db) {
            AuthIdentities.insert {
                it[AuthIdentities.userId] = userId
                it[AuthIdentities.provider] = provider
                it[AuthIdentities.providerSubject] = providerSubject
                it[AuthIdentities.createdAt] = now
            }
        }
    }

    override suspend fun disable(
        provider: String,
        providerSubject: String,
        now: Long,
    ): Int =
        sqliteTransaction(db) {
            AuthIdentities.update({
                (AuthIdentities.provider eq provider) and
                    (AuthIdentities.providerSubject eq providerSubject) and
                    AuthIdentities.disabledAt.isNull()
            }) {
                it[AuthIdentities.disabledAt] = now
            }
        }
}
