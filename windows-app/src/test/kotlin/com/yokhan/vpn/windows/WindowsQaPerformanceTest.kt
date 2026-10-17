package com.yokhan.gates.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsQaPerformanceTest {
    @Test fun ignoresIdleAndSeparatesDrawFromClockIntervals() {
        val meter = WindowsQaPerformance()
        meter.frame(1_000_000, "dashboard")
        assertEquals(emptyMap<Any, Any>(), meter.snapshot()["phases"])
        meter.reset()
        meter.frame(1_000_000, "dashboard")
        meter.frame(18_000_000, "dashboard")
        meter.draw(2_000_000, "dashboard")
        val phases = meter.snapshot()["phases"] as Map<*, *>
        assertEquals(17.0, (phases["frame_interval"] as Map<*, *>)["maxMillis"])
        assertEquals(2.0, (phases["draw_record"] as Map<*, *>)["maxMillis"])
        meter.stop()
        meter.frame(100_000_000, "dashboard")
        assertFalse(meter.snapshot()["recording"] as Boolean)
    }

    @Test fun keepsOutliersNotJustAnAverageAndBoundsStorage() {
        val meter = WindowsQaPerformance()
        meter.reset()
        repeat(9000) { meter.draw(if (it == 8999) 120_000_000 else 1_000_000, "dashboard") }
        val phase = (meter.snapshot()["phases"] as Map<*, *>)["draw_record"] as Map<*, *>
        assertEquals(8192, phase["count"])
        assertEquals(120.0, phase["maxMillis"])
        assertEquals(1, phase["over100ms"])
        assertTrue(isWindowsQaTag("settings.routing"))
        assertFalse(isWindowsQaTag("runtime.connect"))
        assertFalse(isWindowsQaTag("report.message/../../"))
    }

    @Test fun sceneElapsedDoesNotMasqueradeAsThreadCpu() {
        val meter = WindowsQaPerformance()
        meter.reset()
        meter.scene(1_000_000, 60_000_000, 2_000_000, "dashboard")
        val phases = meter.snapshot()["phases"] as Map<*, *>
        assertEquals(60.0, (phases["scene_render_elapsed"] as Map<*, *>)["maxMillis"])
        assertEquals(2.0, (phases["scene_render_thread_cpu"] as Map<*, *>)["maxMillis"])
    }
}
