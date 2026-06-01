package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * The `event_weights` enrichment dictionary: maps an opaque client
 * `source_tag` to a numeric weight. It is *enrichment, not constraint* — an
 * unknown tag is not rejected; it simply resolves to
 * [DEFAULT_EVENT_WEIGHT] until an operator assigns a weight, which then
 * takes effect on the next profile recompute.
 */
class EventWeightRepository(
    private val db: Database,
) {
    /** The stored weight for [sourceTag], or `null` if the tag is unknown. */
    fun resolve(sourceTag: String): Double? =
        transaction(db) {
            EventWeights
                .selectAll()
                .where { EventWeights.sourceTag eq sourceTag }
                .firstOrNull()
                ?.get(EventWeights.weight)
        }

    /** Operator action: set or update the weight for a tag. */
    fun upsert(
        sourceTag: String,
        weight: Double,
        now: Long,
    ) {
        transaction(db) {
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

    companion object {
        /** Weight of an unknown / unweighted tag: neutral, so it cannot move a profile. */
        const val DEFAULT_EVENT_WEIGHT: Double = 0.0
    }
}
