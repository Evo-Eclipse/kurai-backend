package com.example.observability

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.metrics.LongCounter

/**
 * Business counters that the HTTP/JVM/op instrumentation does not already
 * cover. Generic auth 401/429 rates come from the `http.server.*` metrics
 * (route + status), and operation latency from [OpTimer]'s `kurai.op.duration`,
 * so this stays deliberately small to keep metric cardinality low.
 *
 * Recorded from the composition root by passing plain callbacks into the
 * application services/workers, so `:application` never depends on OTel. Backed
 * by whatever [OpenTelemetry] is provided — no-op under the inert (`NONE`) SDK.
 */
class KuraiMetrics(
    openTelemetry: OpenTelemetry,
) {
    private val meter = openTelemetry.getMeter("com.example.observability.business")

    private val refreshChainRevoked: LongCounter =
        meter
            .counterBuilder("kurai.auth.refresh_chain_revoked")
            .setUnit("{revocation}")
            .setDescription("Refresh-token reuse detected; the session chain was burned")
            .build()

    private val sessionGcPurged: LongCounter =
        meter
            .counterBuilder("kurai.session_gc.purged")
            .setUnit("{session}")
            .setDescription("Expired auth sessions removed by the GC sweep")
            .build()

    private val contentPersistFailed: LongCounter =
        meter
            .counterBuilder("kurai.content.persist_failed")
            .setUnit("{failure}")
            .setDescription("Content write-behind persist failed after the response was sent")
            .build()

    /** A superseded refresh token was replayed and the user's chain was revoked. */
    fun recordRefreshChainRevoked() = refreshChainRevoked.add(1)

    /** [count] expired sessions were purged in one GC sweep. */
    fun recordSessionGcPurged(count: Long) = sessionGcPurged.add(count)

    /** A content write-behind persist failed silently after responding. */
    fun recordContentPersistFailed() = contentPersistFailed.add(1)
}
