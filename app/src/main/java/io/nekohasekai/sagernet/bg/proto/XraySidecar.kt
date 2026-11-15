package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.fmt.xray.XrayRouteSpec
import java.io.File

/**
 * Android-side shape of DTP A3's `TunnelTransport`, reduced to what a sidecar egress needs today.
 * The adapter is an egress edge: it never owns TUN, DNS or system routes; sing-box stays the only
 * datapath owner (see `XrayConfigBuilder` for the executable allowlist).
 */
interface XraySidecar {
    val descriptor: XraySidecarDescriptor

    /** Idempotent while running; returns the existing epoch and endpoint. */
    fun start(spec: XrayRouteSpec, configDir: File): XrayStartResult

    /** Idempotent; must terminate the child and delete the config. */
    fun stop(reason: XrayStopReason)

    /** Cheap, side-effect-free snapshot. Never dials the network. */
    fun health(): XraySidecarHealth

    /** Cached; does not start or probe the engine. */
    fun version(): XrayTransportVersion

    /** Non-null only while running/degraded. */
    fun localSocks(): XrayLoopbackEndpoint?

    /**
     * Watchdog hook for the caller's existing coroutine loop. Detects a dead child and performs a
     * bounded restart with backoff. Deterministic on purpose so it can be unit-tested.
     */
    fun tick(nowMillis: Long)
}

enum class XraySidecarKind { Xray }

enum class XraySidecarCapability { SocksEgress, ProcessSidecar, ActiveVerify }

data class XraySidecarDescriptor(
    val id: String,
    val kind: XraySidecarKind,
    val adapterVersion: String,
    val capabilities: Set<XraySidecarCapability>,
    /** Always false: sing-box owns the datapath. Kept explicit for topology checks. */
    val ownsDatapath: Boolean = false,
)

data class XrayLoopbackEndpoint(
    val host: String = "127.0.0.1",
    val port: Int,
) {
    init {
        require(host == "127.0.0.1" || host == "::1") { "sidecar endpoint must be loopback" }
        require(port in 1024..65535) { "sidecar endpoint must use an unprivileged port" }
    }
}

enum class XraySidecarState { Stopped, Starting, Running, Degraded, Failed }

enum class XrayFailureCode {
    ExecutableMissing,
    ConfigRejected,
    StartFailed,
    NotReady,
    Crashed,
    NotRunning,
    Internal,
}

/** [detail] must already be redacted; it is user-visible and may be reported. */
data class XrayFailure(val code: XrayFailureCode, val detail: String)

sealed interface XrayStartResult {
    data class Ready(
        val epoch: Long,
        val socks: XrayLoopbackEndpoint,
    ) : XrayStartResult

    data class Rejected(val failure: XrayFailure) : XrayStartResult
}

enum class XrayStopReason { UserRequest, Failover, Shutdown, ProfileChange, Watchdog }

data class XraySidecarHealth(
    val state: XraySidecarState,
    val sinceMillis: Long,
    val restarts: Int,
    val lastFailure: XrayFailure?,
)

data class XrayTransportVersion(
    val engine: String,
    val engineVersion: String,
    val adapterVersion: String,
    val binarySha256: String?,
)

/**
 * Injectable process boundary. The production implementation wraps `ProcessBuilder`; tests use a
 * fake so lifecycle and retry behaviour are verifiable without a device.
 */
interface XrayChildProcess {
    val pid: Long
    fun isAlive(): Boolean

    /** Returns true when the child exited within [graceMillis]; otherwise the caller may force it. */
    fun terminate(graceMillis: Long): Boolean
    fun destroyForcibly()
}

interface XrayProcessLauncher {
    fun launch(
        command: List<String>,
        workingDir: File?,
        onStdout: (String) -> Unit,
        onStderr: (String) -> Unit,
    ): XrayChildProcess
}

interface XrayReadinessProbe {
    fun awaitReady(
        host: String,
        port: Int,
        timeoutMillis: Long,
        pollMillis: Long,
        isAlive: () -> Boolean,
    ): Boolean
}
