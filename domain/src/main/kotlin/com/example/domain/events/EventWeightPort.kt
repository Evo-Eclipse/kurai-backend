package com.example.domain.events

/**
 * The `event_weights` enrichment dictionary: maps an opaque client
 * `source_tag` to a numeric weight.
 *
 * Enrichment, not constraint -- an unknown tag is never rejected; it
 * resolves to [DEFAULT_EVENT_WEIGHT] until an operator assigns a weight,
 * which then takes effect on the next profile recompute. No FK ties
 * `user_events.source_tag` to this table.
 */
interface EventWeightPort {
    /** The stored weight for [sourceTag], or `null` if the tag is unknown. */
    suspend fun resolve(sourceTag: String): Double?

    /** Operator action: set or update the weight for a tag. */
    suspend fun upsert(
        sourceTag: String,
        weight: Double,
        now: Long,
    )

    companion object {
        /** Weight of an unknown / unweighted tag: neutral, so it cannot move a profile. */
        const val DEFAULT_EVENT_WEIGHT: Double = 0.0
    }
}
