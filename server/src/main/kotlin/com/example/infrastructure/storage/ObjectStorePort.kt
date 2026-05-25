package com.example.infrastructure.storage

/**
 * Result of an [ObjectStorePort.get] call.
 *
 * Sealed because AGENTS.md mandates result-types over exceptions for control flow.
 */
sealed interface GetResult {
    data class Found(
        val bytes: ByteArray,
    ) : GetResult {
        override fun equals(other: Any?): Boolean = other is Found && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data object NotFound : GetResult
}

/**
 * Hexagonal port for blob storage.
 *
 * MVP backing impl is filesystem-local ([LocalObjectStore]). Spaces/S3 backed
 * impl is post-MVP — see `.kiro/specs/kurai-mvp-waves/design.md` Out-of-scope.
 *
 * `put` SHALL be atomic: a concurrent reader either sees the previous bytes
 * (or nothing) or the full new payload, never a partial write. Required by
 * the k-means worker's atomic-swap procedure for cluster centroids.
 */
interface ObjectStorePort {
    /**
     * Stores [bytes] under [key]. Atomic with respect to readers: write to a
     * temporary file then move into place.
     *
     * @throws IllegalArgumentException if [key] is empty, absolute, or contains `..` segments.
     */
    suspend fun put(
        key: String,
        bytes: ByteArray,
    )

    /**
     * Retrieves bytes previously stored under [key], or [GetResult.NotFound]
     * if no object exists for that key.
     */
    suspend fun get(key: String): GetResult
}
