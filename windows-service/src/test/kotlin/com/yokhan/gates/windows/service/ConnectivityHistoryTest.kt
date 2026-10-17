package com.yokhan.gates.windows.service

import com.google.gson.JsonParser
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectivityHistoryTest {

    @Test
    fun exposesFirstFailureThenConfirmsAfterSecondFailureAndRecoversAfterTwoSuccesses() = withHistory { history, dir ->
        history.record(observation(at = 1, system = ProbeOutcome.FAILURE))
        assertEquals(listOf("SUSPECTED"), transitions(history))

        history.record(observation(at = 2, system = ProbeOutcome.FAILURE))
        assertEquals(listOf("SUSPECTED", "CONFIRMED"), transitions(history))

        history.record(observation(at = 3))
        assertEquals(listOf("SUSPECTED", "CONFIRMED"), transitions(history))
        history.record(observation(at = 4))
        assertEquals(listOf("SUSPECTED", "CONFIRMED", "RECOVERED"), transitions(history))
        assertTrue(Files.exists(dir.resolve("connectivity-samples.jsonl")))
    }

    @Test
    fun unavailableResetsConsecutiveFailureAndRecoveryCounts() = withHistory { history, dir ->
        history.record(observation(at = 1, system = ProbeOutcome.FAILURE))
        history.record(observation(at = 2, system = ProbeOutcome.UNAVAILABLE))
        assertEquals(listOf("SUSPECTED"), transitions(history))
        val unknownSample = Files.readAllLines(dir.resolve("connectivity-samples.jsonl"), UTF_8)[1]
        assertFalse(JsonParser.parseString(unknownSample).asJsonObject.has("category"))

        history.record(observation(at = 3, system = ProbeOutcome.FAILURE))
        assertEquals(listOf("SUSPECTED"), transitions(history))
        history.record(observation(at = 4, system = ProbeOutcome.FAILURE))
        assertEquals(listOf("SUSPECTED", "CONFIRMED"), transitions(history))

        history.record(observation(at = 5))
        history.record(observation(at = 6, proxy = ProbeOutcome.UNAVAILABLE))
        history.record(observation(at = 7))
        assertEquals(listOf("SUSPECTED", "CONFIRMED"), transitions(history))
        history.record(observation(at = 8))
        assertEquals(listOf("SUSPECTED", "CONFIRMED", "RECOVERED"), transitions(history))
    }

    @Test
    fun sessionChangeAndExplicitEndCloseIncidentsWithoutRecovery() = withHistory { history, _ ->
        val firstSession = "a".repeat(64)
        val secondSession = "b".repeat(64)
        history.record(observation(at = 1, session = firstSession, proxy = ProbeOutcome.FAILURE))
        history.record(observation(at = 2, session = secondSession, proxy = ProbeOutcome.FAILURE))

        val eventsAfterChange = JsonParser.parseString(history.recentEvents()).asJsonArray
        assertEquals(
            listOf("SUSPECTED", "SESSION_ENDED", "SUSPECTED"),
            eventsAfterChange.map { it.asJsonObject.get("transition").asString },
        )
        assertEquals(firstSession, eventsAfterChange[1].asJsonObject.get("sessionId").asString)

        history.endSession(capturedAtMillis = 3, monotonicMillis = 3)
        assertEquals(
            listOf("SUSPECTED", "SESSION_ENDED", "SUSPECTED", "SESSION_ENDED"),
            transitions(history),
        )
        assertFalse(transitions(history).contains("RECOVERED"))
    }

    @Test
    fun runtimeDeathConfirmsImmediatelyAndUsesMonotonicDurationClampedAtZero() = withHistory { history, _ ->
        history.record(observation(at = 1, monotonic = 100, system = ProbeOutcome.FAILURE))
        history.record(observation(at = 2, monotonic = 90, alive = false))

        val events = JsonParser.parseString(history.recentEvents()).asJsonArray
        assertEquals(listOf("SUSPECTED", "CONFIRMED"), events.map {
            it.asJsonObject.get("transition").asString
        })
        assertEquals("RUNTIME_DOWN", events[1].asJsonObject.get("category").asString)
        assertEquals(0L, events[1].asJsonObject.get("durationMillis").asLong)

        history.endSession(capturedAtMillis = 3, monotonicMillis = 80)
        val ended = JsonParser.parseString(history.recentEvents()).asJsonArray.last().asJsonObject
        assertEquals("SESSION_ENDED", ended.get("transition").asString)
        assertEquals(0L, ended.get("durationMillis").asLong)
    }

    @Test
    fun rotatesWithinTwoFilesPerJournalAndConfiguredAggregateBound() = withHistory(maxFileBytes = 1_024) { history, dir ->
        repeat(30) { index ->
            val at = index * 4L
            history.record(observation(at = at + 1, system = ProbeOutcome.FAILURE))
            history.record(observation(at = at + 2, system = ProbeOutcome.FAILURE))
            history.record(observation(at = at + 3))
            history.record(observation(at = at + 4))
        }

        val files = Files.list(dir).use { stream -> stream.toList() }
        assertEquals(
            setOf(
                "connectivity-samples.jsonl",
                "connectivity-samples.previous.jsonl",
                "connectivity-events.jsonl",
                "connectivity-events.previous.jsonl",
            ),
            files.map { it.fileName.toString() }.toSet(),
        )
        assertTrue(files.all { Files.size(it) <= 1_024L })
        assertTrue(files.sumOf(Files::size) <= 4_096L)
    }

    @Test
    fun recentEventsIsReadOnlyBoundedAndRewhitelistsTruncatedOrInjectedLines() = withHistory { history, dir ->
        assertFalse(Files.exists(dir))
        assertEquals("[]", history.recentEvents())
        assertFalse(Files.exists(dir))

        history.record(observation(at = 1, system = ProbeOutcome.FAILURE))
        val eventFile = dir.resolve("connectivity-events.jsonl")
        Files.writeString(
            eventFile,
            """{"transition":"CONFIRMED","capturedAtMillis":2,"sessionId":"${"c".repeat(64)}","category":"SYSTEM_PATH","durationMillis":1,"extra":"private-config-value","systemProbe":{"outcome":"FAILURE","target":"GSTATIC","curlExit":28,"httpStatus":0,"dnsMs":4,"tcpMs":5,"tlsMs":6,"totalMs":7,"raw":"secret"},"proxyProbe":{"outcome":"SUCCESS","target":"YOUTUBE","httpStatus":200,"totalMs":8,"raw":"secret"}}""" +
                "\n{\"transition\":\"CONFIRMED\"",
            UTF_8,
            APPEND,
        )

        val result = history.recentEvents(2_048)
        val array = JsonParser.parseString(result).asJsonArray
        assertEquals(listOf("SUSPECTED", "CONFIRMED"), array.map {
            it.asJsonObject.get("transition").asString
        })
        assertFalse(result.contains("extra"))
        assertFalse(result.contains("private-config-value"))
        assertFalse(result.contains("raw"))
        assertFalse(result.contains("secret"))
        assertTrue(array[1].asJsonObject.getAsJsonObject("systemProbe").get("httpStatus").asInt == 0)
        assertTrue(result.toByteArray(UTF_8).size <= 2_048)
        assertFailsWith<IllegalArgumentException> { history.recentEvents(1) }
        assertFailsWith<IllegalArgumentException> { history.recentEvents(65_537) }
    }

    @Test
    fun rejectsInvalidSessionIdentifiersAndNegativeClockOrDurationValues() = withHistory { history, _ ->
        assertFailsWith<IllegalArgumentException> { history.record(observation(at = 1, session = "ABC")) }
        assertFailsWith<IllegalArgumentException> { history.record(observation(at = -1)) }
        assertFailsWith<IllegalArgumentException> {
            history.record(observation(at = 1, system = ProbeOutcome.FAILURE, totalMs = -1))
        }
    }

    @Test
    fun transitionJournalKeepsFirstProbeEvidenceAfterSamplesRotate() = withHistory(maxFileBytes = 1_024) { history, dir ->
        history.record(
            observation(
                at = 1,
                system = ProbeOutcome.FAILURE,
                curlExit = 28,
                httpStatus = 0,
                dnsMs = 7,
                tcpMs = 11,
                tlsMs = 13,
                totalMs = 31,
            ),
        )
        repeat(40) { index -> history.record(observation(at = index + 2L)) }

        val retainedSamples = listOf("connectivity-samples.jsonl", "connectivity-samples.previous.jsonl")
            .mapNotNull { name -> dir.resolve(name).takeIf { Files.exists(it) } }
            .joinToString("\n") { Files.readString(it, UTF_8) }
        assertFalse(retainedSamples.contains("\"capturedAtMillis\":1"))

        val firstEvent = JsonParser.parseString(history.recentEvents()).asJsonArray.first().asJsonObject
        val firstSystemProbe = firstEvent.getAsJsonObject("systemProbe")
        assertEquals(0, firstSystemProbe.get("httpStatus").asInt)
        assertEquals(28, firstSystemProbe.get("curlExit").asInt)
        assertEquals(7L, firstSystemProbe.get("dnsMs").asLong)
        assertEquals(11L, firstSystemProbe.get("tcpMs").asLong)
        assertEquals(13L, firstSystemProbe.get("tlsMs").asLong)
        assertEquals(31L, firstSystemProbe.get("totalMs").asLong)
    }

    @Test
    fun retriesFailedConfirmationBeforeAdvancingTheNextObservation() = withHistory { history, dir ->
        history.record(observation(at = 1, system = ProbeOutcome.FAILURE))
        withBlockedHistoryDirectory(dir) {
            assertFailsWith<IOException> {
                history.record(observation(at = 2, system = ProbeOutcome.FAILURE, totalMs = 31))
            }
            assertFailsWith<IOException> { history.record(observation(at = 3)) }
        }

        history.record(observation(at = 4))
        assertEquals(listOf("SUSPECTED", "CONFIRMED"), transitions(history))
        val confirmed = JsonParser.parseString(history.recentEvents()).asJsonArray[1].asJsonObject
        assertEquals(2L, confirmed.get("capturedAtMillis").asLong)
        assertEquals(31L, confirmed.getAsJsonObject("systemProbe").get("totalMs").asLong)

        history.record(observation(at = 5))
        assertEquals(listOf("SUSPECTED", "CONFIRMED", "RECOVERED"), transitions(history))
    }

    @Test
    fun retriesFailedSessionEndBeforeRecordingTheNextSession() = withHistory { history, dir ->
        history.record(observation(at = 1, system = ProbeOutcome.FAILURE))
        withBlockedHistoryDirectory(dir) {
            assertFailsWith<IOException> {
                history.endSession(capturedAtMillis = 2, monotonicMillis = 2)
            }
        }

        history.record(observation(at = 3, session = "2".repeat(64), proxy = ProbeOutcome.FAILURE))
        assertEquals(listOf("SUSPECTED", "SESSION_ENDED", "SUSPECTED"), transitions(history))
        val ended = JsonParser.parseString(history.recentEvents()).asJsonArray[1].asJsonObject
        assertEquals(2L, ended.get("capturedAtMillis").asLong)
        assertEquals("1".repeat(64), ended.get("sessionId").asString)
    }

    @Test
    fun persistsTransitionsEvenWhenTheSampleJournalCannotBeWritten() = withHistory { history, dir ->
        Files.createDirectories(dir.resolve("connectivity-samples.jsonl"))
        assertFailsWith<IOException> { history.record(observation(at = 1, system = ProbeOutcome.FAILURE)) }
        assertEquals(listOf("SUSPECTED"), transitions(history))
        assertFailsWith<IOException> { history.record(observation(at = 2, system = ProbeOutcome.FAILURE)) }
        assertEquals(listOf("SUSPECTED", "CONFIRMED"), transitions(history))
    }

    @Test
    fun separatesAnIncompleteJournalLineBeforeAppendingTheNextTransition() = withHistory { history, dir ->
        history.record(observation(at = 1, system = ProbeOutcome.FAILURE))
        Files.writeString(dir.resolve("connectivity-events.jsonl"), "{\"transition\":", UTF_8, APPEND)

        history.record(observation(at = 2, system = ProbeOutcome.FAILURE))

        assertEquals(listOf("SUSPECTED", "CONFIRMED"), transitions(history))
        val confirmed = JsonParser.parseString(history.recentEvents()).asJsonArray[1].asJsonObject
        assertEquals(2L, confirmed.get("capturedAtMillis").asLong)
    }

    private fun withBlockedHistoryDirectory(directory: Path, block: () -> Unit) {
        val savedDirectory = directory.resolveSibling("history-saved")
        Files.move(directory, savedDirectory)
        Files.writeString(directory, "temporarily unavailable", UTF_8)
        try {
            block()
        } finally {
            Files.delete(directory)
            Files.move(savedDirectory, directory)
        }
    }

    private fun transitions(history: ConnectivityHistory): List<String> =
        JsonParser.parseString(history.recentEvents()).asJsonArray.map {
            it.asJsonObject.get("transition").asString
        }

    private fun observation(
        at: Long,
        monotonic: Long = at,
        session: String = "1".repeat(64),
        alive: Boolean = true,
        system: ProbeOutcome = ProbeOutcome.SUCCESS,
        proxy: ProbeOutcome = ProbeOutcome.SUCCESS,
        curlExit: Int? = null,
        httpStatus: Int? = null,
        dnsMs: Long? = null,
        tcpMs: Long? = null,
        tlsMs: Long? = null,
        totalMs: Long? = 12,
    ) = ConnectivityObservation(
        capturedAtMillis = at,
        monotonicMillis = monotonic,
        sessionId = session,
        runtimeAlive = alive,
        systemProbe = ConnectivityProbe(
            system,
            DiagnosticTarget.GSTATIC,
            curlExit,
            httpStatus,
            dnsMs,
            tcpMs,
            tlsMs,
            totalMs,
        ),
        proxyProbe = ConnectivityProbe(
            proxy,
            DiagnosticTarget.YOUTUBE,
            curlExit,
            httpStatus,
            dnsMs,
            tcpMs,
            tlsMs,
            totalMs,
        ),
    )

    private fun withHistory(
        maxFileBytes: Int = 524_288,
        block: (ConnectivityHistory, Path) -> Unit,
    ) {
        val parent = Files.createTempDirectory("connectivity-history-test")
        val directory = parent.resolve("history")
        try {
            block(ConnectivityHistory(directory, maxFileBytes), directory)
        } finally {
            Files.walk(parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
