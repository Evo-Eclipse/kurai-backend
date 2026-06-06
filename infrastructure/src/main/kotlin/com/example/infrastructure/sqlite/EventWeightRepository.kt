package com.example.infrastructure.sqlite

import com.example.domain.events.EventWeightPort
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** SQLite-backed [EventWeightPort] over the `event_weights` table. */
class EventWeightRepository(
    private val db: Database,
) : EventWeightPort {
    override suspend fun resolve(sourceTag: String): Double? =
        sqliteTransaction(db) {
            EventWeights
                .selectAll()
                .where { EventWeights.sourceTag eq sourceTag }
                .firstOrNull()
                ?.get(EventWeights.weight)
        }

    override suspend fun upsert(
        sourceTag: String,
        weight: Double,
        now: Long,
    ) {
        sqliteTransaction(db) {
            val updated =
                EventWeights.update({ EventWeights.sourceTag eq sourceTag }) {
                    it[EventWeights.weight] = weight
                    it[updatedAt] = now
                }
            if (updated == 0) {
                EventWeights.insert {
                    it[EventWeights.sourceTag] = sourceTag
                    it[EventWeights.weight] = weight
                    it[updatedAt] = now
                }
            }
        }
    }
}
