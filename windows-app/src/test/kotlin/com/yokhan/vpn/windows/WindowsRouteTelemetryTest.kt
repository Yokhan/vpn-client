package com.yokhan.gates.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WindowsRouteTelemetryTest {
    @Test
    fun parsesCurlMetricsWithoutKeepingUrl() {
        val sample = parseWindowsCurlQualitySample(
            "https://i.ytimg.com/vi/example/image.jpg",
            RuntimeCommandResult(
                0,
                "200 ttfb=0.250s total=8.000s bytes=150000 speed=18750",
            ),
        )

        requireNotNull(sample)
        assertEquals(250, sample.ttfbMs)
        assertEquals(8_000, sample.durationMs)
        assertEquals(150_000, sample.bytesTransferred)
        assertEquals(150, sample.throughputKbps)
    }

    @Test
    fun serializesDegradedV2Payload() {
        val payload = buildWindowsQualityTelemetryPayload(
            "bridge",
            listOf(
                WindowsCurlQualitySample(
                    targetId = "youtube_cdn_static",
                    isSuccessful = true,
                    ttfbMs = 250,
                    durationMs = 8_000,
                    bytesTransferred = 150_000,
                    throughputKbps = 150,
                    isTimedOut = false,
                    errorCode = "",
                ),
            ),
        )

        assertEquals("gates-route-telemetry-v2", payload.get("schema").asString)
        assertEquals("degraded", payload.get("result").asString)
        assertFalse(payload.toString().contains("https://"))
    }

    @Test
    fun bugReportWithoutQualityEvidenceIsUnknown() {
        val payload = buildWindowsUnknownTelemetryPayload("bridge")

        assertEquals("unknown", payload.get("result").asString)
    }
}
