package com.yokhan.gates.windows

import java.nio.file.Files
import java.nio.file.Path
import com.yokhan.gates.windows.service.ConnectivityProbe
import com.yokhan.gates.windows.service.ConnectivityProbeRunner
import com.yokhan.gates.windows.service.DiagnosticTarget
import com.yokhan.gates.windows.service.ProbeOutcome
import com.yokhan.gates.windows.service.TunnelDiagnosticSnapshot
import com.yokhan.gates.windows.service.ValidatedSingBoxConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsRecoveryDeliveryTest {
    @Test
    fun optOutKeepsLocalIncidentAndSendsAfterReenable() = withTempDirectory { root ->
        val sent = mutableListOf<ByteArray>()
        val sender: (ByteArray) -> Boolean = { sent += it; true }
        var elapsed = 10_000L
        val delivery = WindowsRecoveryDelivery(root.toFile(), null, coreVersion = "1.13.12",
            elapsedMillis = { elapsed }, send = sender)

        delivery.recordFailure(RecoveryFailureStage.CORE_STARTUP)
        delivery.runOnce(enabled = false)
        assertTrue(sent.isEmpty())
        elapsed += 300_001
        delivery.runOnce(enabled = false)
        assertTrue(Files.exists(root.resolve("recovery/incidents/archives")))

        WindowsRecoveryDelivery(root.toFile(), null, coreVersion = "1.13.12", send = sender).runOnce(enabled = true)
        assertEquals(1, sent.size)
        val payload = sent.single().toString(Charsets.UTF_8)
        assertContains(payload, "\"platform\":\"windows\"")
        assertContains(payload, "\"schema\":\"gates-recovery-v1\"")
        assertContains(payload, "\"app_version\":\"${windowsAppVersionForDisplay()}\"")
        assertContains(payload, "\"core_version\":\"1.13.12\"")
        assertContains(payload, "Automatic Windows connectivity recovery report.")
        assertContains(payload, "\"kind\":\"RUNTIME_FAILURE\"")
        assertFalse(payload.contains("exception"))
        assertTrue(sent.single().size <= 16 * 1024)
    }

    @Test
    fun retrySurvivesRestartWithStableReportIdAndNoRawErrorText() = withTempDirectory { root ->
        var now = 10_000L
        var elapsed = 10_000L
        val sent = mutableListOf<String>()
        val first = WindowsRecoveryDelivery(root.toFile(), null, nowMillis = { now }, elapsedMillis = { elapsed }) { payload ->
            sent += payload.toString(Charsets.UTF_8)
            false
        }
        first.recordFailure(RecoveryFailureStage.CORE_STARTUP)
        elapsed += 300_001
        first.runOnce(enabled = true)
        assertEquals(1, sent.size)

        now += 60_000
        val second = WindowsRecoveryDelivery(root.toFile(), null, nowMillis = { now }, coreVersion = "1.13.12") { payload ->
            sent += payload.toString(Charsets.UTF_8)
            true
        }
        second.runOnce(enabled = true)
        assertEquals(2, sent.size)
        val firstId = Regex("\"report_id\":\"([0-9a-f]{32,64})\"").find(sent[0])!!.groupValues[1]
        assertContains(sent[1], "\"report_id\":\"$firstId\"")
        assertFalse(sent[1].contains("password", ignoreCase = true))
        assertFalse(sent[1].contains("token", ignoreCase = true))
    }

    @Test
    fun corruptOutboxIsNotOverwritten() = withTempDirectory { root ->
        val bytes = "not-json".toByteArray()
        Files.write(root.resolve("recovery-delivery.json"), bytes)

        val delivery = WindowsRecoveryDelivery(root.toFile(), null)
        delivery.recordFailure(RecoveryFailureStage.CORE_STARTUP)
        assertFails { delivery.runOnce(enabled = true) }
        assertTrue(Files.readAllBytes(root.resolve("recovery-delivery.json")).contentEquals(bytes))
        assertTrue(Files.exists(root.resolve("recovery/incidents/incident-checkpoint.json")))
    }

    @Test
    fun successfulCommandsAndUntrackedCommandsDoNotCreateFailureReports() {
        assertEquals(null, windowsRecoveryFailureStage("connect", RuntimeCommandResult(0, "")))
        assertEquals(null, windowsRecoveryFailureStage("status", RuntimeCommandResult(1, "")))
        assertEquals(null, windowsRecoveryFailureStage("connect", RuntimeCommandResult(9, "login denied")))
        assertEquals(
            RecoveryFailureStage.CORE_STARTUP,
            windowsRecoveryFailureStage("connect", RuntimeCommandResult(1, "sing-box exited", RecoveryFailureStage.CORE_STARTUP)),
        )
    }

    @Test
    fun standaloneProxyFreezeIsRecordedWithCorePath() = withTempDirectory { root ->
        val sent = mutableListOf<ByteArray>()
        var elapsed = 10_000L
        val config = ValidatedSingBoxConfig(
            """{"inbounds":[{"type":"mixed","tag":"mixed-in","listen":"127.0.0.1","listen_port":2088}],"outbounds":[],"route":{}}""",
        )
        val runner = ConnectivityProbeRunner { target, proxyPort ->
            ConnectivityProbe(
                if (proxyPort == null) ProbeOutcome.SUCCESS else ProbeOutcome.FAILURE,
                target,
                curlExit = if (proxyPort == null) 0 else 7,
            )
        }
        val delivery = WindowsRecoveryDelivery(
            root.toFile(), null,
            elapsedMillis = { elapsed },
            send = { sent += it; true },
            standaloneSnapshot = { session -> TunnelDiagnosticSnapshot(session, true, config) },
            probeRunner = runner,
        )
        delivery.sampleStandaloneProxy()
        delivery.sampleStandaloneProxy()
        delivery.runOnce(enabled = true)
        assertTrue(sent.isEmpty(), "An active incident must not be acknowledged before recovery evidence is recorded")
        elapsed += 300_001
        delivery.runOnce(enabled = true)
        val payload = sent.single().toString(Charsets.UTF_8)
        assertContains(payload, "\"path\":\"CORE\"")
        assertContains(payload, "\"kind\":\"PROBE_FAILURE\"")
    }

    @Test
    fun unknownOutboxFieldsAreRejectedAndPreserved() = withTempDirectory { root ->
        val outbox = root.resolve("recovery-delivery.json")
        val session = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val bytes = """{"schema":"gates-recovery-delivery-v1","state":{"pending":[{"report":{"id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","sessionId":"$session","triggeredAtMillis":1,"status":"CONFIRMED","recovered":false,"truncated":false,"eventCount":1,"events":[{"timestampMillis":1,"monotonicMillis":1,"sessionId":"$session","kind":"RUNTIME_FAILURE","target":"NONE","transport":"UNKNOWN","network":"UNKNOWN","path":"CORE","future":true}]},"attempts":0,"lastAttemptMillis":0,"nextAttemptMillis":0}],"acknowledgedIds":[],"capacityReached":false}}""".toByteArray()
        Files.write(outbox, bytes)
        assertFails {
            WindowsRecoveryDelivery(root.toFile(), null).runOnce(enabled = true)
        }
        assertTrue(Files.readAllBytes(outbox).contentEquals(bytes))
    }

    @Test
    fun automaticUploadPreferenceDefaultsOnAndPersistsOptOut() = withTempDirectory { root ->
        val controller = WindowsRuntimeController(appDir = root.toFile())
        assertTrue(controller.readConfig().autoUploadRecoveryReports)
        controller.writeConfig(controller.readConfig().copy(autoUploadRecoveryReports = false))
        assertFalse(controller.readConfig().autoUploadRecoveryReports)
        Files.writeString(root.resolve("app-config.json"), """{"autoCheckUpdates":false}""")
        assertTrue(controller.readConfig().autoUploadRecoveryReports)
    }

    private inline fun withTempDirectory(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("gates-recovery-test-")
        try {
            block(root)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
