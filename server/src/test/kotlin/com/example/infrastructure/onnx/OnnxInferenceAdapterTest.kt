package com.example.infrastructure.onnx

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OnnxInferenceAdapterTest {
    /**
     * Bundled `abs.onnx` is the canonical ONNX backend test fixture for the
     * `Abs` op. It takes a single input `x` of shape [3,4,5] and returns
     * `y = |x|` of the same shape.
     */
    private val modelBytes: ByteArray = loadResource("/onnx/abs.onnx")
    private val sha = OnnxInferenceAdapter.sha256Hex(modelBytes)
    private val shape = longArrayOf(3, 4, 5)

    @Test
    fun `infer returns absolute values from the bundled abs model`() =
        runBlocking {
            adapter().use { onnx ->
                val input = FloatArray(60) { i -> if (i % 2 == 0) -i.toFloat() else i.toFloat() }
                val output = onnx.infer(input, shape)
                assertEquals(60, output.size)
                for (i in input.indices) {
                    assertEquals(abs(input[i]), output[i], "i=$i")
                }
            }
        }

    @Test
    fun `concurrent infer calls produce correct per-input results`() =
        runBlocking {
            adapter().use { onnx ->
                // Each coroutine submits a distinct input; the mutex
                // serializes entry to the session but every result must
                // still be the abs of its own input.
                val inputs =
                    (0 until 10).map { worker ->
                        FloatArray(60) { idx -> (worker - idx).toFloat() }
                    }
                val outputs =
                    coroutineScope {
                        inputs.map { input -> async { onnx.infer(input, shape) } }.awaitAll()
                    }
                outputs.forEachIndexed { worker, out ->
                    val expected = inputs[worker].map { abs(it) }
                    assertEquals(expected, out.toList(), "worker=$worker")
                }
            }
        }

    @Test
    fun `first infer completes within 1s (cold-call warmup)`() =
        runBlocking {
            adapter().use { onnx ->
                val input = FloatArray(60) { it.toFloat() }
                val elapsedMs = measureNanoTime { onnx.infer(input, shape) } / 1_000_000
                assertTrue(elapsedMs < 1000, "Cold call must complete within 1s, took ${elapsedMs}ms")
            }
        }

    @Test
    fun `SHA-256 mismatch fails fast at construction`() {
        val tampered = "0".repeat(64)
        val error =
            assertFailsWith<IllegalArgumentException> {
                OnnxInferenceAdapter(
                    modelBytes = modelBytes,
                    expectedSha256 = tampered,
                    inputName = "x",
                    outputName = "y",
                    intraOpThreads = 2,
                )
            }
        assertTrue(error.message!!.contains("SHA-256 mismatch"))
    }

    private fun adapter() =
        OnnxInferenceAdapter(
            modelBytes = modelBytes,
            expectedSha256 = sha,
            inputName = "x",
            outputName = "y",
            intraOpThreads = 2,
        )

    private fun loadResource(path: String): ByteArray =
        OnnxInferenceAdapterTest::class.java.getResourceAsStream(path)?.readBytes()
            ?: error("Test resource not found: $path")
}
