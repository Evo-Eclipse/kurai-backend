package com.example.observability

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OpenTelemetryBootstrapTest {
    private fun config(
        exporter: OtelExporter,
        endpoint: String? = null,
    ) = OtelConfig(
        serviceName = "kurai-test",
        exporter = exporter,
        otlpEndpoint = endpoint,
        otlpHeaders = emptyMap(),
        metricIntervalMs = 60_000,
    )

    @Test
    fun `none builds an inert sdk with a working tracer`() {
        val sdk = createOpenTelemetry(config(OtelExporter.NONE))
        assertNotNull(sdk.getTracer("test"))
        sdk.close()
    }

    @Test
    fun `logging exporter builds`() {
        val sdk = createOpenTelemetry(config(OtelExporter.LOGGING))
        assertNotNull(sdk)
        sdk.close()
    }

    @Test
    fun `otlp exporter builds with an endpoint`() {
        val sdk = createOpenTelemetry(config(OtelExporter.OTLP, endpoint = "http://localhost:4318"))
        assertNotNull(sdk)
        sdk.close()
    }

    @Test
    fun `optimer records on the inert sdk without error`() =
        runTest {
            val timer = OpTimer(createOpenTelemetry(config(OtelExporter.NONE)))
            assertEquals(42, timer.measured("suspend.op") { 42 })
            assertEquals(7, timer.measuredBlocking("blocking.op") { 7 })
        }

    @Test
    fun `jvm warmup metrics register and close on the inert sdk`() {
        val handle = registerJvmWarmupMetrics(createOpenTelemetry(config(OtelExporter.NONE)))
        handle.close()
    }

    @Test
    fun `business metrics record on the inert sdk without error`() {
        val metrics = KuraiMetrics(createOpenTelemetry(config(OtelExporter.NONE)))
        metrics.recordRefreshChainRevoked()
        metrics.recordSessionGcPurged(3)
    }
}
