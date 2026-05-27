package com.example.domain.model

data class UserEvent(
    val id: Long,
    val userId: Long,
    val itemId: Long,
    val weight: Float,
    val embeddingVersion: EmbeddingVersion,
    val ts: Long,
) {
    init {
        require(weight in -1.0f..1.0f) {
            "UserEvent weight must be in [-1.0, 1.0], got $weight"
        }
    }
}
