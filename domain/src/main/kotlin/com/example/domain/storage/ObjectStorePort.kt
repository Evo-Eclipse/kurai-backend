package com.example.domain.storage

sealed interface GetResult {
    data class Found(
        val bytes: ByteArray,
    ) : GetResult {
        override fun equals(other: Any?): Boolean = other is Found && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data object NotFound : GetResult
}

interface ObjectStorePort {
    suspend fun put(
        key: String,
        bytes: ByteArray,
    )

    suspend fun get(key: String): GetResult
}
