package com.example.domain.profile

interface ProfilePort {
    suspend fun upsert(
        userId: Long,
        embeddingVersion: String,
        lastAppliedEventId: Long,
    )

    suspend fun load(userId: Long): ProfileState?

    suspend fun findStaleVersions(activeVersion: String): List<Long>

    suspend fun loadAllUserIds(): List<Long>
}

interface UserEventPort {
    suspend fun append(
        userId: Long,
        itemId: Long,
        sourceTag: String,
        embeddingVersion: String,
    ): Long

    suspend fun loadSince(
        userId: Long,
        sinceEventId: Long,
    ): List<ResolvedUserEvent>

    suspend fun loadPositiveSince(
        userId: Long,
        sinceEventId: Long,
    ): List<ResolvedUserEvent>

    suspend fun maxEventId(userId: Long): Long

    suspend fun appendBatch(events: List<PendingUserEvent>): List<Long>
}

interface PrototypePort {
    suspend fun load(userId: Long): List<StoredPrototype>

    suspend fun replaceAll(
        userId: Long,
        rows: List<StoredPrototype>,
    )
}
