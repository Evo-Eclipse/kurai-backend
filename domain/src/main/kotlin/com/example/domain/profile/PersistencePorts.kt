package com.example.domain.profile

interface ProfilePort {
    fun upsert(
        userId: Long,
        embeddingVersion: String,
        lastAppliedEventId: Long,
    )

    fun load(userId: Long): ProfileState?

    fun findStaleVersions(activeVersion: String): List<Long>

    fun loadAllUserIds(): List<Long>
}

interface UserEventPort {
    fun append(
        userId: Long,
        itemId: Long,
        sourceTag: String,
        embeddingVersion: String,
    ): Long

    fun loadSince(
        userId: Long,
        sinceEventId: Long,
    ): List<ResolvedUserEvent>

    fun loadPositiveSince(
        userId: Long,
        sinceEventId: Long,
    ): List<ResolvedUserEvent>

    fun maxEventId(userId: Long): Long

    fun appendBatch(events: List<PendingUserEvent>): List<Long>
}

interface PrototypePort {
    fun load(userId: Long): List<StoredPrototype>

    fun replaceAll(
        userId: Long,
        rows: List<StoredPrototype>,
    )
}
