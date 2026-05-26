package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

data class EventData(
    val userId: Long,
    val itemId: Long,
    val weight: Float,
    val embeddingVersion: String,
)

class EventRepository(
    private val db: Database,
) {
    fun append(
        userId: Long,
        itemId: Long,
        weight: Float,
        embeddingVersion: String,
    ): Long =
        transaction(db) {
            UserEvents.insert {
                it[UserEvents.userId] = userId
                it[UserEvents.itemId] = itemId
                it[UserEvents.weight] = weight
                it[UserEvents.embeddingVersion] = embeddingVersion
            }[UserEvents.id]
        }

    fun loadSince(
        userId: Long,
        sinceEventId: Long,
    ): List<EventData> =
        transaction(db) {
            UserEvents
                .selectAll()
                .where { (UserEvents.userId eq userId) and (UserEvents.id greater sinceEventId) }
                .orderBy(UserEvents.id to SortOrder.ASC)
                .map { row ->
                    EventData(
                        userId = row[UserEvents.userId],
                        itemId = row[UserEvents.itemId],
                        weight = row[UserEvents.weight],
                        embeddingVersion = row[UserEvents.embeddingVersion],
                    )
                }
        }

    fun appendBatch(events: List<EventData>): List<Long> =
        transaction(db) {
            UserEvents
                .batchInsert(events) { e ->
                    this[UserEvents.userId] = e.userId
                    this[UserEvents.itemId] = e.itemId
                    this[UserEvents.weight] = e.weight
                    this[UserEvents.embeddingVersion] = e.embeddingVersion
                }.map { it[UserEvents.id] }
        }
}
