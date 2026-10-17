package com.yokhan.gates.windows.service

import com.google.gson.JsonParser
import com.yokhan.gates.shared.domain.diagnostics.ActiveIncident
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticEvent
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticEventKind
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticPath
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticTarget as IncidentDiagnosticTarget
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticTransport as IncidentDiagnosticTransport
import com.yokhan.gates.shared.domain.diagnostics.IncidentArchive
import com.yokhan.gates.shared.domain.diagnostics.IncidentCheckpoint
import com.yokhan.gates.shared.domain.diagnostics.IncidentStatus
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsIncidentStoreTest {
    @Test
    fun firstFailureIsPersistedAndRestartRecoversItAsInterrupted() = withDirectory { directory ->
        val store = WindowsIncidentStore(directory, nowMillis = { 10_000 })
        val history = ConnectivityHistory(directory.resolveSibling("connectivity"), incidentStore = store)
        history.record(observation(1, runtimeAlive = false))
        assertNotNull(store.loadCheckpoint()?.active)

        val restarted = WindowsIncidentStore(directory, nowMillis = { 10_000 })
        ConnectivityHistory(directory.resolveSibling("connectivity"), incidentStore = restarted).tick(2, 2)

        val recovered = restarted.listArchives().single()
        assertEquals(IncidentStatus.INTERRUPTED, recovered.status)
        assertEquals(DiagnosticEventKind.RUNTIME_FAILURE, recovered.trigger.kind)
        assertFalse(recovered.recovered)
    }

    @Test
    fun explicitStopIsPersistedBeforeLegacyHistoryFailure() = withDirectory { directory ->
        val legacyPath = directory.resolveSibling("blocked-connectivity-history")
        Files.writeString(legacyPath, "not-a-directory", UTF_8)
        val store = WindowsIncidentStore(directory, nowMillis = { 10_000 })
        val history = ConnectivityHistory(legacyPath, incidentStore = store)

        assertFailsWith<IOException> { history.record(observation(1, system = ProbeOutcome.FAILURE)) }
        history.endSession(2, 2)

        assertTrue(store.listArchives().any { it.status == IncidentStatus.ENDED })
    }

    @Test
    fun sealedArchivesAreIdempotentImmutableAndSelectedExportIsWhitelisted() = withDirectory { directory ->
        val store = WindowsIncidentStore(directory, nowMillis = { 10_000 })
        val archive = archive(id(1), timestamp = 1)
        store.saveArchive(archive)
        store.saveArchive(archive)
        val archivePath = directory.resolve("archives").resolve("${archive.id}.json")
        val savedBytes = Files.readAllBytes(archivePath)

        assertFailsWith<IOException> { store.saveArchive(archive.copy(truncated = true)) }
        assertTrue(savedBytes.contentEquals(Files.readAllBytes(archivePath)))
        assertEquals(archive, store.loadArchive(archive.id))
        assertNull(store.loadArchive("../${archive.id}"))
        val injected = JsonParser.parseString(Files.readString(archivePath, UTF_8)).asJsonObject
        injected.addProperty("unexpected", "private-value")
        injected.getAsJsonObject("archive").getAsJsonObject("trigger").addProperty("raw", "secret")
        Files.writeString(archivePath, injected.toString(), UTF_8)
        store.saveArchive(archive)

        val exported = WindowsIncidentReportsReader(directory).loadArchive(archive.id)!!.archiveJson
        assertFalse(exported.contains("private-value") || exported.contains("secret"))
        assertEquals(archive, JsonParser.parseString(exported).let { gson ->
            com.google.gson.Gson().fromJson(gson, IncidentArchive::class.java)
        })
    }

    @Test
    fun rotationKeepsEightArchivesAndProtectsTheTwoNewestSevereArchives() = withDirectory { directory ->
        val store = WindowsIncidentStore(directory, nowMillis = { 10_000 })
        repeat(10) { index -> store.saveArchive(archive(id(index), index.toLong() + 1)) }

        val archives = store.listArchives()
        assertEquals(8, archives.size)
        assertTrue(archives.any { it.id == id(8) })
        assertTrue(archives.any { it.id == id(9) })
        val totalBytes = Files.walk(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
        }
        assertTrue(totalBytes <= 96L * 1024 * 1024)
    }

    @Test
    fun recoveredConfirmedArchiveRemainsSevereForRotation() = withDirectory { directory ->
        val store = WindowsIncidentStore(directory, nowMillis = { 10_000 })
        store.saveArchive(probeArchive(id(40), 1, confirmed = true))
        repeat(8) { index -> store.saveArchive(probeArchive(id(index), index.toLong() + 2, confirmed = false)) }

        assertNotNull(store.loadArchive(id(40)))
        val summary = WindowsIncidentReportsReader(directory).listArchives().summaries.single { it.id == id(40) }
        assertTrue(summary.confirmed)
        assertTrue(summary.recovered)
    }

    @Test
    fun malformedCheckpointAndArchiveAreRejectedWithoutRepairOrOverwrite() = withDirectory { directory ->
        Files.createDirectories(directory)
        val checkpoint = directory.resolve("incident-checkpoint.json")
        Files.writeString(checkpoint, "{malformed", UTF_8)
        val store = WindowsIncidentStore(directory, nowMillis = { 10_000 })
        assertFailsWith<IOException> { store.loadCheckpoint() }
        assertFailsWith<IOException> { store.saveCheckpoint(IncidentCheckpoint()) }
        assertEquals("{malformed", Files.readString(checkpoint, UTF_8))

        val archiveDirectory = directory.resolve("archives")
        Files.createDirectories(archiveDirectory)
        val malformedArchive = archiveDirectory.resolve("${id(1)}.json")
        Files.writeString(malformedArchive, "not-json", UTF_8)
        assertFailsWith<IOException> { store.loadArchive(id(1)) }
        assertTrue(WindowsIncidentReportsReader(directory).listArchives().truncated)
    }

    @Test
    fun oversizeFilesAndInvalidGeneratedIdsCannotBeReadOrWritten() = withDirectory { directory ->
        val store = WindowsIncidentStore(directory, nowMillis = { 10_000 })
        assertFailsWith<IllegalArgumentException> { store.saveArchive(archive("../escape", 1)) }
        Files.createDirectories(directory)
        Files.write(directory.resolve("incident-checkpoint.json"), ByteArray(4 * 1024 * 1024 + 1))
        assertFailsWith<IOException> { store.loadCheckpoint() }
    }

    @Test
    fun readOnlyListingIncludesActiveArchiveAndLeavesStagingUntouched() = withDirectory { directory ->
        val store = WindowsIncidentStore(directory, nowMillis = { 10_000 })
        val activeArchive = archive(id(2), timestamp = 12)
        store.saveCheckpoint(
            IncidentCheckpoint(
                sessionId = activeArchive.sessionId,
                active = ActiveIncident(activeArchive, lastFailureMillis = 12),
                lastEvent = activeArchive.trigger,
            ),
        )
        val stage = directory.resolve("incident-checkpoint.stage")
        Files.writeString(stage, "leave-this-staging-file", UTF_8)
        val checkpointBytes = Files.readAllBytes(directory.resolve("incident-checkpoint.json"))

        val reader = WindowsIncidentReportsReader(directory)
        val listing = reader.listArchives()
        val export = assertNotNull(reader.loadArchive(activeArchive.id))

        assertEquals(activeArchive.id, listing.summaries.single().id)
        assertTrue(listing.summaries.single().recording)
        assertTrue(export.recording)
        assertTrue(stage.toFile().exists())
        assertTrue(checkpointBytes.contentEquals(Files.readAllBytes(directory.resolve("incident-checkpoint.json"))))
        assertEquals("leave-this-staging-file", Files.readString(stage, UTF_8))
    }

    @Test
    fun observerTickSealsIncidentAfterMergeWindowWithoutAnotherProbe() = withDirectory { directory ->
        val store = WindowsIncidentStore(directory, nowMillis = { 10_000 })
        val history = ConnectivityHistory(directory.resolveSibling("connectivity"), incidentStore = store)
        history.record(observation(1, system = ProbeOutcome.FAILURE))
        assertTrue(store.listArchives().isEmpty())

        history.tick(capturedAtMillis = 300_001, monotonicMillis = 300_001)

        assertEquals(IncidentStatus.SUSPECTED, store.listArchives().single().status)
    }

    @Test
    fun probeEventsKeepSystemAndCorePathsAndAvailablePhaseDurations() = withDirectory { directory ->
        val store = WindowsIncidentStore(directory, nowMillis = { 10_000 })
        val history = ConnectivityHistory(directory.resolveSibling("connectivity"), incidentStore = store)
        history.record(
            ConnectivityObservation(
                capturedAtMillis = 1,
                monotonicMillis = 1,
                sessionId = SESSION_ID,
                runtimeAlive = true,
                transport = IncidentDiagnosticTransport.HYSTERIA2,
                systemProbe = ConnectivityProbe(
                    ProbeOutcome.SUCCESS, DiagnosticTarget.GSTATIC,
                    httpStatus = 204, dnsMs = 11, tcpMs = 22, tlsMs = 33, totalMs = 44,
                ),
                proxyProbe = ConnectivityProbe(
                    ProbeOutcome.FAILURE, DiagnosticTarget.GSTATIC,
                    curlExit = 28, dnsMs = 12, tcpMs = 23, tlsMs = 34, totalMs = 45,
                ),
            ),
        )

        val events = assertNotNull(store.loadCheckpoint()?.active).archive.events
        val system = events.single { it.kind == DiagnosticEventKind.PROBE_SUCCESS }
        val core = events.single { it.kind == DiagnosticEventKind.PROBE_FAILURE }
        assertEquals(DiagnosticPath.SYSTEM, system.path)
        assertEquals(IncidentDiagnosticTransport.HYSTERIA2, system.transport)
        assertEquals(11L, system.dnsMillis)
        assertEquals(22L, system.connectMillis)
        assertEquals(33L, system.tlsMillis)
        assertEquals(DiagnosticPath.CORE, core.path)
        assertEquals(IncidentDiagnosticTransport.HYSTERIA2, core.transport)
        assertEquals(12L, core.dnsMillis)
        assertEquals(23L, core.connectMillis)
        assertEquals(34L, core.tlsMillis)
    }

    @Test
    fun storageIoFailureDoesNotPreventLegacyHistoryFromRecording() = withDirectory { parent ->
        Files.createDirectories(parent)
        val blockedDirectory = parent.resolve("not-a-directory")
        Files.writeString(blockedDirectory, "blocked", UTF_8)
        val connectivity = parent.resolve("connectivity")
        val history = ConnectivityHistory(
            connectivity,
            incidentStore = WindowsIncidentStore(blockedDirectory.resolve("incidents"), nowMillis = { 10_000 }),
        )

        assertFailsWith<IOException> { history.record(observation(1, system = ProbeOutcome.FAILURE)) }

        assertTrue(Files.exists(connectivity.resolve("connectivity-samples.jsonl")))
        assertTrue(Files.readString(connectivity.resolve("connectivity-samples.jsonl"), UTF_8).contains(SESSION_ID))
    }

    private fun archive(id: String, timestamp: Long): IncidentArchive {
        val trigger = DiagnosticEvent(
            timestampMillis = timestamp,
            monotonicMillis = timestamp,
            sessionId = SESSION_ID,
            kind = DiagnosticEventKind.RUNTIME_FAILURE,
            target = IncidentDiagnosticTarget.NONE,
        )
        return IncidentArchive(
            id = id,
            sessionId = SESSION_ID,
            trigger = trigger,
            status = IncidentStatus.CONFIRMED,
            recovered = false,
            truncated = false,
            events = listOf(trigger),
        )
    }

    private fun probeArchive(id: String, timestamp: Long, confirmed: Boolean): IncidentArchive {
        val failed = DiagnosticEvent(
            timestampMillis = timestamp,
            monotonicMillis = timestamp,
            sessionId = SESSION_ID,
            kind = DiagnosticEventKind.PROBE_FAILURE,
            target = IncidentDiagnosticTarget.GSTATIC,
            path = DiagnosticPath.CORE,
        )
        val evidence = if (confirmed) listOf(
            failed,
            failed.copy(
                timestampMillis = timestamp + 1,
                monotonicMillis = timestamp + 1,
                target = IncidentDiagnosticTarget.YOUTUBE,
            ),
        ) else listOf(failed)
        return IncidentArchive(
            id = id,
            sessionId = SESSION_ID,
            trigger = failed,
            status = if (confirmed) IncidentStatus.RECOVERED else IncidentStatus.SUSPECTED,
            recovered = confirmed,
            truncated = false,
            events = evidence,
        )
    }

    private fun observation(
        at: Long,
        runtimeAlive: Boolean = true,
        system: ProbeOutcome = ProbeOutcome.SUCCESS,
    ) = ConnectivityObservation(
        capturedAtMillis = at,
        monotonicMillis = at,
        sessionId = SESSION_ID,
        runtimeAlive = runtimeAlive,
        systemProbe = ConnectivityProbe(system, DiagnosticTarget.GSTATIC),
        proxyProbe = ConnectivityProbe(ProbeOutcome.UNAVAILABLE, DiagnosticTarget.YOUTUBE),
    )

    private fun id(value: Int): String = value.toString(16).padStart(32, '0')

    private fun withDirectory(block: (Path) -> Unit) {
        val parent = Files.createTempDirectory("windows-incident-test")
        try {
            block(parent.resolve("incidents"))
        } finally {
            Files.walk(parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private companion object {
        val SESSION_ID = "a".repeat(64)
    }
}
