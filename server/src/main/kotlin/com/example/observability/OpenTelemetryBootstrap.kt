package com.example.observability

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.logging.LoggingMetricExporter
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.time.Duration

/** Where traces/metrics go. `NONE` builds an inert SDK (no exporters, no overhead). */
enum class OtelExporter { NONE, LOGGING, OTLP }

/**
 * Server-owned observability settings, mapped from [com.example.AppConfig] at
 * the composition root so the env layer stays free of OTel types.
 */
data class OtelConfig(
    val serviceName: String,
    val exporter: OtelExporter,
    val otlpEndpoint: String?,
    val otlpHeaders: Map<String, String>,
    val metricIntervalMs: Long,
)

/**
 * Builds the process-wide [OpenTelemetrySdk] for Kurai.
 *
 * `LOGGING` prints spans/metrics to stdout (local profiling without a
 * collector); `OTLP` ships them over OTLP/HTTP (Grafana Cloud) behind a
 * [BatchSpanProcessor] and a [PeriodicMetricReader]; `NONE` returns an inert
 * SDK whose tracers/meters are no-ops — the form tests and disabled deploys use.
 */
fun createOpenTelemetry(config: OtelConfig): OpenTelemetrySdk {
    if (config.exporter == OtelExporter.NONE) {
        return OpenTelemetrySdk.builder().build()
    }
    val resource =
        Resource.getDefault().merge(
            Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), config.serviceName)),
        )
    val tracerProvider =
        SdkTracerProvider
            .builder()
            .setResource(resource)
            .addSpanProcessor(BatchSpanProcessor.builder(spanExporter(config)).build())
            .build()
    val meterProvider =
        SdkMeterProvider
            .builder()
            .setResource(resource)
            .registerMetricReader(
                PeriodicMetricReader
                    .builder(metricExporter(config))
                    .setInterval(Duration.ofMillis(config.metricIntervalMs))
                    .build(),
            ).build()
    return OpenTelemetrySdk
        .builder()
        .setTracerProvider(tracerProvider)
        .setMeterProvider(meterProvider)
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build()
}

private fun spanExporter(config: OtelConfig): SpanExporter =
    when (config.exporter) {
        OtelExporter.LOGGING -> LoggingSpanExporter.create()
        OtelExporter.OTLP ->
            OtlpHttpSpanExporter
                .builder()
                .apply {
                    config.otlpEndpoint?.let { setEndpoint("$it/v1/traces") }
                    config.otlpHeaders.forEach { (k, v) -> addHeader(k, v) }
                }.build()
        OtelExporter.NONE -> error("NONE is handled before exporter construction")
    }

private fun metricExporter(config: OtelConfig): MetricExporter =
    when (config.exporter) {
        OtelExporter.LOGGING -> LoggingMetricExporter.create()
        OtelExporter.OTLP ->
            OtlpHttpMetricExporter
                .builder()
                .apply {
                    config.otlpEndpoint?.let { setEndpoint("$it/v1/metrics") }
                    config.otlpHeaders.forEach { (k, v) -> addHeader(k, v) }
                }.build()
        OtelExporter.NONE -> error("NONE is handled before exporter construction")
    }
