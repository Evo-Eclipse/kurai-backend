package com.example.domain.events

import com.example.domain.model.EmbeddingVersion

/**
 * A raw behavioral signal as it arrives from the client: an opaque
 * [sourceTag] plus the item it refers to and the embedding version active
 * at the time. The server stores this as-is and never interprets the tag;
 * the numeric weight is resolved later from the `event_weights` dictionary.
 *
 * The scorable [com.example.domain.model.UserEvent] (with a resolved
 * weight) is derived from a raw event on read, so a backfilled weight takes
 * effect on the next profile recompute.
 */
data class RawEvent(
    val userId: Long,
    val itemId: Long,
    val sourceTag: String,
    val embeddingVersion: EmbeddingVersion,
)
