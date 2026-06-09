package com.example.observability

import io.opentelemetry.api.OpenTelemetry
import java.lang.management.ManagementFactory

/**
 * Registers JVM warm-up gauges from the platform MXBeans.
 *
 * JIT compilation time and the loaded-class count climb steeply at boot and
 * plateau once the JVM is warm, so watching them next to the per-op latency
 * histograms tells "still warming up" apart from a genuine regression. Heap
 * usage rounds out the picture. Returns an [AutoCloseable] that detaches the
 * async callbacks on shutdown.
 *
 * Kept deliberately small and hand-rolled (rather than pulling the alpha
 * runtime-telemetry instrumentation) because these three signals are exactly
 * the warm-up essence and stay fully under our control.
 */
fun registerJvmWarmupMetrics(openTelemetry: OpenTelemetry): AutoCloseable {
    val meter = openTelemetry.getMeter("com.example.observability.jvm")
    val classLoading = ManagementFactory.getClassLoadingMXBean()
    val memory = ManagementFactory.getMemoryMXBean()
    val gauges = mutableListOf<AutoCloseable>()

    val compilation = ManagementFactory.getCompilationMXBean()
    if (compilation != null && compilation.isCompilationTimeMonitoringSupported) {
        gauges +=
            meter
                .gaugeBuilder("jvm.jit.compilation_time")
                .ofLongs()
                .setUnit("ms")
                .setDescription("Cumulative JIT compilation time since JVM start; plateaus once warm")
                .buildWithCallback { it.record(compilation.totalCompilationTime) }
    }
    gauges +=
        meter
            .gaugeBuilder("jvm.class.loaded")
            .ofLongs()
            .setUnit("{class}")
            .setDescription("Currently loaded classes; plateaus once warm")
            .buildWithCallback { it.record(classLoading.loadedClassCount.toLong()) }
    gauges +=
        meter
            .gaugeBuilder("jvm.memory.heap.used")
            .ofLongs()
            .setUnit("By")
            .setDescription("Used heap memory")
            .buildWithCallback { it.record(memory.heapMemoryUsage.used) }

    return AutoCloseable { gauges.forEach(AutoCloseable::close) }
}
