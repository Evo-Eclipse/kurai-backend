package com.example.domain.profile

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SplittingTest {
    private val rng = Random(0xDEAD)

    private fun nearVec(
        base: FloatArray,
        noise: Float,
        seed: Int,
    ): FloatArray {
        val r = Random(seed)
        return Scoring.l2Normalize(FloatArray(base.size) { i -> base[i] + r.nextFloat() * noise })
    }

    @Test
    fun `two tight separable clusters yield silhouette above 0_5`() {
        val dim = 128
        val c0 = FloatArray(dim).also { it[0] = 1f }
        val c1 = FloatArray(dim).also { it[1] = 1f }
        val vecs = (0 until 50).map { nearVec(c0, 0.05f, it) } + (0 until 50).map { nearVec(c1, 0.05f, it + 100) }
        val assignments = splitPrototypes(vecs, k = 2, seed = 42L)
        val s = silhouette(vecs, assignments)
        assertTrue(s > 0.5, "Separable clusters should have silhouette > 0.5, got $s")
    }

    @Test
    fun `nearly identical vectors yield silhouette below 0_1`() {
        val base = FloatArray(128).also { it[0] = 1f }
        val vecs = (0 until 50).map { nearVec(base, 0.001f, it) }
        val assignments = splitPrototypes(vecs, k = 2, seed = 42L)
        val s = silhouette(vecs, assignments)
        assertTrue(s < 0.1, "Uniform cluster should have silhouette < 0.1, got $s")
    }

    @Test
    fun `same seed and vectors produce identical assignments`() {
        val base = FloatArray(128).also { it[0] = 1f }
        val vecs = (0 until 20).map { Scoring.l2Normalize(FloatArray(128) { rng.nextFloat() * 2f - 1f }) }
        val a1 = splitPrototypes(vecs, k = 3, seed = 99L)
        val a2 = splitPrototypes(vecs, k = 3, seed = 99L)
        assertEquals(a1, a2, "Same seed must produce same assignments")
    }

    @Test
    fun `k below 2 throws IllegalArgumentException`() {
        val vecs = listOf(FloatArray(128) { 1f })
        assertFailsWith<IllegalArgumentException> { splitPrototypes(vecs, k = 1, seed = 0L) }
    }

    @Test
    fun `k above 5 throws IllegalArgumentException`() {
        val vecs = listOf(FloatArray(128) { 1f })
        assertFailsWith<IllegalArgumentException> { splitPrototypes(vecs, k = 6, seed = 0L) }
    }
}
