package com.example.domain.profile

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MmrTest {
    private val rng = Random(0xBEEF)

    private fun randomVec(dim: Int = 8): FloatArray = Scoring.l2Normalize(FloatArray(dim) { rng.nextFloat() * 2f - 1f })

    @Test
    fun `lambda=1 returns items sorted by score`() {
        val items = (1L..10L).map { id -> id to rng.nextFloat() }
        val embeddings = items.associate { (id, _) -> id to randomVec() }
        val expected = items.sortedByDescending { it.second }.map { it.first }.take(5)
        val result = items.mmr(embeddings, lambda = 1.0f, n = 5)
        assertEquals(expected, result)
    }

    @Test
    fun `lambda=0 picks from different clusters first`() {
        val clusterA = FloatArray(8).also { it[0] = 1f }
        val clusterB = FloatArray(8).also { it[1] = 1f }
        val items =
            listOf(
                1L to 0.9f, // near cluster A
                2L to 0.8f, // near cluster A
                3L to 0.7f, // near cluster B
                4L to 0.6f, // near cluster B
            )
        val noise = 0.01f
        val embeddings =
            mapOf(
                1L to Scoring.l2Normalize(FloatArray(8) { i -> clusterA[i] + noise }),
                2L to Scoring.l2Normalize(FloatArray(8) { i -> clusterA[i] + noise * 2 }),
                3L to Scoring.l2Normalize(FloatArray(8) { i -> clusterB[i] + noise }),
                4L to Scoring.l2Normalize(FloatArray(8) { i -> clusterB[i] + noise * 2 }),
            )
        val result = items.mmr(embeddings, lambda = 0.0f, n = 2)
        // First two picks should be from different clusters (one near A, one near B)
        val firstIsA = Scoring.cos(embeddings[result[0]]!!, clusterA) > 0.9f
        val secondIsB = Scoring.cos(embeddings[result[1]]!!, clusterB) > 0.9f
        val firstIsB = Scoring.cos(embeddings[result[0]]!!, clusterB) > 0.9f
        val secondIsA = Scoring.cos(embeddings[result[1]]!!, clusterA) > 0.9f
        assertTrue(
            (firstIsA && secondIsB) || (firstIsB && secondIsA),
            "First two picks should be from different clusters, got $result",
        )
    }

    @Test
    fun `lambda=0_5 avoids near-duplicate when alternatives exist`() {
        val base = FloatArray(8).also { it[0] = 1f }
        val duplicate = Scoring.l2Normalize(FloatArray(8) { i -> base[i] + 0.001f })
        val diverse = FloatArray(8).also { it[1] = 1f }
        val items =
            listOf(
                1L to 1.0f, // base — will be picked first
                2L to 0.95f, // near-duplicate of base
                3L to 0.9f, // diverse
            )
        val embeddings = mapOf(1L to base, 2L to duplicate, 3L to diverse)
        val result = items.mmr(embeddings, lambda = 0.5f, n = 2)
        // Item 1 picked first; item 3 (diverse) preferred over item 2 (duplicate)
        assertEquals(1L, result[0])
        assertEquals(3L, result[1], "Diverse item should beat near-duplicate at lambda=0.5")
    }

    @Test
    fun `empty input returns empty list`() {
        val result = emptyList<Pair<Long, Float>>().mmr(emptyMap(), lambda = 0.5f, n = 5)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `n greater than size returns all items`() {
        val items = (1L..3L).map { id -> id to rng.nextFloat() }
        val embeddings = items.associate { (id, _) -> id to randomVec() }
        val result = items.mmr(embeddings, lambda = 0.5f, n = 10)
        assertEquals(3, result.size)
    }

    @Test
    fun `missing embedding treated as zero diversity penalty`() {
        val items = listOf(1L to 1.0f, 2L to 0.9f, 3L to 0.8f)
        // No embeddings provided at all
        val result = items.mmr(emptyMap(), lambda = 0.5f, n = 2)
        // With no embeddings, diversity is always 0, so score order wins
        assertEquals(listOf(1L, 2L), result)
    }
}
