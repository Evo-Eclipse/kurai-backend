package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

data class ProfileRow(
    val userId: Long,
    val embeddingVersion: String,
    val lastAppliedEventId: Long,
    val updatedAt: Long,
)

class ProfileRepository(
    private val db: Database,
) {
    fun upsert(
        userId: Long,
        embeddingVersion: String,
        lastAppliedEventId: Long,
    ) {
        transaction(db) {
            UserProfileState.upsert {
                it[UserProfileState.userId] = userId
                it[UserProfileState.assignedEmbeddingVersion] = embeddingVersion
                it[UserProfileState.lastAppliedEventId] = lastAppliedEventId
                it[UserProfileState.updatedAt] = Instant.now().toEpochMilli()
            }
        }
    }

    fun load(userId: Long): ProfileRow? =
        transaction(db) {
            UserProfileState
                .selectAll()
                .where { UserProfileState.userId eq userId }
                .singleOrNull()
                ?.let { row ->
                    ProfileRow(
                        userId = row[UserProfileState.userId],
                        embeddingVersion = row[UserProfileState.assignedEmbeddingVersion],
                        lastAppliedEventId = row[UserProfileState.lastAppliedEventId],
                        updatedAt = row[UserProfileState.updatedAt],
                    )
                }
        }

    fun findStaleVersions(activeVersion: String): List<Long> =
        transaction(db) {
            UserProfileState
                .selectAll()
                .where { UserProfileState.assignedEmbeddingVersion neq activeVersion }
                .orderBy(UserProfileState.updatedAt to SortOrder.ASC)
                .map { it[UserProfileState.userId] }
        }

    fun loadAllUserIds(): List<Long> =
        transaction(db) {
            UserProfileState
                .selectAll()
                .map { it[UserProfileState.userId] }
        }
}
