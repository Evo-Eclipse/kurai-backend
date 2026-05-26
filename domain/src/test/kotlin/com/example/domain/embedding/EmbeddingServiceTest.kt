package com.example.domain.embedding

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddingServiceTest {
    private fun vec(seed: Int): FloatArray = FloatArray(768) { (it + seed).toFloat() }

    @Test
    fun `cold lookup triggers onMiss not onHit`() {
        var hits = 0
        var misses = 0
        val service =
            EmbeddingService(
                lookupFromStore = { ids -> ids.associateWith { vec(it.toInt()) } },
                onHit = { hits++ },
                onMiss = { misses++ },
            )
        runBlocking { service.lookupVectors(listOf(1L)) }
        assertEquals(0, hits)
        assertEquals(1, misses)
    }

    @Test
    fun `repeat lookup triggers onHit and does not call store again`() {
        var storeCalls = 0
        var hits = 0
        val service =
            EmbeddingService(
                lookupFromStore = { ids ->
                    storeCalls++
                    ids.associateWith { vec(it.toInt()) }
                },
                onHit = { hits++ },
            )
        runBlocking {
            service.lookupVectors(listOf(1L))
            service.lookupVectors(listOf(1L))
        }
        assertEquals(1, storeCalls, "Store should only be called once")
        assertEquals(1, hits)
    }

    @Test
    fun `weight-based eviction keeps cache within byte budget`() {
        val entryBudget = 10
        val maxBytes = EmbeddingService.VECTOR_BYTES.toLong() * entryBudget
        val service =
            EmbeddingService(
                lookupFromStore = { ids -> ids.associateWith { vec(it.toInt()) } },
                maxCacheBytes = maxBytes,
            )
        runBlocking {
            for (id in 1L..30L) {
                service.lookupVectors(listOf(id))
            }
        }
        service.cleanUp()
        // Caffeine may retain slightly more than the budget during cleanup windows,
        // but should stay well within 2x.
        assertTrue(
            service.estimatedCacheSize <= entryBudget * 2,
            "Cache should stay near budget, got ${service.estimatedCacheSize} entries",
        )
    }

    @Test
    fun `batch lookup with partial cache hit calls store only for misses`() {
        var capturedMissIds: List<Long> = emptyList()
        val service =
            EmbeddingService(
                lookupFromStore = { ids ->
                    capturedMissIds = ids
                    ids.associateWith { vec(it.toInt()) }
                },
            )
        runBlocking {
            service.lookupVectors(listOf(1L))
            capturedMissIds = emptyList()
            service.lookupVectors(listOf(1L, 2L, 3L))
        }
        assertEquals(listOf(2L, 3L), capturedMissIds.sorted(), "Store called only for cache misses")
    }
}
