package com.yokhan.gates.shared.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals

class StarfieldClockTest {
    @Test
    fun disabledMotionScaleDoesNotAdvanceElapsedTime() {
        assertEquals(0.0, starfieldElapsedDeltaSeconds(16_666_667L, 0f))
    }

    @Test
    fun elapsedTimeUsesTheActiveMotionScale() {
        assertEquals(0.016, starfieldElapsedDeltaSeconds(16_000_000L, 1f), 0.000001)
        assertEquals(0.008, starfieldElapsedDeltaSeconds(16_000_000L, 2f), 0.000001)
    }
}
