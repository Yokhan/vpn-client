package com.yokhan.gates.windows

import com.yokhan.gates.shared.domain.ConnectionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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

    @Test
    fun parsesBestLatencyMsFromMixedTestOutput() {
        val output = """
            FAIL https://example.org/a :: 000 timeout
            OK   https://www.youtube.com/generate_204 :: 204 ttfb=0.184s total=0.210s bytes=0 speed=0
            OK   https://telegram.org/ :: 200 ttfb=0.097s total=0.140s bytes=1200 speed=8500
        """.trimIndent()

        assertEquals(97L, parseBestLatencyMsFromTestOutput(output))
        assertNull(parseBestLatencyMsFromTestOutput("FAIL https://example.org :: 000 timeout"))
    }

    @Test
    fun dashboardStateReflectsConnectedLatencyAndResetsWhenDisconnected() {
        val connected = dashboardState(
            status = ConnectionStatus.Connected,
            activeRoute = "bridge",
            busy = false,
            latencyMs = 97L,
            connectionTestRunning = true,
        )
        assertEquals("97 мс", connected.latencyLabel)
        assertEquals(97L, connected.latency.milliseconds)
        assertEquals(true, connected.isConnectionTestRunning)

        val disconnected = dashboardState(
            status = ConnectionStatus.Disconnected,
            activeRoute = "bridge",
            busy = false,
            latencyMs = 97L,
            connectionTestRunning = true,
        )
        assertEquals("--", disconnected.latencyLabel)
        assertNull(disconnected.latency.milliseconds)
        assertFalse(disconnected.isConnectionTestRunning)
    }
}
