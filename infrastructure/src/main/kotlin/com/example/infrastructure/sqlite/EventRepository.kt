package com.example.infrastructure.sqlite

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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** Current event-schema version stamped on every appended row. */
private const val EVENT_SCHEMA_VER = 1

class EventRepository(
    private val db: Database,
) : UserEventPort {
    override fun append(
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

    override fun loadSince(
        userId: Long,
        sinceEventId: Long,
    ): List<ResolvedUserEvent> =
        transaction(db) {
            joinedRows(userId, sinceEventId).map(::toResolvedEvent)
        }

    override fun loadPositiveSince(
        userId: Long,
        sinceEventId: Long,
    ): List<ResolvedUserEvent> =
        transaction(db) {
            joinedRows(userId, sinceEventId)
                .map(::toResolvedEvent)
                .filter { it.weight > 0f }
        }

    override fun maxEventId(userId: Long): Long =
        transaction(db) {
            UserEvents
                .selectAll()
                .where { UserEvents.userId eq userId }
                .maxByOrNull { it[UserEvents.id] }
                ?.get(UserEvents.id) ?: 0L
        }

    override fun appendBatch(events: List<PendingUserEvent>): List<Long> =
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
        val resolved = row.getOrNull(EventWeights.weight) ?: EventWeightRepository.DEFAULT_EVENT_WEIGHT
        return ResolvedUserEvent(
            userId = row[UserEvents.userId],
            itemId = row[UserEvents.itemId],
            sourceTag = row[UserEvents.sourceTag],
            weight = resolved.toFloat(),
            embeddingVersion = row[UserEvents.embeddingVersion],
        )
    }
}
