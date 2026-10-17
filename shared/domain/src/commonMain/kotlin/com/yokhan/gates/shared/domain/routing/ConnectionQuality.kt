package com.yokhan.gates.shared.domain.routing

enum class ConnectionQualityClass {
    Healthy,
    Degraded,
    Stalled,
    Down,
    Unknown,
}

enum class ConnectionFailureStage {
    Dns,
    Connect,
    Tls,
    Transport,
    Ttfb,
    Transfer,
    Verify,
    Tunnel,
    Unknown,
}

data class ConnectionQualityPolicy(
    val degradedThroughputKbps: Long = DEFAULT_DEGRADED_THROUGHPUT_KBPS,
    val degradedTtfbMs: Long = DEFAULT_DEGRADED_TTFB_MS,
    val stallMaxBytes: Long = DEFAULT_STALL_MAX_BYTES,
    val stallMinDurationMs: Long = DEFAULT_STALL_MIN_DURATION_MS,
)

data class ConnectionQualitySample(
    val targetId: String,
    val isSuccessful: Boolean,
    val failureStage: ConnectionFailureStage = ConnectionFailureStage.Unknown,
    val ttfbMs: Long = 0,
    val durationMs: Long = 0,
    val bytesTransferred: Long = 0,
    val throughputKbps: Long = 0,
    val isTimedOut: Boolean = false,
    val isBlocked: Boolean = false,
)

data class ConnectionQualityResult(
    val qualityClass: ConnectionQualityClass,
    val score: Int,
    val reason: String,
)

fun classifyConnectionQuality(
    sample: ConnectionQualitySample,
    policy: ConnectionQualityPolicy = ConnectionQualityPolicy(),
): ConnectionQualityResult = when {
    sample.isBlocked -> qualityResult(ConnectionQualityClass.Down, "probe was blocked")
    sample.isTimedOut && sample.durationMs >= policy.stallMinDurationMs &&
        sample.bytesTransferred <= policy.stallMaxBytes ->
        qualityResult(ConnectionQualityClass.Stalled, "transfer stalled after a small payload")
    !sample.isSuccessful -> qualityResult(ConnectionQualityClass.Down, "probe failed")
    effectiveThroughput(sample) in 1 until policy.degradedThroughputKbps ->
        qualityResult(ConnectionQualityClass.Degraded, "sustained throughput is below policy")
    sample.ttfbMs >= policy.degradedTtfbMs ->
        qualityResult(ConnectionQualityClass.Degraded, "time to first byte is above policy")
    effectiveThroughput(sample) > 0 || sample.ttfbMs > 0 ->
        qualityResult(ConnectionQualityClass.Healthy, "quality sample passed policy")
    else -> qualityResult(ConnectionQualityClass.Unknown, "sample has no quality evidence")
}

private fun effectiveThroughput(sample: ConnectionQualitySample): Long {
    if (sample.bytesTransferred > 0 && sample.durationMs > 0) {
        return (sample.bytesTransferred * BITS_PER_BYTE) / sample.durationMs
    }
    return sample.throughputKbps.coerceAtLeast(0)
}

private fun qualityResult(
    qualityClass: ConnectionQualityClass,
    reason: String,
): ConnectionQualityResult = ConnectionQualityResult(
    qualityClass = qualityClass,
    score = when (qualityClass) {
        ConnectionQualityClass.Healthy -> 100
        ConnectionQualityClass.Degraded -> 45
        ConnectionQualityClass.Stalled -> 20
        ConnectionQualityClass.Down -> 0
        ConnectionQualityClass.Unknown -> 50
    },
    reason = reason,
)

private const val BITS_PER_BYTE = 8L
private const val DEFAULT_DEGRADED_THROUGHPUT_KBPS = 256L
private const val DEFAULT_DEGRADED_TTFB_MS = 3_000L
private const val DEFAULT_STALL_MAX_BYTES = 32_768L
private const val DEFAULT_STALL_MIN_DURATION_MS = 5_000L
