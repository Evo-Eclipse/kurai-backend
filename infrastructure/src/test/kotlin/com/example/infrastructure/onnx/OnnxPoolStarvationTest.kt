package com.example.infrastructure.onnx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proof-of-fix for the ONNX / Dispatchers.Default starvation
 * pattern. Before [OnnxInferenceAdapter] routed `session.run()`
 * through its own bounded pool, every concurrent `infer` call
 * pinned a `Dispatchers.Default` thread. On a 2-core JVM
 * `Default = max(2, ncores) = 2`, so a small number of concurrent
 * embeds wedged any other `withContext(Dispatchers.Default) { … }`
 * indefinitely. This test exercises the post-fix invariant:
 * `Default` stays responsive while inference is in flight.
 */
class OnnxPoolStarvationTest {
    @Test
    fun `concurrent inference does not block Dispatchers Default`() =
        runBlocking<Unit> {
            val modelBytes =
                checkNotNull(OnnxPoolStarvationTest::class.java.getResourceAsStream("/onnx/abs.onnx")) {
                    "abs.onnx fixture missing"
                }.readBytes()
            val sha = OnnxInferenceAdapter.sha256Hex(modelBytes)

            OnnxInferenceAdapter(
                modelBytes = modelBytes,
                expectedSha256 = sha,
                inputName = "x",
                outputName = "y",
                intraOpThreads = 2,
            ).use { adapter ->
                val input = FloatArray(60) { it.toFloat() }
                val shape = longArrayOf(3, 4, 5)

                coroutineScope {
                    val embeds =
                        (1..16).map {
                            async(Dispatchers.IO) { adapter.infer(input, shape) }
                        }

                    val defaultResult =
                        withTimeoutOrNull(2_000L) {
                            withContext(Dispatchers.Default) { "ok" }
                        }
                    assertEquals(
                        "ok",
                        defaultResult,
                        "Dispatchers.Default must remain responsive while ONNX inference is in flight",
                    )

                    embeds.awaitAll()
                }
            }
        }
}
