package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** Write shape: the raw event as stored — an opaque tag, no resolved weight. */
data class EventData(
    val userId: Long,
    val itemId: Long,
    val sourceTag: String,
    val embeddingVersion: String,
)

/** Read shape: an event with its weight resolved from `event_weights`. */
data class ResolvedEvent(
    val userId: Long,
    val itemId: Long,
    val sourceTag: String,
    val weight: Float,
    val embeddingVersion: String,
)

/** Current event-schema version stamped on every appended row. */
private const val EVENT_SCHEMA_VER = 1

class EventRepository(
    private val db: Database,
) {
    fun append(
        userId: Long,
        itemId: Long,
        sourceTag: String,
        embeddingVersion: String,
    ): Long =
        transaction(db) {
            UserEvents.insert {
                it[UserEvents.userId] = userId
                it[UserEvents.itemId] = itemId
                it[UserEvents.sourceTag] = sourceTag
                it[UserEvents.embeddingVersion] = embeddingVersion
                it[schemaVer] = EVENT_SCHEMA_VER
            }[UserEvents.id]
        }

    fun loadSince(
        userId: Long,
        sinceEventId: Long,
    ): List<ResolvedEvent> =
        transaction(db) {
            joinedRows(userId, sinceEventId).map(::toResolvedEvent)
        }

    fun loadPositiveSince(
        userId: Long,
        sinceEventId: Long,
    ): List<ResolvedEvent> =
        transaction(db) {
            joinedRows(userId, sinceEventId)
                .map(::toResolvedEvent)
                .filter { it.weight > 0f }
        }

    fun maxEventId(userId: Long): Long =
        transaction(db) {
            UserEvents
                .selectAll()
                .where { UserEvents.userId eq userId }
                .maxByOrNull { it[UserEvents.id] }
                ?.get(UserEvents.id) ?: 0L
        }

    fun appendBatch(events: List<EventData>): List<Long> =
        transaction(db) {
            UserEvents
                .batchInsert(events) { e ->
                    this[UserEvents.userId] = e.userId
                    this[UserEvents.itemId] = e.itemId
                    this[UserEvents.sourceTag] = e.sourceTag
                    this[UserEvents.embeddingVersion] = e.embeddingVersion
                    this[UserEvents.schemaVer] = EVENT_SCHEMA_VER
                }.map { it[UserEvents.id] }
        }

    /**
     * Left-joins events to the weight dictionary so the weight is resolved
     * live: a backfilled `event_weights` row changes the result on the next
     * read, and an unknown tag falls back to [EventWeightRepository.DEFAULT_EVENT_WEIGHT].
     */
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

    private fun toResolvedEvent(row: org.jetbrains.exposed.v1.core.ResultRow): ResolvedEvent {
        val resolved = row.getOrNull(EventWeights.weight) ?: EventWeightRepository.DEFAULT_EVENT_WEIGHT
        return ResolvedEvent(
            userId = row[UserEvents.userId],
            itemId = row[UserEvents.itemId],
            sourceTag = row[UserEvents.sourceTag],
            weight = resolved.toFloat(),
            embeddingVersion = row[UserEvents.embeddingVersion],
        )
    }
}
