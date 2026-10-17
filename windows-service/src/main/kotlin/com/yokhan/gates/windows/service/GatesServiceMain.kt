package com.yokhan.gates.windows.service

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun main() {
    val paths = WindowsServicePathContract.paths()
    val controlToken = java.nio.file.Files.readString(paths.controlTokenFile, Charsets.US_ASCII).trim()
    require(controlToken.matches(Regex("[0-9a-f]{64}"))) { "GATES Runtime control token is unavailable" }
    val supervisor = SerializedTunnelSupervisor(SingBoxProcessLauncher(paths))
    runCatching(supervisor::restorePersisted)
    val connectivity = ConnectivityMonitor(
        supervisor::diagnosticsSnapshot,
        WindowsCurlConnectivityRunner(),
        ConnectivityHistory(
            paths.dataDirectory.resolve("connectivity"),
            incidentStore = WindowsIncidentStore(paths.dataDirectory.resolve("connectivity").resolve("incidents")),
        ),
    )
    val connectivityProbeExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "gates-connectivity-probes").apply { isDaemon = true }
    }
    connectivityProbeExecutor.scheduleWithFixedDelay(connectivity::sample, 5, 60, TimeUnit.SECONDS)
    val connectivityObserverExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "gates-connectivity-observer").apply { isDaemon = true }
    }
    connectivityObserverExecutor.scheduleWithFixedDelay(connectivity::observeRuntime, 1, 1, TimeUnit.SECONDS)
    val monitor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "gates-tunnel-monitor").apply { isDaemon = true }
    }
    monitor.scheduleWithFixedDelay(
        {
            connectivity.captureRuntime()
            runCatching(supervisor::reconcile)
        },
        MONITOR_INTERVAL_SECONDS,
        MONITOR_INTERVAL_SECONDS,
        TimeUnit.SECONDS,
    )
    Runtime.getRuntime().addShutdownHook(Thread {
        monitor.shutdownNow()
        connectivityProbeExecutor.shutdownNow()
        connectivityObserverExecutor.shutdownNow()
        supervisor.shutdown()
        connectivity.endSession()
    })
    LoopbackControlServer(SerializedServiceHandler(supervisor, controlToken)).use { server ->
        server.runForever()
    }
}

private const val MONITOR_INTERVAL_SECONDS = 2L
