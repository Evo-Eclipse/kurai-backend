package com.example.infrastructure.lucene

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LuceneAdapterTest {
    private lateinit var indexDir: Path
    private lateinit var adapter: LuceneAdapter

    @BeforeTest
    fun setUp() {
        indexDir = createTempDirectory("kurai-lucene-test-")
        adapter = LuceneAdapter(indexDir)
    }

    @AfterTest
    fun tearDown() {
        adapter.close()
        indexDir
            .toFile()
            .walkBottomUp()
            .forEach { it.delete() }
    }

    @Test
    fun `write then search returns the inserted item`() {
        val vec = randomNormalizedVector(seed = 1)
        adapter.write(itemId = 42L, vector = vec)
        adapter.refresh()
        assertEquals(listOf(42L), adapter.search(query = vec, k = 1))
    }

    @Test
    fun `search on empty index returns empty list`() {
        assertEquals(emptyList(), adapter.search(query = randomNormalizedVector(seed = 7), k = 10))
    }

    @Test
    fun `top-10 recall above 0_9 on 1000 vectors`() {
        val rng = Random(seed = 42)
        val n = 1000
        val vectors = (0 until n).map { randomNormalizedVector(seed = it xor rng.nextInt()) }
        vectors.forEachIndexed { i, v -> adapter.write(i.toLong(), v) }
        adapter.refresh()

        val queryCount = 50
        var hit = 0
        repeat(queryCount) {
            val idx = rng.nextInt(n)
            if (adapter.search(vectors[idx], k = 10).contains(idx.toLong())) hit++
        }
        val recall = hit.toDouble() / queryCount
        assertTrue(recall > 0.9, "Top-10 recall must be > 0.9, was $recall")
    }

    @Test
    fun `search p95 latency below 30ms on 1000 vectors`() {
        val rng = Random(seed = 99)
        val n = 1000
        val vectors = (0 until n).map { randomNormalizedVector(seed = it xor rng.nextInt()) }
        vectors.forEachIndexed { i, v -> adapter.write(i.toLong(), v) }
        adapter.refresh()

        // Warmup.
        repeat(20) { adapter.search(vectors[rng.nextInt(n)], k = 10) }

        val samples = 200
        val latenciesMs = LongArray(samples)
        for (i in 0 until samples) {
            val t0 = System.nanoTime()
            adapter.search(vectors[rng.nextInt(n)], k = 10)
            latenciesMs[i] = (System.nanoTime() - t0) / 1_000_000
        }
        latenciesMs.sort()
        val p95 = latenciesMs[(samples * 95) / 100]
        assertTrue(p95 < 30, "p95 latency must be < 30 ms, was ${p95}ms")
    }

    @Test
    fun `getVector returns bit-exact vector for a written itemId`() {
        val vec = randomNormalizedVector(seed = 11)
        adapter.write(itemId = 77L, vector = vec)
        adapter.refresh()
        val result = assertNotNull(adapter.getVector(77L))
        assertEquals(vec.size, result.size)
        var maxDiff = 0f
        for (i in vec.indices) maxDiff = maxOf(maxDiff, kotlin.math.abs(vec[i] - result[i]))
        assertTrue(maxDiff < 1e-6f, "Round-trip vector should be bit-exact, max diff=$maxDiff")
    }

    @Test
    fun `getVector returns null for unknown itemId`() {
        adapter.write(itemId = 1L, vector = randomNormalizedVector(seed = 1))
        adapter.refresh()
        assertNull(adapter.getVector(999L))
    }

    @Test
    fun `getVector on empty index returns null`() {
        assertNull(adapter.getVector(1L))
    }

    @Test
    fun `write rejects non-normalized vectors via debug assert (I-4)`() {
        val unnormalized = FloatArray(LuceneAdapter.VECTOR_DIM) { 0.5f }
        assertFailsWith<AssertionError> { adapter.write(1L, unnormalized) }
    }

    private fun randomNormalizedVector(seed: Int): FloatArray {
        val rng = Random(seed)
        val v = FloatArray(LuceneAdapter.VECTOR_DIM) { rng.nextFloat() * 2f - 1f }
        var sumSq = 0.0
        for (x in v) sumSq += x * x
        val norm = sqrt(sumSq).toFloat()
        for (i in v.indices) v[i] = v[i] / norm
        return v
    }
}
