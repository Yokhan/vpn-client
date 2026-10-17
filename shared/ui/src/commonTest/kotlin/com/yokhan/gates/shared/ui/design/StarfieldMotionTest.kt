package com.yokhan.gates.shared.ui.design

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class StarfieldMotionTest {
    @Test
    fun noGlobalJumpAtSixteenSeconds() {
        for (duration in listOf(2.8f, 2.2f, 9f, 14f, 9.5f, 10.5f)) {
            val before = starProgress(15.999, duration, 1.2f)
            val after = starProgress(16.001, duration, 1.2f)
            val difference = abs(after - before)
            val circularDifference = minOf(difference, 1f - difference)
            assertTrue(circularDifference < 0.002f, "Unexpected jump for period $duration")
        }
    }

    @Test
    fun ownPeriodWrapsAndLongSessionsRetainFramePrecision() {
        assertTrue(starProgress(2.8f.toDouble(), 2.8f, 0f) == 0f)
        val before = starProgress(86400.0, 9f, 3f)
        val after = starProgress(86400.0 + 1.0 / 60, 9f, 3f)
        assertTrue(abs((after - before) - (1f / 540f)) < 0.000001f)
    }
}
