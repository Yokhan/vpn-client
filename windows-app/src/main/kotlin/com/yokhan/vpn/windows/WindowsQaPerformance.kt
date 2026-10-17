package com.yokhan.gates.windows

import kotlin.math.ceil

internal data class WindowsQaTiming(val millis: Double, val screen: String, val phase: String, val atMillis: Double = 0.0)

internal class WindowsQaPerformance {
    private val samples = ArrayDeque<WindowsQaTiming>()
    private var previousFrame = 0L
    private var previousScene = 0L
    private var enabled = false
    private var startedNanos = 0L
    private var startedUtc = ""
    private val markers = ArrayDeque<Map<String, Any>>()

    @Synchronized fun reset() {
        samples.clear(); markers.clear(); previousFrame = 0; previousScene = 0; enabled = true
        startedNanos = System.nanoTime(); startedUtc = java.time.Instant.now().toString()
    }
    @Synchronized fun stop() { enabled = false; previousFrame = 0 }
    @Synchronized fun frame(nanos: Long, screen: String) {
        if (!enabled) return
        if (previousFrame > 0 && nanos > previousFrame) add(WindowsQaTiming((nanos - previousFrame) / 1_000_000.0, screen, "frame_interval"))
        previousFrame = nanos
    }
    @Synchronized fun draw(nanos: Long, screen: String) {
        if (enabled) add(WindowsQaTiming(nanos / 1_000_000.0, screen, "draw_record"))
    }
    @Synchronized fun scene(startNanos: Long, durationNanos: Long, cpuNanos: Long?, screen: String) {
        if (!enabled) return
        if (previousScene > 0 && startNanos > previousScene) {
            add(WindowsQaTiming((startNanos - previousScene) / 1_000_000.0, screen, "scene_frame_interval"))
        }
        previousScene = startNanos
        add(WindowsQaTiming(durationNanos / 1_000_000.0, screen, "scene_render_elapsed"))
        if (cpuNanos != null && cpuNanos >= 0) add(WindowsQaTiming(cpuNanos / 1_000_000.0, screen, "scene_render_thread_cpu"))
    }
    @Synchronized fun composition(nanos: Long, screen: String) {
        if (enabled) add(WindowsQaTiming(nanos / 1_000_000.0, screen, "composition_commit"))
    }
    @Synchronized fun mark(id: String) {
        if (!enabled) return
        if (markers.size == 64) markers.removeFirst()
        markers.addLast(mapOf("id" to id, "atMillis" to (System.nanoTime() - startedNanos) / 1_000_000.0))
    }
    @Synchronized fun snapshot(): Map<String, Any> = mapOf(
        "recording" to enabled,
        "kind" to "compose_frame_clock_scene_elapsed_and_thread_cpu_not_gpu_present",
        "capacity" to 8192,
        "startedUtc" to startedUtc,
        "markers" to markers.toList(),
        "phases" to samples.groupBy { it.phase }.mapValues { (_, values) ->
            val sorted = values.map { it.millis }.sorted()
            mapOf("count" to sorted.size, "p95Millis" to sorted[(ceil(sorted.size * .95).toInt() - 1).coerceAtLeast(0)],
                "maxMillis" to sorted.last(), "over33ms" to sorted.count { it > 33.4 },
                "over50ms" to sorted.count { it > 50 }, "over100ms" to sorted.count { it > 100 })
        },
        "worst" to samples.sortedByDescending { it.millis }.take(24),
    )
    private fun add(value: WindowsQaTiming) {
        if (samples.size == 8192) samples.removeFirst()
        samples.addLast(value.copy(atMillis = (System.nanoTime() - startedNanos) / 1_000_000.0))
    }
}
