package com.yokhan.gates.shared.domain.routing

import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionQualityTest {
    @Test
    fun classifiesHealthyTransfer() {
        val result = classifyConnectionQuality(sample(bytes = 512_000, durationMs = 2_000, ttfbMs = 180))

        assertEquals(ConnectionQualityClass.Healthy, result.qualityClass)
    }

    @Test
    fun classifiesSustained150KbpsAsDegraded() {
        val result = classifyConnectionQuality(sample(bytes = 150_000, durationMs = 8_000, ttfbMs = 200))

        assertEquals(ConnectionQualityClass.Degraded, result.qualityClass)
    }

    @Test
    fun classifies16kTimeoutAsStalled() {
        val result = classifyConnectionQuality(
            sample(bytes = 16_384, durationMs = 10_000, ttfbMs = 200).copy(
                isSuccessful = false,
                isTimedOut = true,
            ),
        )

        assertEquals(ConnectionQualityClass.Stalled, result.qualityClass)
    }

    @Test
    fun classifiesTlsFailureAsDown() {
        val result = classifyConnectionQuality(
            sample(bytes = 0, durationMs = 500, ttfbMs = 0).copy(
                isSuccessful = false,
                failureStage = ConnectionFailureStage.Tls,
            ),
        )

        assertEquals(ConnectionQualityClass.Down, result.qualityClass)
    }

    private fun sample(bytes: Long, durationMs: Long, ttfbMs: Long) = ConnectionQualitySample(
        targetId = "youtube_cdn_static",
        isSuccessful = true,
        ttfbMs = ttfbMs,
        durationMs = durationMs,
        bytesTransferred = bytes,
    )
}
