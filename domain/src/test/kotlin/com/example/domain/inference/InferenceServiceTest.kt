package com.example.domain.inference

import com.example.domain.profile.Scoring
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class InferenceServiceTest {
    private val rng = Random(0xFACE)

    private fun randomVec(dim: Int): FloatArray = FloatArray(dim) { rng.nextFloat() * 2f - 1f }

    private fun l2Norm(v: FloatArray): Float {
        var s = 0.0
        for (x in v) s += x * x
        return sqrt(s).toFloat()
    }

    @Test
    fun `embed output is L2-normalized`() {
        val service =
            InferenceService(
                preprocess = { _ -> FloatArray(3 * 224 * 224) { rng.nextFloat() } },
                infer = { _ -> randomVec(768) },
            )
        runBlocking {
            val result = service.embed(ByteArray(100))
            assertTrue(abs(l2Norm(result) - 1f) < 1e-5f, "Output must be L2-normalized, norm=${l2Norm(result)}")
        }
    }

    @Test
    fun `embed is deterministic for deterministic ports`() {
        val fixedPreprocessOutput = FloatArray(3 * 224 * 224) { it.toFloat() }
        val fixedInferOutput = randomVec(768)
        val service =
            InferenceService(
                preprocess = { _ -> fixedPreprocessOutput },
                infer = { _ -> fixedInferOutput.copyOf() },
            )
        runBlocking {
            val r1 = service.embed(ByteArray(10))
            val r2 = service.embed(ByteArray(10))
            val expected = Scoring.l2Normalize(fixedInferOutput)
            for (i in r1.indices) {
                assertTrue(abs(r1[i] - expected[i]) < 1e-6f)
                assertTrue(abs(r2[i] - expected[i]) < 1e-6f)
            }
        }
    }
}
