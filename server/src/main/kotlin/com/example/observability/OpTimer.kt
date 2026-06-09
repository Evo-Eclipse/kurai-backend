package com.example.observability

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram

/**
 * Records per-operation wall-clock latency into one OTel histogram
 * (`kurai.op.duration`, ms) tagged by `op`. The per-op percentiles surface both
 * JVM warm-up (latency falling as the JIT compiles the hot path) and
 * steady-state bottlenecks.
 *
 * Backed by whatever [OpenTelemetry] is provided: with the inert (`NONE`) SDK
 * the histogram is a no-op, so [measured] stays cheap and the call sites need no
 * enabled/disabled branch.
 */
class OpTimer(
    openTelemetry: OpenTelemetry,
) {
    private val histogram: DoubleHistogram =
        openTelemetry
            .getMeter(INSTRUMENTATION_SCOPE)
            .histogramBuilder("kurai.op.duration")
            .setUnit("ms")
            .setDescription("Operation wall-clock latency by op")
            .build()

    /** Times suspend [block], recording its duration under the `op` attribute even on failure. */
    suspend fun <R> measured(
        op: String,
        block: suspend () -> R,
    ): R {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(op, start)
        }
    }

    /** Blocking counterpart of [measured] for non-suspend ports (e.g. the Lucene lookup). */
    fun <R> measuredBlocking(
        op: String,
        block: () -> R,
    ): R {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(op, start)
        }
    }

    private fun record(
        op: String,
        startNanos: Long,
    ) = histogram.record((System.nanoTime() - startNanos) / NANOS_PER_MILLI, Attributes.of(OP_KEY, op))

    companion object {
        private const val INSTRUMENTATION_SCOPE = "com.example.observability"
        private const val NANOS_PER_MILLI = 1_000_000.0
        private val OP_KEY: AttributeKey<String> = AttributeKey.stringKey("op")
    }
}
