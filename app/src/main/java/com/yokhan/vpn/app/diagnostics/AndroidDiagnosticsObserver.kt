package com.yokhan.gates.app.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticEvent
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticEventKind
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticNetwork
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticPath
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticTarget
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticTransport
import com.yokhan.gates.shared.domain.diagnostics.IncidentPolicy
import com.yokhan.gates.shared.domain.diagnostics.IncidentRecorder
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.ProxyEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import libcore.BoxInstance
import libcore.Libcore
import java.security.SecureRandom
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object AndroidDiagnosticsObserver {
    private val active = IdentityHashMap<VpnService, Observer>()
    private val lifetimeGate = DiagnosticsLifetimeGate()
    private val startupFailurePending = AtomicBoolean()

    @Synchronized
    fun start(service: VpnService, box: BoxInstance, transport: DiagnosticTransport) {
        if (active.containsKey(service)) return
        try {
            lateinit var observer: Observer
            observer = Observer(service.applicationContext, box, transport, lifetimeGate) {
                forget(service, observer)
            }
            active[service] = observer
            observer.start()
        } catch (_: Exception) {
            active.remove(service)
        }
    }

    @Synchronized
    fun connected(service: VpnService) {
        runCatching { active[service]?.connected() }
    }

    @Synchronized
    fun runtimeFailure(service: VpnService) {
        val observer = active[service]
        if (observer != null) {
            runCatching { observer.record(DiagnosticEventKind.RUNTIME_FAILURE, path = DiagnosticPath.CORE) }
            return
        }
        if (!startupFailurePending.compareAndSet(false, true)) return
        val context = service.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                lifetimeGate.serialize {
                    persistFailedAttempt(context)
                }
            } catch (_: Exception) {
                // Diagnostics must not prevent connection teardown or the next attempt.
            } finally {
                startupFailurePending.set(false)
                scope.cancel()
            }
        }
    }

    private fun persistFailedAttempt(context: Context, transport: DiagnosticTransport = DiagnosticTransport.UNKNOWN) {
        val recorder = IncidentRecorder(IncidentPolicy(), AndroidIncidentReports.store(context), ::newId)
        recordFailedConnectionAttempt(recorder, newId(), nowMillis().coerceAtLeast(0), SystemClock.elapsedRealtime(), transport)
        AndroidRecoveryDelivery.start(context)
        AndroidRecoveryDelivery.capture(context)
    }

    @Synchronized
    fun deviceIdleChanged(service: VpnService, idle: Boolean) {
        runCatching { active[service]?.setSuspended(idle) }
    }

    @Synchronized
    fun stop(service: VpnService) {
        runCatching { active.remove(service)?.stop() }
    }

    @Synchronized
    private fun forget(service: VpnService, observer: Observer) {
        if (active[service] === observer) active.remove(service)
    }

    private class Observer(
        private val context: Context,
        private val box: BoxInstance,
        private val transport: DiagnosticTransport,
        private val lifetimeGate: DiagnosticsLifetimeGate,
        private val onTerminated: () -> Unit,
    ) {
        private val exceptionHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, _ ->
            stopped.set(true)
            probeJob?.cancel()
            signals.close()
            scope.cancel()
        }
        private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
        private val signals = Channel<PendingSignal>(SIGNAL_CAPACITY)
        private val droppedSignals = AtomicInteger()
        private val stopped = AtomicBoolean()
        private val startupFailureQueued = AtomicBoolean()
        private val terminated = AtomicBoolean()
        private val suspended = AtomicReference<Boolean?>()
        private val network = AtomicReference(DiagnosticNetwork.UNKNOWN)
        private val networkGeneration = AtomicLong()
        private val connected = CompletableDeferred<Unit>()
        private val stopRequested = CompletableDeferred<Unit>()
        private var probeJob: Job? = null
        private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        fun start() {
            scope.launch { consume() }
        }

        fun connected() {
            connected.complete(Unit)
        }

        fun record(
            kind: DiagnosticEventKind,
            target: DiagnosticTarget = DiagnosticTarget.NONE,
            durationMillis: Long? = null,
            network: DiagnosticNetwork = this.network.get(),
            path: DiagnosticPath = DiagnosticPath.UNKNOWN,
            code: Int? = null,
        ) {
            if (kind == DiagnosticEventKind.RUNTIME_FAILURE) startupFailureQueued.set(true)
            enqueue(PendingSignal(nowMillis(), SystemClock.elapsedRealtime(), kind, target,
                durationMillis, network, path, code))
        }

        fun setSuspended(value: Boolean) {
            val previous = suspended.getAndSet(value)
            if (previous == value || previous == null && !value) return
            record(if (value) DiagnosticEventKind.SUSPENDED else DiagnosticEventKind.RESUMED)
        }

        fun stop() {
            if (!stopped.compareAndSet(false, true)) return
            stopRequested.complete(Unit)
            probeJob?.cancel()
            signals.close()
        }

        private suspend fun consume() {
            try {
                lifetimeGate.consumeObserver(stopped::get, startupFailureQueued::get, ::consumeSession) {
                    persistFailedAttempt(context, transport)
                }
            } catch (_: CancellationException) {
                // A stopped observer waiting behind its predecessor owns no recorder state.
            } catch (_: Exception) {
                // Diagnostics must remain fail-open.
            } finally {
                stopped.set(true)
                signals.close()
                probeJob?.cancel()
                scope.cancel()
                finish()
            }
        }

        private suspend fun consumeSession() {
            var sessionId: String? = null
            var recorder: IncidentRecorder? = null
            var callback: ConnectivityManager.NetworkCallback? = null
            try {
                AndroidRecoveryDelivery.start(context)
                val session = newId()
                sessionId = session
                val incidentStore = AndroidIncidentReports.store(context)
                var candidate: IncidentRecorder? = null
                while (!stopped.get() && candidate == null) {
                    try {
                        candidate = IncidentRecorder(IncidentPolicy(), incidentStore, ::newId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        droppedSignals.incrementAndGet()
                        withTimeoutOrNull(30_000L) { stopRequested.await() }
                    }
                }
                val activeRecorder = candidate ?: return
                recorder = activeRecorder
                safeRecord(activeRecorder, event(session, PendingSignal(
                    nowMillis(), SystemClock.elapsedRealtime(), DiagnosticEventKind.SESSION_STARTED,
                    network = readCurrentNetwork(), path = DiagnosticPath.CORE,
                )))
                if (incidentStore.checkpointDegraded) {
                    safeRecord(activeRecorder, event(session, PendingSignal(
                        nowMillis(), SystemClock.elapsedRealtime(), DiagnosticEventKind.OBSERVER_GAP,
                        path = DiagnosticPath.UNKNOWN,
                        code = 1,
                    )))
                }
                callback = registerNetworkObserver()
                val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    power?.let { setSuspended(it.isDeviceIdleMode) }
                }
                startProbes()

                for (signal in signals) {
                    val lost = droppedSignals.getAndSet(0)
                    if (lost > 0) {
                        val gapRecorded = safeRecord(activeRecorder, event(session, signal.copy(
                            kind = DiagnosticEventKind.OBSERVER_GAP,
                            target = DiagnosticTarget.NONE,
                            durationMillis = null,
                            path = DiagnosticPath.UNKNOWN,
                            code = lost.coerceAtMost(9_999),
                        )))
                        if (!gapRecorded) droppedSignals.addAndGet(lost)
                    }
                    safeRecord(activeRecorder, event(session, signal))
                    safeTick(activeRecorder)
                    AndroidRecoveryDelivery.capture(context, incidentStore)
                }
            } catch (_: CancellationException) {
                // Teardown drains queued records and seals the current session below.
            } catch (_: Exception) {
                // Diagnostics must not affect VPN service behavior or expose exception text.
            } finally {
                probeJob?.cancel()
                callback?.let { cb -> runCatching { connectivity?.unregisterNetworkCallback(cb) } }
                val currentRecorder = recorder
                val currentSessionId = sessionId
                if (currentRecorder != null && currentSessionId != null) {
                    var lost = droppedSignals.getAndSet(0)
                    repeat(MAX_FINAL_GAP_ATTEMPTS) {
                        if (lost <= 0) return@repeat
                        val recorded = safeRecord(currentRecorder, event(currentSessionId, PendingSignal(
                            nowMillis(), SystemClock.elapsedRealtime(), DiagnosticEventKind.OBSERVER_GAP,
                            path = DiagnosticPath.UNKNOWN,
                            code = lost.coerceAtMost(9_999),
                        )))
                        if (recorded) {
                            lost = droppedSignals.getAndSet(0)
                        } else {
                            lost += droppedSignals.getAndSet(0)
                        }
                    }
                    safeRecord(currentRecorder, event(currentSessionId, PendingSignal(
                        nowMillis(), SystemClock.elapsedRealtime(), DiagnosticEventKind.SESSION_ENDED,
                        network = network.get(), path = DiagnosticPath.CORE,
                    )), end = true)
                    AndroidRecoveryDelivery.capture(context)
                }
            }
        }

        private fun startProbes() {
            probeJob = scope.launch {
                connected.await()
                var target = DiagnosticTarget.GSTATIC
                while (isActive && !stopped.get()) {
                    val primary = probe(target)
                    if (primary == DiagnosticEventKind.PROBE_FAILURE) {
                        var followUps = 0
                        while (followUps < MAX_FOLLOW_UP_PROBES && isActive && !stopped.get()) {
                            delay(FOLLOW_UP_DELAY_MILLIS)
                            target = target.other()
                            if (stopped.get()) break
                            followUps += 1
                            if (probe(target) != DiagnosticEventKind.PROBE_FAILURE) break
                        }
                    }
                    target = target.other()
                    delay(PROBE_INTERVAL_MILLIS)
                }
            }
        }

        private suspend fun probe(target: DiagnosticTarget): DiagnosticEventKind {
            val idle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isDeviceIdleMode == true
            } else {
                false
            }
            if (suspended.get() == true || idle) {
                if (idle || suspended.get() == true) setSuspended(idle)
                record(DiagnosticEventKind.PROBE_UNAVAILABLE, target, network = network.get(), path = DiagnosticPath.CORE)
                return DiagnosticEventKind.PROBE_UNAVAILABLE
            }
            if (network.get() == DiagnosticNetwork.NONE) {
                record(DiagnosticEventKind.PROBE_UNAVAILABLE, target, network = DiagnosticNetwork.NONE, path = DiagnosticPath.CORE)
                return DiagnosticEventKind.PROBE_UNAVAILABLE
            }

            val startGeneration = networkGeneration.get()
            val networkAtStart = network.get()
            val url = if (target == DiagnosticTarget.GSTATIC) GSTATIC_URL else YOUTUBE_URL
            val started = SystemClock.elapsedRealtime()
            val kind = try {
                if (Libcore.urlTest(box, url, PROBE_TIMEOUT_MS) > 0) {
                    DiagnosticEventKind.PROBE_SUCCESS
                } else {
                    DiagnosticEventKind.PROBE_FAILURE
                }
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (_: Exception) {
                DiagnosticEventKind.PROBE_FAILURE
            }
            val duration = (SystemClock.elapsedRealtime() - started).coerceIn(0, MAX_MEASUREMENT_MILLIS)
            if (!isProbeResultCurrent(startGeneration, networkGeneration.get(), stopped.get())) {
                record(DiagnosticEventKind.PROBE_UNAVAILABLE, target, duration, network.get(), DiagnosticPath.CORE)
                return DiagnosticEventKind.PROBE_UNAVAILABLE
            }
            record(kind, target, duration, networkAtStart, DiagnosticPath.CORE)
            return kind
        }

        private fun registerNetworkObserver(): ConnectivityManager.NetworkCallback? {
            val manager = connectivity ?: return null
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runCatching { networkGeneration.incrementAndGet() }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    runCatching {
                        val current = capabilities.toDiagnosticNetwork()
                        if (this@Observer.network.getAndSet(current) != current) {
                            networkGeneration.incrementAndGet()
                            record(DiagnosticEventKind.NETWORK_CHANGED, network = current)
                        }
                    }
                }

                override fun onLost(network: Network) {
                    runCatching {
                        networkGeneration.incrementAndGet()
                        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            DiagnosticNetwork.NONE
                        } else {
                            DiagnosticNetwork.UNKNOWN
                        }
                        if (this@Observer.network.getAndSet(current) != current) {
                            record(DiagnosticEventKind.NETWORK_CHANGED, network = current)
                        }
                    }
                }

                override fun onUnavailable() {
                    runCatching {
                        networkGeneration.incrementAndGet()
                        if (network.getAndSet(DiagnosticNetwork.NONE) != DiagnosticNetwork.NONE) {
                            record(DiagnosticEventKind.NETWORK_CHANGED, network = DiagnosticNetwork.NONE)
                        }
                    }
                }
            }
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    manager.registerDefaultNetworkCallback(callback)
                } else {
                    manager.registerNetworkCallback(
                        NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                        callback,
                    )
                }
                val current = readCurrentNetwork()
                if (network.getAndSet(current) != current) {
                    networkGeneration.incrementAndGet()
                    record(DiagnosticEventKind.NETWORK_CHANGED, network = current)
                }
                callback
            }.getOrNull()
        }

        private fun readCurrentNetwork(): DiagnosticNetwork {
            val manager = connectivity ?: return DiagnosticNetwork.UNKNOWN
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val activeNetwork = manager.activeNetwork
                    val capabilities = activeNetwork?.let(manager::getNetworkCapabilities)
                    capabilities?.toDiagnosticNetwork() ?: if (activeNetwork == null) {
                        DiagnosticNetwork.NONE
                    } else {
                        DiagnosticNetwork.UNKNOWN
                    }
                } else when (manager.activeNetworkInfo?.type) {
                    ConnectivityManager.TYPE_WIFI -> DiagnosticNetwork.WIFI
                    ConnectivityManager.TYPE_MOBILE -> DiagnosticNetwork.CELLULAR
                    null -> DiagnosticNetwork.UNKNOWN
                    else -> DiagnosticNetwork.UNKNOWN
                }
            }.getOrDefault(DiagnosticNetwork.UNKNOWN)
        }

        private fun enqueue(signal: PendingSignal) {
            if (!stopped.get() && signals.trySend(signal).isFailure) droppedSignals.incrementAndGet()
        }

        private fun event(sessionId: String, signal: PendingSignal) = DiagnosticEvent(
            timestampMillis = signal.timestampMillis,
            monotonicMillis = signal.monotonicMillis,
            sessionId = sessionId,
            kind = signal.kind,
            target = signal.target,
            durationMillis = signal.durationMillis,
            transport = transport,
            network = signal.network,
            code = signal.code,
            path = signal.path,
            dnsMillis = signal.dnsMillis,
            connectMillis = signal.connectMillis,
            tlsMillis = signal.tlsMillis,
        )

        private fun safeRecord(recorder: IncidentRecorder, event: DiagnosticEvent, end: Boolean = false): Boolean {
            val result = runCatching { if (end) recorder.endSession(event) else recorder.record(event) }
            if (result.isFailure) droppedSignals.incrementAndGet()
            return result.isSuccess
        }

        private fun safeTick(recorder: IncidentRecorder) {
            if (runCatching { recorder.tick(nowMillis(), SystemClock.elapsedRealtime()) }.isFailure) {
                droppedSignals.incrementAndGet()
            }
        }

        private fun finish() {
            if (terminated.compareAndSet(false, true)) runCatching(onTerminated)
        }

        private data class PendingSignal(
            val timestampMillis: Long,
            val monotonicMillis: Long,
            val kind: DiagnosticEventKind,
            val target: DiagnosticTarget = DiagnosticTarget.NONE,
            val durationMillis: Long? = null,
            val network: DiagnosticNetwork = DiagnosticNetwork.UNKNOWN,
            val path: DiagnosticPath = DiagnosticPath.UNKNOWN,
            val code: Int? = null,
            val dnsMillis: Long? = null,
            val connectMillis: Long? = null,
            val tlsMillis: Long? = null,
        )

        private fun NetworkCapabilities.toDiagnosticNetwork(): DiagnosticNetwork = when {
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> DiagnosticNetwork.WIFI
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> DiagnosticNetwork.CELLULAR
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> DiagnosticNetwork.ETHERNET
            else -> DiagnosticNetwork.UNKNOWN
        }

        private fun DiagnosticTarget.other(): DiagnosticTarget =
            if (this == DiagnosticTarget.GSTATIC) DiagnosticTarget.YOUTUBE else DiagnosticTarget.GSTATIC
    }

    private fun newId(): String {
        val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
        return buildString(bytes.size * 2) {
            bytes.forEach { byte -> append("0123456789abcdef"[(byte.toInt() and 0xff) ushr 4])
                append("0123456789abcdef"[byte.toInt() and 0x0f]) }
        }
    }

    private fun nowMillis(): Long = System.currentTimeMillis()

    private fun transport(profile: ProxyEntity?): DiagnosticTransport = when {
        profile?.type == ProxyEntity.TYPE_HYSTERIA && profile.hysteriaBean?.protocolVersion == 2 ->
            DiagnosticTransport.HYSTERIA2
        profile?.type == ProxyEntity.TYPE_VMESS && profile.vmessBean?.isVLESS == true -> DiagnosticTransport.VLESS
        profile == null -> DiagnosticTransport.UNKNOWN
        else -> DiagnosticTransport.OTHER
    }

    private const val SIGNAL_CAPACITY = 128
    private const val PROBE_TIMEOUT_MS = 5_000
    private const val FOLLOW_UP_DELAY_MILLIS = 2_000L
    private const val MAX_FOLLOW_UP_PROBES = 2
    private const val MAX_FINAL_GAP_ATTEMPTS = 2
    private const val PROBE_INTERVAL_MILLIS = 180_000L
    private const val MAX_MEASUREMENT_MILLIS = 86_400_000L
    private const val GSTATIC_URL = "https://www.gstatic.com/generate_204"
    private const val YOUTUBE_URL = "https://www.youtube.com/generate_204"

    fun transportFor(profile: ProxyEntity?): DiagnosticTransport = transport(profile)
}
