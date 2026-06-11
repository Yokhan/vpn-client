package com.yokhan.gates.shared.domain

data class RateDisplay(
    val bytesPerSecond: Long,
) {
    init {
        require(bytesPerSecond >= 0) { "bytesPerSecond must be non-negative" }
    }

    val isIdle: Boolean
        get() = bytesPerSecond == 0L
}

data class LatencyDisplay(
    val milliseconds: Long?,
) {
    init {
        require(milliseconds == null || milliseconds >= 0) {
            "milliseconds must be null or non-negative"
        }
    }

    val isAvailable: Boolean
        get() = milliseconds != null
}
