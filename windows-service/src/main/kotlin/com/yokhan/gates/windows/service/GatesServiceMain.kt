package com.yokhan.gates.windows.service

import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(runGatesServiceProcess(args))
}

fun runGatesServiceProcess(args: Array<String>): Int {
    if (args.contentEquals(arrayOf("--cleanup-protection"))) {
        try {
            cleanupSelectedProtection(WindowsServicePathContract.paths(), ::gatesVpnServiceIsRunning)
        } catch (_: Exception) {
            System.err.println("GATES selected-app protection cleanup failed")
            return 1
        }
        return 0
    }
    if (args.isNotEmpty()) {
        System.err.println("Unsupported GATES service argument")
        return 2
    }
    if (!isGatesRuntimeServiceIdentitySystem()) {
        System.err.println("GATES service mode requires the Windows LocalSystem identity")
        return 5
    }
    return try {
        runService()
        0
    } catch (_: Exception) {
        System.err.println("GATES Runtime service failed to start")
        1
    }
}

fun isGatesRuntimeServiceIdentitySystem(): Boolean =
    runCatching { isLocalSystemSid(currentWindowsTokenSid()) }.getOrDefault(false)

internal fun isLocalSystemSid(sid: String?): Boolean = sid == WINDOWS_LOCAL_SYSTEM_SID

private fun currentWindowsTokenSid(): String {
    val tokenReference = WinNT.HANDLEByReference()
    check(Advapi32.INSTANCE.OpenProcessToken(
        Kernel32.INSTANCE.GetCurrentProcess(),
        WinNT.TOKEN_QUERY,
        tokenReference,
    ))
    val token = requireNotNull(tokenReference.value)
    return try {
        Advapi32Util.getTokenAccount(token).sidString
    } finally {
        Kernel32.INSTANCE.CloseHandle(token)
    }
}

private fun runService() {
    val lifecycle = GatesServiceLifecycle()
    val shutdownHook = Thread({
        runCatching(lifecycle::close).onFailure { System.err.println("GATES service shutdown cleanup failed") }
    }, "gates-service-shutdown")
    Runtime.getRuntime().addShutdownHook(shutdownHook)
    try {
        lifecycle.run {
            val paths = WindowsServicePathContract.paths()
            val controlToken = java.nio.file.Files.readString(paths.controlTokenFile, Charsets.US_ASCII).trim()
            require(controlToken.matches(Regex("[0-9a-f]{64}"))) { "GATES Runtime control token is unavailable" }
            val protection = WfpSelectedAppProtection(
                coreExecutablePath = paths.runtimeExecutable.toString(),
                installationAlias = selectedProtectionAlias(controlToken),
            )
            val serviceProcessExecutablePath = ProcessHandle.current().info().command().orElse(null)?.let {
                runCatching { java.nio.file.Path.of(it).toAbsolutePath().normalize().toString() }.getOrNull()
            }
            val xraySidecar = own(XrayProcessSidecar(paths)) { it.stop() }
            val supervisor = own(
                SerializedTunnelSupervisor(
                    SingBoxProcessLauncher(
                        paths,
                        protection,
                        selectedProtectionAlias(controlToken),
                        serviceProcessExecutablePath,
                        xraySidecar,
                    ),
                ),
            ) { it.shutdown() }
            runCatching(supervisor::restorePersisted)
            val connectivity = ConnectivityMonitor(
                supervisor::diagnosticsSnapshot,
                WindowsCurlConnectivityRunner(),
                ConnectivityHistory(
                    paths.dataDirectory.resolve("connectivity"),
                    incidentStore = WindowsIncidentStore(paths.dataDirectory.resolve("connectivity").resolve("incidents")),
                ),
            )
            own(connectivity) { it.endSession() }
            val connectivityProbeExecutor = own(
                Executors.newSingleThreadScheduledExecutor { task ->
                    Thread(task, "gates-connectivity-probes").apply { isDaemon = true }
                },
            ) { it.shutdownNow() }
            connectivityProbeExecutor.scheduleWithFixedDelay(connectivity::sample, 5, 60, TimeUnit.SECONDS)
            val connectivityObserverExecutor = own(
                Executors.newSingleThreadScheduledExecutor { task ->
                    Thread(task, "gates-connectivity-observer").apply { isDaemon = true }
                },
            ) { it.shutdownNow() }
            connectivityObserverExecutor.scheduleWithFixedDelay(connectivity::observeRuntime, 1, 1, TimeUnit.SECONDS)
            val monitor = own(
                Executors.newSingleThreadScheduledExecutor { task ->
                    Thread(task, "gates-tunnel-monitor").apply { isDaemon = true }
                },
            ) { it.shutdownNow() }
            monitor.scheduleWithFixedDelay(
                {
                    connectivity.captureRuntime()
                    runCatching(supervisor::reconcile)
                },
                MONITOR_INTERVAL_SECONDS,
                MONITOR_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )
            own(LoopbackControlServer(SerializedServiceHandler(supervisor, controlToken))) { it.close() }
                .runForever()
        }
    } finally {
        runCatching(lifecycle::close)
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }
}

internal class GatesServiceLifecycle {
    private val lock = Any()
    private val cleanups = mutableListOf<() -> Unit>()
    private var closing = false
    @Volatile private var closeFailure: Throwable? = null
    private val closeCompleted = CountDownLatch(1)

    fun <T> own(resource: T, cleanup: (T) -> Unit): T {
        val cleanupNow = synchronized(lock) {
            if (closing) true else {
                cleanups.add { cleanup(resource) }
                false
            }
        }
        if (cleanupNow) {
            cleanup(resource)
            error("GATES service is shutting down")
        }
        return resource
    }

    fun <T> run(start: GatesServiceLifecycle.() -> T): T {
        var startupFailure: Throwable? = null
        try {
            return start()
        } catch (failure: Throwable) {
            startupFailure = failure
            throw failure
        } finally {
            try {
                close()
            } catch (cleanupFailure: Throwable) {
                if (startupFailure != null) startupFailure.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }

    fun close() {
        val pending = synchronized(lock) {
            if (closing) null else {
                closing = true
                cleanups.asReversed().toList().also { cleanups.clear() }
            }
        }
        if (pending == null) {
            closeCompleted.await()
            closeFailure?.let { throw it }
            return
        }
        val failures = pending.mapNotNull { cleanup -> runCatching(cleanup).exceptionOrNull() }
        closeFailure = if (failures.isEmpty()) null else {
            IllegalStateException("GATES service resource cleanup failed").also { failure ->
                failures.forEach(failure::addSuppressed)
            }
        }
        closeCompleted.countDown()
        closeFailure?.let { throw it }
    }
}

private const val MONITOR_INTERVAL_SECONDS = 2L
private const val WINDOWS_LOCAL_SYSTEM_SID = "S-1-5-18"
