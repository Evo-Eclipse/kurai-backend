package com.example.application.embedding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CachingEmbeddingAdapterTest {
    private fun vec(seed: Int): FloatArray = FloatArray(768) { (it + seed).toFloat() }

    @Test
    fun `cold lookup triggers onMiss not onHit`() {
        var hits = 0
        var misses = 0
        val adapter =
            CachingEmbeddingAdapter(
                lookupFromStore = { ids -> ids.associateWith { vec(it.toInt()) } },
                onHit = { hits++ },
                onMiss = { misses++ },
            )
        adapter.lookupVectors(listOf(1L))
        assertEquals(0, hits)
        assertEquals(1, misses)
    }

    @Test
    fun `repeat lookup triggers onHit and does not call store again`() {
        var storeCalls = 0
        var hits = 0
        val adapter =
            CachingEmbeddingAdapter(
                lookupFromStore = { ids ->
                    storeCalls++
                    ids.associateWith { vec(it.toInt()) }
                },
                onHit = { hits++ },
            )
        adapter.lookupVectors(listOf(1L))
        adapter.lookupVectors(listOf(1L))
        assertEquals(1, storeCalls, "Store should only be called once")
        assertEquals(1, hits)
    }

    @Test
    fun `weight-based eviction keeps cache within byte budget`() {
        val entryBudget = 10
        val maxBytes = CachingEmbeddingAdapter.VECTOR_BYTES.toLong() * entryBudget
        val adapter =
            CachingEmbeddingAdapter(
                lookupFromStore = { ids -> ids.associateWith { vec(it.toInt()) } },
                maxCacheBytes = maxBytes,
            )
        for (id in 1L..30L) {
            adapter.lookupVectors(listOf(id))
        }
        adapter.cleanUp()
        // Caffeine may retain slightly more than the budget during cleanup windows,
        // but should stay well within 2x.
        assertTrue(
            adapter.estimatedCacheSize <= entryBudget * 2,
            "Cache should stay near budget, got ${adapter.estimatedCacheSize} entries",
        )
    }

    @Test
    fun `batch lookup with partial cache hit calls store only for misses`() {
        var capturedMissIds: List<Long> = emptyList()
        val adapter =
            CachingEmbeddingAdapter(
                lookupFromStore = { ids ->
                    capturedMissIds = ids
                    ids.associateWith { vec(it.toInt()) }
                },
            )
        adapter.lookupVectors(listOf(1L))
        capturedMissIds = emptyList()
        adapter.lookupVectors(listOf(1L, 2L, 3L))
        assertEquals(listOf(2L, 3L), capturedMissIds.sorted(), "Store called only for cache misses")
    }
}
