package com.example.domain.model

data class UserProfile(
    val userId: Long,
    val embeddingVersion: EmbeddingVersion,
    val positivePrototypes: List<Prototype>,
    val negativePrototypes: List<Prototype>,
    val sessionVector: FloatArray,
    val longTermVector: FloatArray,
    val lastAppliedEventId: Long,
) {
    init {
        require(sessionVector.size == Prototype.VECTOR_DIM) {
            "sessionVector must be ${Prototype.VECTOR_DIM}-dimensional, got ${sessionVector.size}"
        }
        require(longTermVector.size == Prototype.VECTOR_DIM) {
            "longTermVector must be ${Prototype.VECTOR_DIM}-dimensional, got ${longTermVector.size}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UserProfile) return false
        return userId == other.userId &&
            embeddingVersion == other.embeddingVersion &&
            positivePrototypes == other.positivePrototypes &&
            negativePrototypes == other.negativePrototypes &&
            sessionVector.contentEquals(other.sessionVector) &&
            longTermVector.contentEquals(other.longTermVector) &&
            lastAppliedEventId == other.lastAppliedEventId
    }

    override fun hashCode(): Int {
        var result = userId.hashCode()
        result = 31 * result + embeddingVersion.hashCode()
        result = 31 * result + positivePrototypes.hashCode()
        result = 31 * result + negativePrototypes.hashCode()
        result = 31 * result + sessionVector.contentHashCode()
        result = 31 * result + longTermVector.contentHashCode()
        result = 31 * result + lastAppliedEventId.hashCode()
        return result
    }

    companion object {
        fun coldStart(
            userId: Long,
            embeddingVersion: EmbeddingVersion = EmbeddingVersion("default"),
        ): UserProfile =
            UserProfile(
                userId = userId,
                embeddingVersion = embeddingVersion,
                positivePrototypes = emptyList(),
                negativePrototypes = emptyList(),
                sessionVector = FloatArray(Prototype.VECTOR_DIM),
                longTermVector = FloatArray(Prototype.VECTOR_DIM),
                lastAppliedEventId = 0L,
            )
    }
}
