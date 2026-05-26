package com.example.application.embedding

import com.example.domain.embedding.EmbedLookupPort
import com.example.domain.model.Prototype
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine

class CachingEmbeddingAdapter(
    private val lookupFromStore: EmbedLookupPort,
    private val onHit: (itemId: Long) -> Unit = {},
    private val onMiss: (itemId: Long) -> Unit = {},
    maxCacheBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val cache: Cache<Long, FloatArray> =
        Caffeine
            .newBuilder()
            .maximumWeight(maxCacheBytes)
            .weigher<Long, FloatArray> { _, v -> v.size * 4 }
            .build()

    suspend fun lookupVectors(itemIds: List<Long>): Map<Long, FloatArray> {
        val result = mutableMapOf<Long, FloatArray>()
        val misses = mutableListOf<Long>()

        for (id in itemIds) {
            val cached = cache.getIfPresent(id)
            if (cached != null) {
                result[id] = cached
                onHit(id)
            } else {
                misses.add(id)
                onMiss(id)
            }
        }

        if (misses.isNotEmpty()) {
            val fetched = lookupFromStore(misses)
            for ((id, vec) in fetched) {
                cache.put(id, vec)
                result[id] = vec
            }
        }

        return result
    }

    internal fun cleanUp() = cache.cleanUp()

    internal val estimatedCacheSize: Long get() = cache.estimatedSize()

    companion object {
        const val VECTOR_BYTES = Prototype.VECTOR_DIM * 4
        const val DEFAULT_MAX_BYTES = 150L * 1024 * 1024 // ~50K entries at 768 dims
    }
}
