package com.example.infrastructure.sqlite

import com.example.domain.events.EventWeightPort
import com.example.domain.profile.PendingUserEvent
import com.example.domain.profile.ResolvedUserEvent
import com.example.domain.profile.UserEventPort
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Current event-schema version stamped on every appended row. */
private const val EVENT_SCHEMA_VER = 1

class EventRepository(
    private val db: Database,
) : UserEventPort {
    override suspend fun append(
        userId: Long,
        itemId: Long,
        sourceTag: String,
        embeddingVersion: String,
    ): Long =
        sqliteTransaction(db) {
            UserEvents.insert {
                it[UserEvents.userId] = userId
                it[UserEvents.itemId] = itemId
                it[UserEvents.sourceTag] = sourceTag
                it[UserEvents.embeddingVersion] = embeddingVersion
                it[schemaVer] = EVENT_SCHEMA_VER
            }[UserEvents.id]
        }

    override suspend fun loadSince(
        userId: Long,
        sinceEventId: Long,
    ): List<ResolvedUserEvent> =
        sqliteTransaction(db) {
            joinedRows(userId, sinceEventId).map(::toResolvedEvent)
        }

    override suspend fun loadPositiveSince(
        userId: Long,
        sinceEventId: Long,
    ): List<ResolvedUserEvent> =
        sqliteTransaction(db) {
            joinedRows(userId, sinceEventId)
                .map(::toResolvedEvent)
                .filter { it.weight > 0f }
        }

    override suspend fun maxEventId(userId: Long): Long =
        sqliteTransaction(db) {
            UserEvents
                .selectAll()
                .where { UserEvents.userId eq userId }
                .maxByOrNull { it[UserEvents.id] }
                ?.get(UserEvents.id) ?: 0L
        }

    override suspend fun appendBatch(events: List<PendingUserEvent>): List<Long> =
        sqliteTransaction(db) {
            UserEvents
                .batchInsert(events) { e ->
                    this[UserEvents.userId] = e.userId
                    this[UserEvents.itemId] = e.itemId
                    this[UserEvents.sourceTag] = e.sourceTag
                    this[UserEvents.embeddingVersion] = e.embeddingVersion
                    this[UserEvents.schemaVer] = EVENT_SCHEMA_VER
                }.map { it[UserEvents.id] }
        }

    private fun joinedRows(
        userId: Long,
        sinceEventId: Long,
    ) = UserEvents
        .join(
            EventWeights,
            JoinType.LEFT,
            onColumn = UserEvents.sourceTag,
            otherColumn = EventWeights.sourceTag,
        ).selectAll()
        .where { (UserEvents.userId eq userId) and (UserEvents.id greater sinceEventId) }
        .orderBy(UserEvents.id to SortOrder.ASC)

    private fun toResolvedEvent(row: org.jetbrains.exposed.v1.core.ResultRow): ResolvedUserEvent {
        val resolved = row.getOrNull(EventWeights.weight) ?: EventWeightPort.DEFAULT_EVENT_WEIGHT
        return ResolvedUserEvent(
            userId = row[UserEvents.userId],
            itemId = row[UserEvents.itemId],
            sourceTag = row[UserEvents.sourceTag],
            weight = resolved.toFloat(),
            embeddingVersion = row[UserEvents.embeddingVersion],
        )
    }
}
