package com.example.infrastructure.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * Single-session ONNX Runtime adapter.
 *
 * The session is opened once at construction time and serves all subsequent
 * [infer] calls until [close]. SPEC §10 invariant I-6: exactly one ONNX
 * session per process — DI is responsible for keeping this a singleton.
 *
 * Concurrent [infer] calls are capped by [inferenceParallelism] on a dedicated
 * IO dispatcher so blocking `session.run()` does not pin [Dispatchers.Default].
 * `OrtSession.run` is thread-safe; the cap bounds peak RSS (NFR-1) on small
 * hosts. Tune [inferenceParallelism] and [intraOpThreads] via env at process
 * start (see [DEFAULT_INFERENCE_PARALLELISM] and AppConfig).
 *
 * Model bytes are integrity-checked against [expectedSha256] before the
 * session is created. A mismatch fails fast at construction — required so
 * the bytes loaded from the object store match the SHA recorded in
 * `embedding_generations` for the active version.
 */
class OnnxInferenceAdapter(
    modelBytes: ByteArray,
    expectedSha256: String,
    private val inputName: String,
    private val outputName: String,
    intraOpThreads: Int,
    inferenceParallelism: Int = DEFAULT_INFERENCE_PARALLELISM,
) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    /**
     * Bounded pool for ONNX JNI work. [inferenceParallelism] limits how many
     * `session.run()` calls may overlap; each call may use up to
     * [intraOpThreads] threads inside ORT (ORT_SEQUENTIAL, inter-op = 1).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val onnxDispatcher = Dispatchers.IO.limitedParallelism(inferenceParallelism)

    init {
        require(inferenceParallelism > 0) {
            "inferenceParallelism must be positive, got $inferenceParallelism"
        }
        val actual = sha256Hex(modelBytes)
        require(actual == expectedSha256) {
            "ONNX model SHA-256 mismatch: expected=$expectedSha256, actual=$actual"
        }
        val opts =
            OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(intraOpThreads)
                setInterOpNumThreads(1)
            }
        session = env.createSession(modelBytes, opts)
    }

    /**
     * Runs inference on [input] reshaped to [shape] and returns the
     * model's output as a flat [FloatArray]. The product of [shape] must
     * equal `input.size`; the caller picks the layout that matches the
     * model's expected input rank.
     */
    suspend fun infer(
        input: FloatArray,
        shape: LongArray,
    ): FloatArray =
        withContext(onnxDispatcher) {
            require(input.size.toLong() == shape.fold(1L, Long::times)) {
                "Input size ${input.size} does not match shape ${shape.toList()}"
            }
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val out =
                        result.get(outputName).orElseThrow {
                            IllegalStateException("Output '$outputName' missing from session result")
                        }
                    flatten(out.value)
                }
            }
        }

    override fun close() {
        session.close()
        // OrtEnvironment is a process-wide singleton — do not close it here.
    }

    /**
     * ONNX Runtime returns N-dimensional Java arrays (`float[]`,
     * `float[][]`, `float[][][]`, …) depending on the output rank.
     * We always present a flat [FloatArray] to callers; consumers
     * already know the shape they passed in.
     */
    private fun flatten(value: Any?): FloatArray {
        val flat = ArrayList<Float>()

        fun walk(v: Any?) {
            when (v) {
                is FloatArray -> flat.addAll(v.toList())
                is Array<*> -> v.forEach(::walk)
                else -> error("Unsupported ONNX output type: ${v?.javaClass}")
            }
        }
        walk(value)
        return flat.toFloatArray()
    }

    companion object {
        /** Default concurrent `infer` calls (2 vCPU / 4 GB MVP). */
        const val DEFAULT_INFERENCE_PARALLELISM: Int = 1

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
