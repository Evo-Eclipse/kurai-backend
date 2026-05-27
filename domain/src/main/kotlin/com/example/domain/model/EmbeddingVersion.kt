package com.example.domain.model

@JvmInline
value class EmbeddingVersion(
    val value: String,
) {
    init {
        require(value.isNotBlank()) {
            "EmbeddingVersion must not be blank"
        }
    }
}
