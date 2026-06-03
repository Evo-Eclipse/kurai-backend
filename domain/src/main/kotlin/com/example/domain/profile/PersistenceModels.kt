package com.example.domain.profile

/** Persisted profile checkpoint for a user. */
data class ProfileState(
    val userId: Long,
    val embeddingVersion: String,
    val lastAppliedEventId: Long,
    val updatedAt: Long,
)

/** Write shape: raw event as stored (opaque tag, no resolved weight). */
data class PendingUserEvent(
    val userId: Long,
    val itemId: Long,
    val sourceTag: String,
    val embeddingVersion: String,
)

/** Read shape: event with weight resolved from the weight dictionary. */
data class ResolvedUserEvent(
    val userId: Long,
    val itemId: Long,
    val sourceTag: String,
    val weight: Float,
    val embeddingVersion: String,
)

/** Prototype row as stored for a user. */
data class StoredPrototype(
    val prototypeType: String,
    val vector: FloatArray,
    val weight: Double,
    val embeddingVersion: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredPrototype) return false
        return prototypeType == other.prototypeType &&
            vector.contentEquals(other.vector) &&
            weight == other.weight &&
            embeddingVersion == other.embeddingVersion
    }

    override fun hashCode(): Int {
        var result = prototypeType.hashCode()
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + weight.hashCode()
        result = 31 * result + embeddingVersion.hashCode()
        return result
    }
}
