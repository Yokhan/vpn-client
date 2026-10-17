package com.yokhan.gates.shared.ui.design

internal fun starProgress(elapsedSeconds: Double, durationSeconds: Float, offsetSeconds: Float): Float =
    (((elapsedSeconds + offsetSeconds) % durationSeconds) / durationSeconds).toFloat()
