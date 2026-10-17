package com.yokhan.gates.windows.service

import com.yokhan.gates.shared.domain.diagnostics.DiagnosticEventKind
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticTransport as IncidentDiagnosticTransport
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectivityMonitorTest {
    private val config = ValidatedSingBoxConfig("""{"inbounds":[{"type":"mixed","tag":"mixed-in","listen":"127.0.0.1","listen_port":2088}]}""")
    private val session = "a".repeat(64)

    @Test
    fun idleServiceDoesNotStartAnythingOrSendNetworkProbes() {
        var probes = 0
        val directory = Files.createTempDirectory("gates-idle-diag").resolve("history")
        val monitor = ConnectivityMonitor({ null }, { target, _ ->
            probes++
            ConnectivityProbe(ProbeOutcome.SUCCESS, target)
        }, ConnectivityHistory(directory))
        monitor.captureRuntime()
        monitor.observeRuntime()
        monitor.sample()
        assertEquals(0, probes)
        assertFalse(Files.exists(directory))
    }

    @Test
    fun probesUseOnlyTheConfiguredLoopbackPortAndNeverMutateRuntime() {
        val requests = mutableListOf<Int?>()
        val history = ConnectivityHistory(Files.createTempDirectory("gates-monitor"))
        val monitor = ConnectivityMonitor({ TunnelDiagnosticSnapshot(session, true, config) }, { target, port ->
            requests += port
            ConnectivityProbe(ProbeOutcome.SUCCESS, target, httpStatus = 204)
        }, history)
        monitor.sample()
        assertEquals(listOf(null, 2088), requests)
    }

    @Test
    fun liveRuntimeSessionIsPersistedBeforeTheFirstProbe() {
        val directory = Files.createTempDirectory("gates-session-start")
        val store = WindowsIncidentStore(directory.resolve("incidents"))
        val hyConfig = ValidatedSingBoxConfig(
            """{"inbounds":[{"type":"tun"}],"outbounds":[{"type":"hysteria2","tag":"hy"}],"route":{"final":"hy"}}""",
        )
        val monitor = ConnectivityMonitor(
            { TunnelDiagnosticSnapshot(session, true, hyConfig) },
            { _, _ -> error("No probe expected") },
            ConnectivityHistory(directory.resolve("history"), incidentStore = store),
        )

        monitor.captureRuntime()
        monitor.observeRuntime()

        val checkpoint = store.loadCheckpoint()
        val started = checkpoint?.ring?.singleOrNull { it.kind == DiagnosticEventKind.SESSION_STARTED }
        assertEquals(IncidentDiagnosticTransport.HYSTERIA2, started?.transport)
        assertNull(checkpoint?.active)
    }

    @Test
    fun transportResolverUsesConcreteFinalOrUnambiguousCandidateFamilyOnly() {
        fun config(finalTag: String, outbounds: String) = ValidatedSingBoxConfig(
            """{"inbounds":[{"type":"tun"}],"outbounds":$outbounds,"route":{"final":"$finalTag"}}""",
        )

        assertEquals(
            IncidentDiagnosticTransport.HYSTERIA2,
            diagnosticTransport(config("hy", """[{"type":"hysteria2","tag":"hy"}]""")),
        )
        assertEquals(
            IncidentDiagnosticTransport.VLESS,
            diagnosticTransport(config("vless", """[{"type":"vless","tag":"vless"}]""")),
        )
        assertEquals(
            IncidentDiagnosticTransport.HYSTERIA2,
            diagnosticTransport(config("auto", """[
                {"type":"selector","tag":"auto","default":"hy-a","outbounds":["hy-a","hy-b"]},
                {"type":"hysteria2","tag":"hy-a"},
                {"type":"hysteria2","tag":"hy-b"}
            ]""")),
        )
        assertEquals(
            IncidentDiagnosticTransport.UNKNOWN,
            diagnosticTransport(config("auto", """[
                {"type":"selector","tag":"auto","default":"hy","outbounds":["hy","vless"]},
                {"type":"hysteria2","tag":"hy"},
                {"type":"vless","tag":"vless"}
            ]""")),
        )
    }

    @Test
    fun resultsFromStoppedOrReplacedSessionsAreDiscarded() {
        val directory = Files.createTempDirectory("gates-stale-diag").resolve("history")
        var current: TunnelDiagnosticSnapshot? = TunnelDiagnosticSnapshot(session, true, config)
        val monitor = ConnectivityMonitor({ current }, { target, _ ->
            current = null
            ConnectivityProbe(ProbeOutcome.FAILURE, target, curlExit = 28)
        }, ConnectivityHistory(directory))
        monitor.sample()
        assertFalse(Files.exists(directory))
    }

    @Test
    fun runtimeDeathIsRecordedBeforeRecoveryWithoutNetworkProbes() {
        val history = ConnectivityHistory(Files.createTempDirectory("gates-dead-diag"))
        val monitor = ConnectivityMonitor({ TunnelDiagnosticSnapshot(session, false, config) }, { _, _ ->
            error("A dead runtime must not initiate probes")
        }, history)
        monitor.captureRuntime()
        monitor.observeRuntime()
        assertTrue(history.recentEvents().contains("RUNTIME_DOWN"))
    }

    @Test
    fun diagnosticCaptureCannotWaitForBlockedPersistenceOnTheObserverThread() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val history = ConnectivityHistory(Files.createTempDirectory("gates-blocked-diagnostics"))
        val monitor = ConnectivityMonitor({ TunnelDiagnosticSnapshot(session, false, config) },
            { _, _ -> error("No probe expected") }, history, recordObservation = {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            })
        monitor.captureRuntime()
        val observer = thread { monitor.observeRuntime() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val capture = FutureTask { repeat(40) { monitor.captureRuntime() } }
            val captureThread = thread { capture.run() }
            capture.get(1, TimeUnit.SECONDS)
            captureThread.join(1_000)
        } finally {
            release.countDown()
            observer.join(3_000)
        }
        assertFalse(observer.isAlive)
    }

    @Test
    fun capturedRuntimeFailureKeepsItsOriginalSessionAfterAUserSwitch() {
        var current = TunnelDiagnosticSnapshot(session, false, config)
        val recorded = mutableListOf<ConnectivityObservation>()
        val monitor = ConnectivityMonitor({ current }, { _, _ -> error("No probe expected") },
            ConnectivityHistory(Files.createTempDirectory("gates-queued-session")),
            recordObservation = { recorded += it })
        monitor.captureRuntime()
        current = TunnelDiagnosticSnapshot("b".repeat(64), true, config)
        monitor.observeRuntime()
        assertEquals(session, recorded.single().sessionId)
        assertFalse(recorded.single().runtimeAlive)
    }

    @Test
    fun blockedProbeDoesNotPreventRuntimeDeathFromBeingRecorded() {
        val probeEntered = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val history = ConnectivityHistory(Files.createTempDirectory("gates-slow-probe"))
        var current: TunnelDiagnosticSnapshot? = TunnelDiagnosticSnapshot(session, true, config)
        val monitor = ConnectivityMonitor({ current }, { target, _ ->
            probeEntered.countDown()
            check(releaseProbe.await(3, TimeUnit.SECONDS))
            ConnectivityProbe(ProbeOutcome.SUCCESS, target)
        }, history)

        val probe = thread { monitor.sample() }
        try {
            assertTrue(probeEntered.await(1, TimeUnit.SECONDS))
            current = TunnelDiagnosticSnapshot(session, false, config)
            monitor.captureRuntime()
            val observer = FutureTask { monitor.observeRuntime() }
            thread { observer.run() }
            observer.get(1, TimeUnit.SECONDS)
            assertTrue(history.recentEvents().contains("RUNTIME_DOWN"))
        } finally {
            releaseProbe.countDown()
            probe.join(3_000)
        }
        assertFalse(probe.isAlive)
    }

    @Test
    fun readOrWriteFailuresCannotEscapeToTheTunnelSupervisor() {
        var unavailable = 0
        val monitor = ConnectivityMonitor({ error("synthetic snapshot failure") }, { _, _ -> error("unexpected") },
            ConnectivityHistory(Files.createTempDirectory("gates-diag-error")), onUnavailable = { unavailable++ })
        monitor.sample()
        monitor.sample()
        assertEquals(1, unavailable)
    }

    @Test
    fun incidentRecorderInitializationFailureIsReportedByObserver() {
        val directory = Files.createTempDirectory("gates-diag-store-error")
        val blockedStoreRoot = directory.resolve("not-a-directory")
        Files.writeString(blockedStoreRoot, "blocked")
        var unavailable = 0
        val monitor = ConnectivityMonitor(
            { TunnelDiagnosticSnapshot(session, true, config) },
            { _, _ -> error("No probe expected") },
            ConnectivityHistory(
                directory.resolve("history"),
                incidentStore = WindowsIncidentStore(blockedStoreRoot),
            ),
            onUnavailable = { unavailable++ },
        )

        monitor.captureRuntime()
        monitor.observeRuntime()

        assertEquals(1, unavailable)
    }

    @Test
    fun proxyProbeDoesNotUseLanListenersAuthenticationOrUnrelatedPorts() {
        assertEquals(2088, diagnosticProxyPort(config))
        assertNull(diagnosticProxyPort(ValidatedSingBoxConfig(config.canonicalJson.replace("127.0.0.1", "0.0.0.0"))))
        assertNull(diagnosticProxyPort(ValidatedSingBoxConfig(config.canonicalJson.replace("mixed-in", "other"))))
        assertNull(diagnosticProxyPort(ValidatedSingBoxConfig(config.canonicalJson.replace("2088", "99999"))))
    }

    @Test
    fun curlMetricsPreserveFailedPhaseInsteadOfCallingAProcessHealthy() {
        val failed = parseConnectivityCurl(DiagnosticTarget.YOUTUBE, 28, "000 0.0 0.0 0.0 3.001")
        assertEquals(ProbeOutcome.FAILURE, failed.outcome)
        assertEquals(3001L, failed.totalMs)
        assertEquals(0L, failed.dnsMs)
        val healthy = parseConnectivityCurl(DiagnosticTarget.GSTATIC, 0, "204 0.01 0.02 0.2 0.3")
        assertEquals(ProbeOutcome.SUCCESS, healthy.outcome)
        assertEquals(10L, healthy.dnsMs)
        assertEquals(200L, healthy.tlsMs)
        assertEquals(ProbeOutcome.FAILURE, parseConnectivityCurl(DiagnosticTarget.GSTATIC, 0, "200 0 0 0 1").outcome)
        assertEquals(ProbeOutcome.UNAVAILABLE, parseConnectivityCurl(DiagnosticTarget.GSTATIC, 0, "malformed").outcome)
        assertEquals(ProbeOutcome.UNAVAILABLE, parseConnectivityCurl(DiagnosticTarget.GSTATIC, 0, "204 NaN 0 0 0").outcome)
    }
}
