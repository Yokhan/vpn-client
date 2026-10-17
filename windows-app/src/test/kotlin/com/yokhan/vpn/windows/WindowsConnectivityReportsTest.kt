package com.yokhan.gates.windows

import com.yokhan.gates.windows.service.ConnectivityHistory
import com.yokhan.gates.windows.service.ConnectivityObservation
import com.yokhan.gates.windows.service.ConnectivityProbe
import com.yokhan.gates.windows.service.DiagnosticTarget
import com.yokhan.gates.windows.service.ProbeOutcome
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WindowsConnectivityReportsTest {
    @Test
    fun missingHistoryIsExplicitAndReadingDoesNotCreateDirectories() {
        val directory = Files.createTempDirectory("gates-history-report").resolve("not-created")
        val result = windowsConnectivityHistory(directory)
        assertEquals("not-recorded", result.get("availability").asString)
        assertFalse(Files.exists(directory))
    }

    @Test
    fun unavailableMachineLocationDoesNotPretendThatConnectionWasHealthy() {
        assertEquals("unavailable", windowsConnectivityHistory(null).get("availability").asString)
    }

    @Test
    fun emptyHistoryReadsAsAnEmptyEventListWithoutMutatingFiles() {
        val directory = Files.createTempDirectory("gates-empty-history")
        val result = windowsConnectivityHistory(directory)
        assertEquals("available", result.get("availability").asString)
        assertEquals(0, result.getAsJsonArray("events").size())
        Files.list(directory).use { assertEquals(0, it.count()) }
    }

    @Test
    fun reportRetainsTheFailedRequestPhaseAfterTheConnectionHasRecovered() {
        val directory = Files.createTempDirectory("gates-report-evidence")
        val history = ConnectivityHistory(directory)
        history.record(ConnectivityObservation(
            1_000, 100, "d".repeat(64), true,
            ConnectivityProbe(ProbeOutcome.FAILURE, DiagnosticTarget.YOUTUBE, 28, 0, 0, 0, 0, 3_000),
            ConnectivityProbe(ProbeOutcome.SUCCESS, DiagnosticTarget.YOUTUBE, 0, 204, 0, 20, 100, 200),
        ))
        val result = windowsConnectivityHistory(directory)
        assertEquals("available", result.get("availability").asString)
        val event = result.getAsJsonArray("events").first().asJsonObject
        assertEquals("SYSTEM_PATH", event.get("category").asString)
        assertEquals("SUSPECTED", event.get("transition").asString)
        assertFalse(result.toString().contains("socks5h://"))
        assertFalse(result.toString().contains("https://"))
    }
}
