package com.example.infrastructure.sqlite

import com.example.domain.profile.ProfilePort
import com.example.domain.profile.ProfileState
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

class ProfileRepository(
    private val db: Database,
) : ProfilePort {
    override suspend fun upsert(
        userId: Long,
        embeddingVersion: String,
        lastAppliedEventId: Long,
    ) {
        sqliteTransaction(db) {
            UserProfileState.upsert {
                it[UserProfileState.userId] = userId
                it[UserProfileState.assignedEmbeddingVersion] = embeddingVersion
                it[UserProfileState.lastAppliedEventId] = lastAppliedEventId
                it[UserProfileState.updatedAt] = Instant.now().toEpochMilli()
            }
        }
    }

    override suspend fun load(userId: Long): ProfileState? =
        sqliteTransaction(db) {
            UserProfileState
                .selectAll()
                .where { UserProfileState.userId eq userId }
                .singleOrNull()
                ?.let { row ->
                    ProfileState(
                        userId = row[UserProfileState.userId],
                        embeddingVersion = row[UserProfileState.assignedEmbeddingVersion],
                        lastAppliedEventId = row[UserProfileState.lastAppliedEventId],
                        updatedAt = row[UserProfileState.updatedAt],
                    )
                }
        }

    override suspend fun findStaleVersions(activeVersion: String): List<Long> =
        sqliteTransaction(db) {
            UserProfileState
                .selectAll()
                .where { UserProfileState.assignedEmbeddingVersion neq activeVersion }
                .orderBy(UserProfileState.updatedAt to SortOrder.ASC)
                .map { it[UserProfileState.userId] }
        }

    override suspend fun loadAllUserIds(): List<Long> =
        sqliteTransaction(db) {
            UserProfileState
                .selectAll()
                .map { it[UserProfileState.userId] }
        }
}
