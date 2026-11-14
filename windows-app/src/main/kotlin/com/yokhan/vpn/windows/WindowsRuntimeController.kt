package com.yokhan.gates.windows

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.awt.Desktop
import java.io.File
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.MutableStateFlow

data class RuntimeCommandResult(
    val exitCode: Int,
    val output: String,
    val recoveryFailureStage: RecoveryFailureStage? = null,
    val accessFailure: WindowsAccessFailure? = null,
    val accessPending: Boolean = false,
)

data class AppRoutingState(
    val routeAll: Boolean,
    val proxyProcessNames: List<String>,
    val proxyProcessPaths: List<String>,
    val directProcessNames: List<String>,
    val directProcessPaths: List<String>,
)

data class WindowsProcessCandidate(
    val pid: Long,
    val name: String,
    val path: String,
)

internal data class WindowsLiveRuntimeSnapshot(
    val connected: Boolean,
    val resolvedRoute: String,
    val runtimeMode: String,
    val protectionState: WindowsProtectionState,
    val tunnelPid: Long? = null,
    val startedAtEpochMillis: Long? = null,
)

data class WindowsSessionState(
    val signedIn: Boolean,
    val hasImportedConfig: Boolean,
    val ready: Boolean,
    val summary: String,
    val accountLabel: String = "",
)

private const val GATES_WINDOWS_AUTH_CLIENT_ID = "gates-windows"
private const val LEGACY_WINDOWS_AUTH_CLIENT_ID = "yokhan-vpn-windows"
private val GATES_DEFAULT_TUN_ADDRESS = listOf("172.30.248.1/30")
private val GATES_LEGACY_SING_BOX_TUN_ADDRESS = listOf("172.19.0.1/30")

data class WindowsAppConfig(
    val subscriptionUrl: String = "https://cloak.yokhanfitness.ru/api/sub/REPLACE_WITH_PERSONAL_TOKEN",
    val profileSource: String = "legacy",
    val bootstrapExpiresAtEpochMillis: Long = 0L,
    val appApiBaseUrl: String = GATES_APP_API_BASE_URL,
    val authIssuer: String = GATES_AUTH_ISSUER,
    val authClientId: String = GATES_WINDOWS_AUTH_CLIENT_ID,
    val authDevice: String = "main",
    val authRoom: String = "",
    val downloadPageUrl: String = GATES_DOWNLOAD_PAGE_URL,
    val autoCheckUpdates: Boolean = true,
    val autoUploadRecoveryReports: Boolean = true,
    val reportUrls: List<String> = listOf(
        GATES_BUG_REPORT_URL,
    ),
    val systemProxyEnabled: Boolean = true,
    val restoreSystemProxyOnStop: Boolean = true,
    val autoStartOnLogin: Boolean = false,
    val runtimeMode: String = "tun",
    val killSwitchEnabled: Boolean = false,
    val allowProxyFallback: Boolean = true,
    val listenHost: String = "127.0.0.1",
    val socksPort: Int = 2088,
    val httpPort: Int = 2089,
    val xhttpSidecarPort: Int = 2095,
    val tunInterfaceName: String = "GATES",
    val tunAddress: List<String> = GATES_DEFAULT_TUN_ADDRESS,
    val tunMtu: Int = 9000,
    val tunStack: String = "mixed",
    val tunAutoRoute: Boolean = true,
    val tunStrictRoute: Boolean = true,
    val tunRouteAll: Boolean = true,
    val tunRouteExcludeAddress: List<String> = listOf(
        "127.0.0.0/8",
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "224.0.0.0/4",
    ),
    val appProxyProcessNames: List<String> = emptyList(),
    val appProxyProcessPaths: List<String> = emptyList(),
    val appDirectProcessNames: List<String> = listOf("GATES.exe", "sing-box.exe"),
    val appDirectProcessPaths: List<String> = emptyList(),
    val roomNetEnabled: Boolean = true,
    val roomNetLocalGameHost: String = "127.0.0.1",
    val activeRoute: String = "auto",
    val disableBridgeMultiplex: Boolean? = true,
    val physicalBindEnabled: Boolean? = true,
    val physicalBindInterface: String? = "",
    val physicalBindIpv4: String? = "",
    val physicalBindRoutes: List<String> = listOf("bridge", "relay"),
    val routeOrder: List<String> = listOf("bridge", "relay", "naive", "hy2"),
    val enableRuBypass: Boolean = true,
    val wholeTrafficMode: Boolean = false,
    val bypassLan: Boolean = true,
    val blockQuic: Boolean = false,
    val healthcheckUrl: String = "https://www.gstatic.com/generate_204",
    val testUrls: List<String> = listOf(
        "https://www.google.com/generate_204",
        "https://www.youtube.com/generate_204",
        "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
        "https://api.telegram.org",
        "https://vk.com",
        "https://rutube.ru",
        "https://cloak.yokhanfitness.ru",
        "https://naive.yokhanfitness.ru",
    ),
)

private data class PhysicalBindTarget(
    val interfaceName: String?,
    val ipv4: String?,
    val source: String,
)

private data class WindowsAuthState(
    val issuer: String = "",
    val clientId: String = "",
    val tokenEndpoint: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtEpochSecond: Long = 0,
    val requiresReauthentication: Boolean = false,
    val accountSubject: String? = null,
    val accountLabel: String? = null,
)

private data class WindowsDeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Long,
    val intervalSeconds: Long,
)

private data class SystemProxySnapshot(
    val proxyEnablePresent: Boolean = true,
    val proxyEnable: Int = 0,
    val proxyServerPresent: Boolean = false,
    val proxyServer: String = "",
    val proxyOverridePresent: Boolean = false,
    val proxyOverride: String = "",
    val capturedAt: String = "",
)

private data class RegistryValue(
    val present: Boolean,
    val value: String,
)

private data class PkcePair(
    val verifier: String,
    val challenge: String,
)

private data class RoomNetPeer(
    val id: String = "",
    val username: String = "",
    val device: String = "",
    val address: String = "",
    val ports: List<Int> = emptyList(),
    val online: Boolean = false,
)

private data class RoomNetRelayInfo(
    val url: String = "",
    val path: String = "/api/app/roomnet/ws",
    val protocol: String = "gates-roomnet-v1",
)

private data class RoomNetInfo(
    val enabled: Boolean = false,
    val room: String = "",
    val mode: String = "",
    val network: String = "",
    val ports: List<Int> = emptyList(),
    val self: RoomNetPeer? = null,
    val peers: List<RoomNetPeer> = emptyList(),
    val relay: RoomNetRelayInfo = RoomNetRelayInfo(),
    val connected: List<String> = emptyList(),
)

private data class RoomNetFlowTarget(
    val address: InetSocketAddress,
    val peerId: String,
    val port: Int,
    val lastActivityMillis: Long,
)

private data class RoomNetInboundFlow(
    val socket: DatagramSocket,
    val peerId: String,
    val lastActivityMillis: Long,
)

private data class RoomNetConnectionEnd(
    val statusCode: Int?,
    val reason: String,
    val error: Throwable? = null,
)

private class RoomNetRelay(
    private val gson: Gson,
    private val config: WindowsAppConfig,
    private val info: RoomNetInfo,
    private val accessTokenProvider: () -> String,
    private val wsUrl: String,
) {
    private val running = AtomicBoolean(true)
    private val webSocket = AtomicReference<WebSocket?>()
    private val peerSockets = ConcurrentHashMap<String, DatagramSocket>()
    private val outboundFlows = ConcurrentHashMap<String, RoomNetFlowTarget>()
    private val inboundFlows = ConcurrentHashMap<String, RoomNetInboundFlow>()
    private val knownPeers = info.peers.map { it.id }.filter { it.isNotBlank() }.toSet()
    private val allowedPortsByPeer = info.peers.associate { peer ->
        peer.id to windowsRoomNetAllowedPorts(info.ports, peer.ports)
    }

    fun run(): RuntimeCommandResult {
        val self = info.self ?: return RuntimeCommandResult(1, "RoomNet self peer is missing.")
        if (info.peers.isEmpty()) {
            return RuntimeCommandResult(1, "RoomNet has no peers.")
        }
        if (info.room.isBlank() || self.id.isBlank()) {
            return RuntimeCommandResult(1, "RoomNet room or self peer identity is missing.")
        }
        val peerIds = info.peers.map { it.id }
        if (peerIds.any { it.isBlank() || it == self.id } || peerIds.distinct().size != peerIds.size) {
            return RuntimeCommandResult(1, "RoomNet peer identities are invalid or duplicated.")
        }
        val localGameAddress = runCatching { InetAddress.getByName(config.roomNetLocalGameHost) }
            .getOrNull()
            ?.takeIf { it.isLoopbackAddress }
            ?: return RuntimeCommandResult(1, "RoomNet local game host must resolve to loopback.")
        if (!areWindowsRoomNetAddressesDistinct(
                self.address,
                info.peers.map { it.address },
                localGameAddress.hostAddress,
            )
        ) {
            return RuntimeCommandResult(1, "RoomNet peer addresses must be unique 127.77.x.y loopback endpoints.")
        }
        if (allowedPortsByPeer.values.any { it.size > WINDOWS_ROOMNET_MAX_LISTENERS_PER_PEER } ||
            allowedPortsByPeer.values.sumOf { it.size } > WINDOWS_ROOMNET_MAX_LISTENERS
        ) {
            return RuntimeCommandResult(1, "RoomNet listener count exceeds the bounded peer limits.")
        }
        println("RoomNet worker starting room=${info.room} self=${self.id} address=${self.address}")
        startPeerListeners()
        if (peerSockets.isEmpty()) return RuntimeCommandResult(1, "RoomNet could not open a loopback UDP listener.")
        return try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .build()
            Runtime.getRuntime().addShutdownHook(Thread { close() })
            var retryToken: String? = null
            var consecutiveFailures = 0
            var consecutiveAuthRetries = 0
            while (running.get()) {
                val token = retryToken ?: runCatching { accessTokenProvider() }.getOrElse { error ->
                    return RuntimeCommandResult(1, "RoomNet access token unavailable: ${error.message}")
                }
                retryToken = null
                if (token.isBlank()) return RuntimeCommandResult(1, "RoomNet access token is empty.")

                val listener = Listener()
                var connectedSocket: WebSocket? = null
                var connectedAtMillis: Long? = null
                val end = try {
                    connectedSocket = client.newWebSocketBuilder()
                        .header("Authorization", "Bearer $token")
                        .header("X-Yokhan-Device", config.authDevice)
                        .header("X-Yokhan-Client", "GATES-Windows-RoomNet")
                        .buildAsync(URI.create(wsUrl), listener)
                        .join()
                    webSocket.set(connectedSocket)
                    println("RoomNet websocket connected: $wsUrl")
                    connectedAtMillis = System.currentTimeMillis()
                    listener.ended.get()
                } catch (error: Exception) {
                    roomNetConnectionEnd(error)
                } finally {
                    connectedSocket?.let { webSocket.compareAndSet(it, null) }
                }

                if (!running.get()) return RuntimeCommandResult(0, "RoomNet worker stopped.")
                if (connectedAtMillis?.let {
                        System.currentTimeMillis() - it >= WINDOWS_ROOMNET_AUTH_RETRY_RESET_MILLIS
                    } == true
                ) {
                    consecutiveAuthRetries = 0
                }
                val refreshForAuth = end.statusCode == 401 ||
                    (end.statusCode == 1008 && end.reason.trim().equals("session expired", ignoreCase = true))
                val refreshedToken = if (refreshForAuth) {
                    runCatching { accessTokenProvider() }.getOrElse { error ->
                        return RuntimeCommandResult(1, "RoomNet session refresh failed: ${error.message}")
                    }
                } else {
                    token
                }
                if (decideWindowsRoomNetRetry(
                        end.statusCode,
                        end.reason,
                        token,
                        refreshedToken,
                        stopped = !running.get(),
                        consecutiveAuthRetries = consecutiveAuthRetries,
                    ) == WindowsRoomNetRetryDecision.STOP
                ) {
                    return RuntimeCommandResult(
                        1,
                        if (end.statusCode == 401 || end.statusCode == 403 || end.statusCode == 1008) {
                            "RoomNet authorization or policy rejected the session."
                        } else {
                            "RoomNet connection stopped: ${end.error?.message ?: end.reason}"
                        },
                    )
                }
                if (refreshForAuth) {
                    retryToken = refreshedToken
                    consecutiveAuthRetries++
                }
                consecutiveFailures++
                val delayMillis = windowsRoomNetReconnectDelayMillis(consecutiveFailures)
                println("RoomNet disconnected; reconnecting in ${delayMillis}ms (status=${end.statusCode ?: "network"})")
                try {
                    Thread.sleep(delayMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return RuntimeCommandResult(0, "RoomNet worker stopped.")
                }
            }
            RuntimeCommandResult(0, "RoomNet worker stopped.")
        } finally {
            close()
        }
    }

    private fun roomNetConnectionEnd(error: Throwable): RoomNetConnectionEnd {
        val handshake = generateSequence(error) { it.cause }
            .filterIsInstance<WebSocketHandshakeException>()
            .firstOrNull()
        return RoomNetConnectionEnd(
            statusCode = handshake?.response?.statusCode(),
            reason = "",
            error = error.cause ?: error,
        )
    }

    private fun startPeerListeners() {
        for (peer in info.peers) {
            for (port in allowedPortsByPeer[peer.id].orEmpty()) {
                val key = socketKey(peer.id, port)
                val socket = runCatching {
                    val bindAddress = windowsRoomNetPeerSocketAddress(peer.address, port)
                        ?: error("peer endpoint is outside the RoomNet loopback range")
                    DatagramSocket(null).also {
                        it.reuseAddress = true
                        it.bind(bindAddress)
                    }
                }.getOrElse { error ->
                    println("RoomNet listener skipped ${peer.address}:$port for ${peer.id}: ${error.message}")
                    null
                } ?: continue
                peerSockets[key] = socket
                Thread({
                    receiveLocalPeerLoop(peer, port, socket)
                }, "GATES-RoomNet-peer-${peer.id}-$port").apply {
                    isDaemon = true
                    start()
                }
                println("RoomNet listening ${peer.address}:$port -> ${peer.id}")
            }
        }
    }

    private fun receiveLocalPeerLoop(peer: RoomNetPeer, port: Int, socket: DatagramSocket) {
        val buffer = ByteArray(65507)
        while (running.get() && !socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                if (running.get()) Thread.sleep(100)
                continue
            }
            if (!packet.address.isLoopbackAddress) continue
            val source = InetSocketAddress(packet.address, packet.port)
            val flow = flowId(peer.id, port, source)
            putBoundedWindowsRoomNetFlow(
                outboundFlows,
                flow,
                RoomNetFlowTarget(source, peer.id, port, System.currentTimeMillis()),
                System.currentTimeMillis(),
                { windowsRoomNetFlowExpiresAt(it.lastActivityMillis) },
            )
            sendUdpToRoom(peer.id, port, flow, packet.data.copyOf(packet.length))
        }
    }

    private fun handleRemoteUdp(message: JsonObject) {
        val self = info.self ?: return
        val localGameAddress = runCatching { InetAddress.getByName(config.roomNetLocalGameHost) }
            .getOrNull()
            ?.takeIf { it.isLoopbackAddress }
            ?: return
        val datagram = runCatching {
            WindowsRoomNetDatagram(
                room = message.get("room")?.asString.orEmpty(),
                to = message.get("to")?.asString.orEmpty(),
                from = message.get("from")?.asString.orEmpty(),
                destinationPort = message.get("dst_port")?.asInt ?: return,
                flow = message.get("flow")?.asString.orEmpty(),
                encodedPayload = message.get("payload")?.asString.orEmpty(),
            )
        }.getOrNull() ?: return
        val payload = validateWindowsRoomNetDatagram(
            datagram,
            info.room,
            self.id,
            knownPeers,
            allowedPortsByPeer[datagram.from].orEmpty(),
        ) ?: return
        val from = datagram.from
        val dstPort = datagram.destinationPort
        val flow = datagram.flow

        val target = outboundFlows[flow]
        if (target != null) {
            val now = System.currentTimeMillis()
            if (isWindowsRoomNetFlowExpired(target.lastActivityMillis, now)) {
                outboundFlows.remove(flow, target)
                return
            }
            if (!isWindowsRoomNetResponseFlowMatch(target.peerId, target.port, from, dstPort)) return
            val refreshed = outboundFlows.computeIfPresent(flow) { _, latest ->
                if (isWindowsRoomNetFlowExpired(latest.lastActivityMillis, now) ||
                    !isWindowsRoomNetResponseFlowMatch(latest.peerId, latest.port, from, dstPort)
                ) {
                    null
                } else {
                    latest.copy(lastActivityMillis = now)
                }
            } ?: return
            val socket = peerSockets[socketKey(from, refreshed.port)]
            if (socket != null && !socket.isClosed) {
                socket.send(DatagramPacket(payload, payload.size, refreshed.address))
            }
            return
        }

        val inboundKey = "$from|$flow|$dstPort"
        val now = System.currentTimeMillis()
        val inbound = synchronized(inboundFlows) {
            val expired = inboundFlows.filterValues {
                isWindowsRoomNetFlowExpired(it.lastActivityMillis, now)
            }.keys
            expired.forEach { key -> inboundFlows.remove(key)?.socket?.close() }
            val existing = inboundFlows[inboundKey]
            if (existing != null) {
                existing.copy(lastActivityMillis = now).also { inboundFlows[inboundKey] = it }
            } else {
                val peerFlowCount = inboundFlows.values.count { it.peerId == from }
                if (!canCreateWindowsRoomNetFlow(from, peerFlowCount, inboundFlows.size)) return
                RoomNetInboundFlow(
                    createInboundFlowSocket(from, flow, dstPort, inboundKey),
                    from,
                    now,
                ).also { inboundFlows[inboundKey] = it }
            }
        }
        inbound.socket.send(DatagramPacket(payload, payload.size, InetSocketAddress(localGameAddress, dstPort)))
    }

    private fun createInboundFlowSocket(peerId: String, flow: String, dstPort: Int, inboundKey: String): DatagramSocket {
        val socket = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        socket.soTimeout = 30_000
        Thread({
            val buffer = ByteArray(65507)
            while (running.get() && !socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    if (expireInboundFlowIfIdle(inboundKey, socket)) break
                    continue
                } catch (_: Exception) {
                    if (running.get()) Thread.sleep(100)
                    continue
                }
                if (!packet.address.isLoopbackAddress) continue
                if (!touchInboundFlow(inboundKey, socket)) continue
                sendUdpToRoom(peerId, dstPort, flow, packet.data.copyOf(packet.length))
            }
        }, "GATES-RoomNet-inbound-$peerId-$dstPort").apply {
            isDaemon = true
            start()
        }
        return socket
    }

    private fun touchInboundFlow(inboundKey: String, socket: DatagramSocket): Boolean = synchronized(inboundFlows) {
        val flow = inboundFlows[inboundKey] ?: return@synchronized false
        val now = System.currentTimeMillis()
        if (flow.socket !== socket || isWindowsRoomNetFlowExpired(flow.lastActivityMillis, now)) {
            inboundFlows.remove(inboundKey, flow)
            runCatching { flow.socket.close() }
            return@synchronized false
        }
        inboundFlows[inboundKey] = flow.copy(lastActivityMillis = now)
        true
    }

    private fun expireInboundFlowIfIdle(inboundKey: String, socket: DatagramSocket): Boolean = synchronized(inboundFlows) {
        val flow = inboundFlows[inboundKey] ?: return@synchronized true
        if (flow.socket !== socket || !isWindowsRoomNetFlowExpired(flow.lastActivityMillis, System.currentTimeMillis())) {
            return@synchronized flow.socket !== socket
        }
        inboundFlows.remove(inboundKey, flow)
        runCatching { flow.socket.close() }
        true
    }

    private fun sendUdpToRoom(peerId: String, dstPort: Int, flow: String, payload: ByteArray) {
        val packet = JsonObject().apply {
            addProperty("type", "udp")
            addProperty("room", info.room)
            addProperty("to", peerId)
            addProperty("dst_port", dstPort)
            addProperty("flow", flow)
            addProperty("payload", Base64.getEncoder().encodeToString(payload))
        }
        webSocket.get()?.sendText(gson.toJson(packet), true)
    }

    private fun close() {
        if (!running.getAndSet(false)) return
        webSocket.getAndSet(null)?.let { socket ->
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "RoomNet stop") }
        }
        peerSockets.values.forEach { runCatching { it.close() } }
        peerSockets.clear()
        inboundFlows.values.forEach { runCatching { it.socket.close() } }
        inboundFlows.clear()
        outboundFlows.clear()
    }

    private fun socketKey(peerId: String, port: Int): String = "$peerId:$port"

    private fun flowId(peerId: String, port: Int, source: InetSocketAddress): String {
        val raw = "$peerId|$port|${source.address.hostAddress}|${source.port}"
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    private inner class Listener : WebSocket.Listener {
        private val partial = StringBuilder()
        private var oversizedMessage = false

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            if (!oversizedMessage) {
                if (partial.length + data.length > 128 * 1024) {
                    partial.clear()
                    oversizedMessage = true
                } else {
                    partial.append(data)
                }
            }
            if (last) {
                if (oversizedMessage) {
                    oversizedMessage = false
                    webSocket.request(1)
                    return CompletableFuture.completedFuture(null)
                }
                val text = partial.toString()
                partial.clear()
                runCatching {
                    val obj = JsonParser.parseString(text).asJsonObject
                    when (obj.get("type")?.asString) {
                        "hello" -> println("RoomNet hello: $text")
                        "udp" -> handleRemoteUdp(obj)
                        "error" -> println("RoomNet server error: $text")
                    }
                }.onFailure { error ->
                    println("RoomNet message error: ${error.message}")
                }
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            println("RoomNet websocket error: ${error.message}")
            ended.complete(RoomNetConnectionEnd(statusCode = null, reason = "", error = error))
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
            println("RoomNet websocket closed: $statusCode $reason")
            ended.complete(RoomNetConnectionEnd(statusCode, reason))
            return CompletableFuture.completedFuture(null)
        }

        val ended = CompletableFuture<RoomNetConnectionEnd>()
    }
}

internal fun normalizeWindowsAuthClientId(
    value: String?,
    fallback: String = GATES_WINDOWS_AUTH_CLIENT_ID,
): String {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isBlank()) return fallback
    if (trimmed.equals(LEGACY_WINDOWS_AUTH_CLIENT_ID, ignoreCase = true)) {
        return fallback
    }
    return trimmed
}

private const val WINDOWS_APP_VERSION = "1.7.17"
internal fun windowsAppVersionForDisplay(): String = WINDOWS_APP_VERSION
private const val WINDOWS_UPDATE_SIGNER_PROPERTY = "gates.update.signer.sha1"
private val WINDOWS_SIGNER_PATTERN = Regex("[0-9A-F]{40}")
private const val WINDOWS_PLATFORM = "windows"
private const val ELEVATED_CLI_TIMEOUT_SECONDS = 120

internal fun isTunPermissionFailure(output: String): Boolean {
    val normalized = output.lowercase()
    return normalized.contains("access is denied") &&
        (
            normalized.contains("tun") ||
                normalized.contains("configure interface")
            )
}

internal fun windowsLocalDirectRouteRule(): JsonObject =
    JsonObject().apply {
        add("domain", JsonArray().also { domains ->
            domains.add("localhost")
        })
        add("domain_suffix", JsonArray().also { suffixes ->
            suffixes.add("localhost")
            suffixes.add("local")
        })
        addProperty("outbound", "direct")
    }

internal fun windowsTunAddressIps(addresses: List<String>): List<String> =
    addresses
        .mapNotNull { address -> address.substringBefore('/').trim().takeIf { it.isNotBlank() } }
        .distinct()

internal fun normalizeTunAddress(addresses: List<String>): List<String> =
    if (addresses == GATES_LEGACY_SING_BOX_TUN_ADDRESS) GATES_DEFAULT_TUN_ADDRESS else addresses

internal fun managedRuntimePid(pidFileText: String?, processState: Map<String, String>): Long? {
    val pidFileValue = pidFileText?.trim()?.toLongOrNull()
    if (pidFileValue != null) return pidFileValue
    return processState["pid"]?.trim()?.toLongOrNull()
}

internal fun selectWindowsDnsProxyOutbound(
    activeRoute: String,
    availableTags: Set<String>,
): String {
    val selected = activeRoute.trim().lowercase()
    if (selected.isNotBlank() && selected != "auto") return selected
    return listOf("naive", "relay", "bridge", "nl-tcp").firstOrNull { it in availableTags } ?: "auto"
}

internal fun buildWindowsTunAddressCleanupScript(interfaceName: String, addresses: List<String>): String {
    val addressArray = addresses.joinToString(", ") { psSingleQuotedScriptValue(it) }
    return "\$ErrorActionPreference = 'Stop'; " +
        "\$alias = ${psSingleQuotedScriptValue(interfaceName)}; " +
        "\$addresses = @($addressArray); " +
        "try { " +
        "foreach (\$address in \$addresses) { " +
        "Get-NetIPAddress -InterfaceAlias \$alias -IPAddress \$address -ErrorAction SilentlyContinue | " +
        "ForEach-Object { Remove-NetIPAddress -InputObject \$_ -Confirm:\$false -ErrorAction Stop }; " +
        "} " +
        "exit 0 " +
        "} catch { " +
        "Write-Output ('cleanup_error=' + \$_.Exception.Message); " +
        "exit 1 " +
        "}"
}

private fun psSingleQuotedScriptValue(value: String): String =
    "'${value.replace("'", "''")}'"

private fun RuntimeCommandResult.isRunningServiceTunnel(): Boolean =
    exitCode == 0 && output.trim() == "RUNNING"

class WindowsRuntimeController(
    private val allowRuntimeServiceDelegation: Boolean = true,
    private val appDir: File = windowsGatesAppDir(),
    private val runtimeService: WindowsRuntimeControl = WindowsRuntimeServiceClient(),
    private val prepareRuntimeService: () -> RuntimeCommandResult = { WindowsRuntimeServiceManager().ensureRunning() },
    private val validateGeneratedConfig: ((File) -> RuntimeCommandResult)? = null,
    private val stopLocalRuntime: ((WindowsAppConfig) -> RuntimeCommandResult)? = null,
    private val fetchSubscriptionJson: ((String) -> String)? = null,
    private val accessOnly: Boolean = false,
    private val authHttpClient: HttpClient? = null,
    private val authorizationBrowser: ((String) -> Boolean)? = null,
    private val authorizationPause: ((Long) -> Unit)? = null,
    private val runtimeServiceInstalled: () -> Boolean? = ::queryWindowsRuntimeServiceInstalled,
    private val prepareRuntimeWithoutTunnel: () -> RuntimeCommandResult = {
        WindowsRuntimeServiceManager().prepareWithoutConnecting()
    },
    private val xrayPinProvider: () -> String? = { readInstalledXrayPin() },
    private val serviceDataDir: File = File(
        System.getenv("ProgramData")?.takeIf { it.isNotBlank() } ?: "C:\\ProgramData",
        "GATES\\Service",
    ),
) {
    internal val authorizationPrompt = MutableStateFlow<WindowsAuthorizationPrompt?>(null)
    internal val authorizationPhase = MutableStateFlow(WindowsAuthorizationPhase.Idle)
    private val authorizationCommitLock = Any()

    fun cancelPendingAuthorization(cancel: () -> Unit): Boolean = synchronized(authorizationCommitLock) {
        if (authorizationPrompt.value == null) false else { cancel(); true }
    }

    fun reopenAuthorization(): Boolean = authorizationPrompt.value?.let { openUri(it.verificationUri) } ?: false

    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    private val binDir = File(appDir, "bin")
    private val runtimeDir = File(appDir, "runtime")
    private val logsDir = File(appDir, "logs")
    private val appConfigFile = File(appDir, "app-config.json")
    private val profileStore = WindowsAtomicStore(appDir)
    private val authStateFile = File(appDir, "auth-state.json")
    private val authStateStore = WindowsEncryptedJsonStore(authStateFile)
    private val legacyConfigFile = File(runtimeDir, "subscription.singbox.json")
    private val accountConfigFile = File(runtimeDir, "account.singbox.json")
    private val manualConfigFile = File(runtimeDir, "manual.singbox.json")
    private val bootstrapConfigFile = File(runtimeDir, "bootstrap.singbox.json")
    private val rawConfigFile: File
        get() = profileFile(readConfig().profileSource)
    private val generatedConfigFile = File(runtimeDir, "config.json")
    private val xhttpSpecFile = File(runtimeDir, "xhttp-spec.json")
    private val generatedModeFile = File(runtimeDir, "generated-mode.txt")
    private val pidFile = File(runtimeDir, "sing-box.pid")
    private val processStateFile = File(runtimeDir, "process-state.json")
    private val systemProxyStateFile = File(runtimeDir, "system-proxy-before.json")
    private val roomNetPidFile = File(runtimeDir, "roomnet.pid")
    private val roomNetStateFile = File(runtimeDir, "roomnet-state.json")
    private val supportDir = File(appDir, "support")
    private val updatesDir = File(appDir, "updates")
    private val reportsDir = File(appDir, "reports")
    private val internetSettingsRegKey = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
    private val runRegKey = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

    val summary: String
        get() = "AppData: ${appDir.absolutePath}"

    fun readConfig(): WindowsAppConfig {
        ensureDirs()
        if (!appConfigFile.isFile) {
            writeConfig(WindowsAppConfig())
        }
        val json = profileStore.readText(appConfigFile, StandardCharsets.UTF_8)
        val source = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
        val configJson = source?.let {
            if (migrateWindowsConfigEndpoints(it)) gson.toJson(it) else json
        } ?: json
        if (configJson != json) {
            profileStore.writeAtomic(appConfigFile, configJson.toByteArray(StandardCharsets.UTF_8))
        }
        val normalized = normalizeConfig(gson.fromJson(configJson, WindowsAppConfig::class.java))
        val defaults = WindowsAppConfig()
        return normalized.copy(
            systemProxyEnabled = if (source?.has("systemProxyEnabled") == true) {
                normalized.systemProxyEnabled
            } else {
                defaults.systemProxyEnabled
            },
            restoreSystemProxyOnStop = if (source?.has("restoreSystemProxyOnStop") == true) {
                normalized.restoreSystemProxyOnStop
            } else {
                defaults.restoreSystemProxyOnStop
            },
            autoUploadRecoveryReports = if (source?.has("autoUploadRecoveryReports") == true) {
                normalized.autoUploadRecoveryReports
            } else {
                defaults.autoUploadRecoveryReports
            },
            allowProxyFallback = if (source?.has("allowProxyFallback") == true) {
                normalized.allowProxyFallback
            } else {
                defaults.allowProxyFallback
            },
            tunAutoRoute = if (source?.has("tunAutoRoute") == true) {
                normalized.tunAutoRoute
            } else {
                defaults.tunAutoRoute
            },
            tunStrictRoute = if (source?.has("tunStrictRoute") == true) {
                normalized.tunStrictRoute
            } else {
                defaults.tunStrictRoute
            },
            tunRouteAll = if (source?.has("tunRouteAll") == true) {
                normalized.tunRouteAll
            } else {
                defaults.tunRouteAll
            },
            enableRuBypass = if (source?.has("enableRuBypass") == true) {
                normalized.enableRuBypass
            } else {
                defaults.enableRuBypass
            },
            wholeTrafficMode = if (source?.has("wholeTrafficMode") == true) {
                normalized.wholeTrafficMode
            } else {
                defaults.wholeTrafficMode
            },
            bypassLan = if (source?.has("bypassLan") == true) {
                normalized.bypassLan
            } else {
                defaults.bypassLan
            },
            blockQuic = if (source?.has("blockQuic") == true) {
                normalized.blockQuic
            } else {
                defaults.blockQuic
            },
        ).let { resolved ->
            if (resolved.wholeTrafficMode && resolved.enableRuBypass) resolved.copy(enableRuBypass = false) else resolved
        }
    }

    fun writeConfig(config: WindowsAppConfig) {
        ensureDirs()
        profileStore.writeAtomic(appConfigFile, gson.toJson(config).toByteArray(StandardCharsets.UTF_8))
    }

    fun sessionState(): WindowsSessionState {
        ensureDirs()
        val auth = readAuthState(readConfig())
        val signedIn = auth != null && !auth.requiresReauthentication &&
            (auth.expiresAtEpochSecond > Instant.now().epochSecond || auth.refreshToken.isNotBlank())
        val hasImportedConfig = hasSavedProfile()
        return WindowsSessionState(
            signedIn = signedIn,
            hasImportedConfig = hasImportedConfig,
            ready = hasImportedConfig,
            summary = authSummary(),
            accountLabel = auth?.accountLabel.orEmpty(),
        )
    }

    internal fun availableRoutes(): List<WindowsProfileRoute> = runCatching {
        if (!rawConfigFile.isFile) emptyList()
        else windowsProfileRoutes(profileStore.readText(rawConfigFile, StandardCharsets.UTF_8))
    }.getOrDefault(emptyList())

    private fun profileFile(source: String): File = when (source) {
        "account" -> accountConfigFile
        "manual" -> manualConfigFile
        "bootstrap" -> bootstrapConfigFile
        else -> legacyConfigFile
    }

    private fun hasSavedProfile(): Boolean = runCatching {
        rawConfigFile.isFile && hasImportedTunnelProfile(profileStore.readText(rawConfigFile, StandardCharsets.UTF_8))
    }.getOrDefault(false)

    @Synchronized
    internal fun storeImportedProfile(
        source: String,
        json: String,
        subscriptionUrl: String? = null,
        bootstrapExpiresAtEpochMillis: Long = 0L,
    ): RuntimeCommandResult {
        var switchedFromBootstrap = false
        val saved = profileStore.transaction {
            if (source !in setOf("account", "manual", "bootstrap") || !hasImportedTunnelProfile(json)) {
                return@transaction RuntimeCommandResult(1, "Конфигурация не содержит поддерживаемого профиля GATES.")
            }
            ensureDirs()
            val config = readConfig()
            val selected = runCatching { windowsImportedProfileConfig(config, source, json) }.getOrElse {
                return@transaction RuntimeCommandResult(1, "Конфигурация не содержит выбранного маршрута. Прежний профиль сохранён.")
            }
            val target = profileFile(source)
            val oldProfile = target.takeIf { it.isFile }?.let(profileStore::readBytes)
            val oldSettings = profileStore.readBytes(appConfigFile)
            try {
                profileStore.writeAtomic(target, json.toByteArray(StandardCharsets.UTF_8))
                val updatedExpires = if (source == "bootstrap") bootstrapExpiresAtEpochMillis else 0L
                if (config.profileSource == "bootstrap" && source != "bootstrap") {
                    switchedFromBootstrap = true
                }
                writeConfig(
                    selected.copy(
                        subscriptionUrl = subscriptionUrl ?: config.subscriptionUrl,
                        bootstrapExpiresAtEpochMillis = updatedExpires,
                    ),
                )
                RuntimeCommandResult(0, "Конфигурация сохранена. Подключение не запущено.")
            } catch (_: Exception) {
                val restored = runCatching {
                    if (oldProfile != null) profileStore.writeAtomic(target, oldProfile)
                    else target.delete()
                    profileStore.writeAtomic(appConfigFile, oldSettings)
                }.isSuccess
                RuntimeCommandResult(1, if (restored) "Не удалось сохранить конфигурацию." else "Не удалось восстановить настройки после ошибки сохранения.")
            }
        }
        if (saved.exitCode == 0 && switchedFromBootstrap) {
            revokeBootstrapAccessBestEffort(readConfig())
        }
        return saved
    }

    fun appRoutingState(): AppRoutingState {
        val config = readConfig()
        return AppRoutingState(
            routeAll = config.tunRouteAll,
            proxyProcessNames = config.appProxyProcessNames,
            proxyProcessPaths = config.appProxyProcessPaths,
            directProcessNames = config.appDirectProcessNames,
            directProcessPaths = config.appDirectProcessPaths,
        )
    }

    fun listRunningApps(): List<WindowsProcessCandidate> {
        val javaProcesses = listRunningAppsFromJava()
        if (javaProcesses.isNotEmpty()) return javaProcesses

        val script = """
            Get-CimInstance Win32_Process |
              Where-Object { ${'$'}_.ExecutablePath -and ${'$'}_.ExecutablePath.ToLowerInvariant().EndsWith('.exe') } |
              ForEach-Object {
                [pscustomobject]@{
                  pid = [int64]${'$'}_.ProcessId
                  name = [System.IO.Path]::GetFileName(${'$'}_.ExecutablePath)
                  path = ${'$'}_.ExecutablePath
                }
              } |
              Sort-Object name,path -Unique |
              Select-Object -First 80 |
              ConvertTo-Json -Compress
        """.trimIndent()
        val result = powershell(script, timeoutSeconds = 20)
        if (result.exitCode != 0 || result.output.isBlank()) return emptyList()
        return runCatching {
            val json = JsonParser.parseString(result.output)
            val items = if (json.isJsonArray) json.asJsonArray.toList() else listOf(json)
            items.mapNotNull { item ->
                val obj = item.asJsonObject
                val path = obj.get("path")?.asString.orEmpty()
                val name = obj.get("name")?.asString.orEmpty()
                if (path.isBlank() || name.isBlank()) return@mapNotNull null
                WindowsProcessCandidate(
                    pid = obj.get("pid")?.asLong ?: 0L,
                    name = name,
                    path = path,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun listRunningAppsFromJava(): List<WindowsProcessCandidate> =
        ProcessHandle.allProcesses()
            .iterator()
            .asSequence()
            .mapNotNull { process ->
                val command = process.info().command().orElse("")
                if (!command.endsWith(".exe", ignoreCase = true)) return@mapNotNull null
                val file = File(command)
                val name = file.name.takeIf { it.endsWith(".exe", ignoreCase = true) } ?: return@mapNotNull null
                WindowsProcessCandidate(
                    pid = process.pid(),
                    name = name,
                    path = file.absolutePath,
                )
            }
            .distinctBy { it.path.lowercase() }
            .sortedWith(compareBy<WindowsProcessCandidate> { it.name.lowercase() }.thenBy { it.path.lowercase() })
            .take(80)
            .toList()

    fun writeCliTranscript(command: String, args: List<String>, result: RuntimeCommandResult): File {
        ensureDirs()
        val file = File(logsDir, "cli-last.txt")
        file.writeText(
            buildString {
                appendLine("captured_at=${Instant.now()}")
                appendLine("command=$command")
                appendLine("args=${redactedCliArguments(command, args).joinToString(" ")}")
                appendLine("exit=${result.exitCode}")
                append(result.output)
            },
            StandardCharsets.UTF_8,
        )
        return file
    }

    private fun normalizeConfig(config: WindowsAppConfig?): WindowsAppConfig {
        val defaults = WindowsAppConfig()
        if (config == null) return defaults
        val routeOrder = runCatching { config.routeOrder.filter { it.isNotBlank() } }
            .getOrDefault(defaults.routeOrder)
            .ifEmpty { defaults.routeOrder }
        val physicalBindRoutes = runCatching {
            config.physicalBindRoutes
                .map { it.lowercase() }
                .filter(::isWindowsProxyRouteId)
        }
            .getOrDefault(defaults.physicalBindRoutes)
            .ifEmpty { defaults.physicalBindRoutes }
        val testUrls = runCatching { config.testUrls.filter { it.startsWith("https://") } }
            .getOrDefault(defaults.testUrls)
            .ifEmpty { defaults.testUrls }
        val reportUrls = runCatching { config.reportUrls.filter { it.startsWith("https://") } }
            .getOrDefault(defaults.reportUrls)
            .ifEmpty { defaults.reportUrls }
        fun cleanList(values: List<String>?, fallback: List<String>, allowEmpty: Boolean = true): List<String> {
            val cleaned = runCatching {
                values.orEmpty()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
            }.getOrDefault(fallback)
            return if (!allowEmpty && cleaned.isEmpty()) fallback else cleaned
        }
        return WindowsAppConfig(
            subscriptionUrl = runCatching { config.subscriptionUrl }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: defaults.subscriptionUrl,
            profileSource = runCatching { config.profileSource }.getOrNull()
                ?.takeIf { it in setOf("legacy", "account", "manual", "bootstrap") }
                ?: defaults.profileSource,
            bootstrapExpiresAtEpochMillis = runCatching { config.bootstrapExpiresAtEpochMillis }.getOrDefault(0L)
                .takeIf { it >= 0L }
                ?: 0L,
            appApiBaseUrl = runCatching { config.appApiBaseUrl }.getOrNull()
                ?.trimEnd('/')
                ?.let(::migrateWindowsAppApiBaseUrl)
                ?.takeIf { it.startsWith("https://") }
                ?: defaults.appApiBaseUrl,
            authIssuer = runCatching { config.authIssuer }.getOrNull()
                ?.trimEnd('/')
                ?.let(::migrateWindowsAuthIssuer)
                ?.takeIf { it.startsWith("https://") }
                ?: defaults.authIssuer,
            authClientId = normalizeWindowsAuthClientId(
                runCatching { config.authClientId }.getOrNull(),
                defaults.authClientId,
            ),
            authDevice = runCatching { config.authDevice }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: defaults.authDevice,
            authRoom = runCatching { config.authRoom }.getOrNull()
                ?.trim()
                ?: defaults.authRoom,
            downloadPageUrl = runCatching { config.downloadPageUrl }.getOrNull()
                ?.takeIf { it.startsWith("https://") }
                ?: defaults.downloadPageUrl,
            autoCheckUpdates = runCatching { config.autoCheckUpdates }.getOrDefault(defaults.autoCheckUpdates),
            autoUploadRecoveryReports = runCatching { config.autoUploadRecoveryReports }
                .getOrDefault(defaults.autoUploadRecoveryReports),
            reportUrls = reportUrls,
            systemProxyEnabled = runCatching { config.systemProxyEnabled }.getOrDefault(defaults.systemProxyEnabled),
            restoreSystemProxyOnStop = runCatching { config.restoreSystemProxyOnStop }
                .getOrDefault(defaults.restoreSystemProxyOnStop),
            autoStartOnLogin = runCatching { config.autoStartOnLogin }.getOrDefault(defaults.autoStartOnLogin),
            runtimeMode = runCatching { config.runtimeMode }.getOrNull()
                ?.takeIf { it in listOf("tun", "system-proxy", "proxy") }
                ?: defaults.runtimeMode,
            killSwitchEnabled = runCatching { config.killSwitchEnabled }.getOrDefault(false),
            allowProxyFallback = runCatching { config.allowProxyFallback }.getOrDefault(defaults.allowProxyFallback),
            listenHost = runCatching { config.listenHost }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: defaults.listenHost,
            socksPort = runCatching { config.socksPort }.getOrDefault(defaults.socksPort)
                .takeIf { it in 1..65535 }
                ?: defaults.socksPort,
            httpPort = runCatching { config.httpPort }.getOrDefault(defaults.httpPort)
                .takeIf { it in 1..65535 }
                ?: defaults.httpPort,
            xhttpSidecarPort = runCatching { config.xhttpSidecarPort }.getOrDefault(defaults.xhttpSidecarPort)
                .takeIf { it in 1024..65535 && it != 38417 }
                ?: defaults.xhttpSidecarPort,
            tunInterfaceName = runCatching { config.tunInterfaceName }.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: defaults.tunInterfaceName,
            tunAddress = normalizeTunAddress(cleanList(config.tunAddress, defaults.tunAddress, allowEmpty = false)),
            tunMtu = runCatching { config.tunMtu }.getOrDefault(defaults.tunMtu)
                .takeIf { it in 1280..65535 }
                ?: defaults.tunMtu,
            tunStack = runCatching { config.tunStack }.getOrNull()
                ?.takeIf { it in listOf("system", "gvisor", "mixed") }
                ?: defaults.tunStack,
            tunAutoRoute = runCatching { config.tunAutoRoute }.getOrDefault(defaults.tunAutoRoute),
            tunStrictRoute = runCatching { config.tunStrictRoute }.getOrDefault(defaults.tunStrictRoute),
            tunRouteAll = runCatching { config.tunRouteAll }.getOrDefault(defaults.tunRouteAll),
            tunRouteExcludeAddress = cleanList(
                config.tunRouteExcludeAddress,
                defaults.tunRouteExcludeAddress,
                allowEmpty = false,
            ),
            appProxyProcessNames = cleanList(config.appProxyProcessNames, defaults.appProxyProcessNames),
            appProxyProcessPaths = cleanList(config.appProxyProcessPaths, defaults.appProxyProcessPaths),
            appDirectProcessNames = cleanList(config.appDirectProcessNames, defaults.appDirectProcessNames),
            appDirectProcessPaths = cleanList(config.appDirectProcessPaths, defaults.appDirectProcessPaths),
            roomNetEnabled = runCatching { config.roomNetEnabled }.getOrDefault(defaults.roomNetEnabled),
            roomNetLocalGameHost = runCatching { config.roomNetLocalGameHost }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: defaults.roomNetLocalGameHost,
            activeRoute = runCatching { config.activeRoute }.getOrNull()
                ?.takeIf { it == "auto" || isWindowsProxyRouteId(it) }
                ?: defaults.activeRoute,
            disableBridgeMultiplex = runCatching { config.disableBridgeMultiplex }.getOrNull()
                ?: defaults.disableBridgeMultiplex,
            physicalBindEnabled = runCatching { config.physicalBindEnabled }.getOrNull()
                ?: defaults.physicalBindEnabled,
            physicalBindInterface = runCatching { config.physicalBindInterface }.getOrNull()
                ?.trim()
                ?: defaults.physicalBindInterface,
            physicalBindIpv4 = runCatching { config.physicalBindIpv4 }.getOrNull()
                ?.trim()
                ?: defaults.physicalBindIpv4,
            physicalBindRoutes = physicalBindRoutes,
            routeOrder = routeOrder,
            enableRuBypass = runCatching { config.enableRuBypass }.getOrDefault(defaults.enableRuBypass),
            wholeTrafficMode = runCatching { config.wholeTrafficMode }.getOrDefault(defaults.wholeTrafficMode),
            bypassLan = runCatching { config.bypassLan }.getOrDefault(defaults.bypassLan),
            blockQuic = runCatching { config.blockQuic }.getOrDefault(defaults.blockQuic),
            healthcheckUrl = runCatching { config.healthcheckUrl }.getOrNull()
                ?.takeIf { it.startsWith("https://") }
                ?: defaults.healthcheckUrl,
            testUrls = testUrls,
        )
    }

    suspend fun run(command: String, route: String? = null): RuntimeCommandResult {
        if (accessOnly && command !in WINDOWS_ACCESS_ONLY_COMMANDS) {
            return RuntimeCommandResult(403, "Операция запрещена в изолированной проверке доступа.")
        }
        val result = runCatching {
            when (command) {
                "init" -> initialize()
                "login" -> {
                    val context = currentCoroutineContext()
                    runInterruptible(Dispatchers.IO) { login { context.ensureActive() } }
                }
                "import-app" -> importAuthenticatedAppConfig()
                "access-request" -> requestAppAccess()
                "bootstrap-claim" -> claimBootstrapAccess(route.orEmpty())
                "import-sub" -> importSubscription(route)
                "generate" -> generateConfig()
                "check" -> checkConfig()
                "connect" -> connect()
                "reconnect", "sync-runtime" -> reconnectRuntime()
                "start-proxy" -> startProxy()
                "stop" -> stop()
                "status" -> status()
                "protection-status" -> runtimeService.protectionStatus()
                "test" -> test()
                "probe-routes" -> probeRoutes()
                "support-bundle" -> supportBundle()
                "check-update" -> checkUpdate(download = false)
                "update" -> checkUpdate(download = true)
                "report-bug" -> reportBug(route.orEmpty())
                "set-route" -> setRoute(route ?: "auto")
                "set-runtime" -> setRuntimeMode(route ?: "tun")
                "set-kill-switch" -> setKillSwitch(route)
                "set-tun-routing" -> setTunRouting(route ?: "all")
                "set-ru-bypass" -> setRuBypass(parseBooleanArg(route) ?: return RuntimeCommandResult(1, "Expected true or false"))
                "set-whole-traffic" -> setWholeTraffic(parseBooleanArg(route) ?: return RuntimeCommandResult(1, "Expected true or false"))
                "set-bypass-lan" -> setBypassLan(parseBooleanArg(route) ?: return RuntimeCommandResult(1, "Expected true or false"))
                "set-block-quic" -> setBlockQuic(parseBooleanArg(route) ?: return RuntimeCommandResult(1, "Expected true or false"))
                "set-route-order" -> setRouteOrder(route.orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() })
                "apps" -> appRoutingSummary()
                "running-apps" -> runningAppsSummary()
                "add-app-proxy" -> addAppRouting(route.orEmpty(), proxy = true)
                "add-app-direct" -> addAppRouting(route.orEmpty(), proxy = false)
                "remove-app-proxy" -> removeAppRouting(route.orEmpty(), proxy = true)
                "remove-app-direct" -> removeAppRouting(route.orEmpty(), proxy = false)
                "enable-system-proxy" -> enableSystemProxy(readConfig())
                "disable-system-proxy" -> disableSystemProxy()
                "enable-autostart" -> setAutoStart(enabled = true)
                "disable-autostart" -> setAutoStart(enabled = false)
                "install-service" -> WindowsRuntimeServiceManager().install()
                "uninstall-service" -> WindowsRuntimeServiceManager().uninstall()
                "service-status" -> WindowsRuntimeServiceManager().status()
                "prepare-runtime" -> if (readConfig().runtimeMode == "tun") prepareRuntimeWithoutTunnel()
                    else RuntimeCommandResult(0, "Runtime preparation is not required for proxy mode")
                "roomnet-info" -> roomNetInfo()
                "roomnet-start" -> startRoomNet()
                "roomnet-stop" -> stopRoomNet()
                "roomnet-worker" -> roomNetWorker()
                "adopt-state" -> adoptStateFrom(File(route ?: error("Missing source runtime directory")))
                "logs" -> logs()
                else -> RuntimeCommandResult(2, "Unknown command: $command")
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            RuntimeCommandResult(1, error.message ?: error.toString())
        }
        recordWindowsCommandResult(appDir, command, result)
        return result
    }

    private fun initialize(): RuntimeCommandResult {
        ensureDirs()
        extractRuntime()
        if (!appConfigFile.isFile) {
            writeConfig(WindowsAppConfig())
        }
        val version = runProcess(listOf(singBox().absolutePath, "version"), binDir, timeoutSeconds = 20)
        return RuntimeCommandResult(
            version.exitCode,
            buildString {
                appendLine("Initialized GATES Windows runtime")
                appendLine("App data: ${appDir.absolutePath}")
                appendLine("sing-box: ${singBox().absolutePath}")
                append(version.output)
            }.trim(),
        )
    }

    private fun login(checkActive: () -> Unit = {}): RuntimeCommandResult {
        try {
        authorizationPhase.value = WindowsAuthorizationPhase.Starting
        checkActive()
        ensureDirs()
        val config = readConfig()
        val metadata = try {
            fetchJson("${config.authIssuer}/.well-known/openid-configuration", secureAuth = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return RuntimeCommandResult(
                1,
                "Authorization server metadata is unavailable.",
                accessFailure = WindowsAccessFailure(
                    WindowsAccessStage.AUTHORIZATION,
                    WindowsAccessCode.AUTH_START_FAILED,
                ),
            )
        }
        if (metadata.get("issuer")?.asString?.trimEnd('/') != config.authIssuer.trimEnd('/')) {
            return RuntimeCommandResult(
                1,
                "Authorization issuer mismatch",
                accessFailure = WindowsAccessFailure(
                    WindowsAccessStage.AUTHORIZATION,
                    WindowsAccessCode.AUTH_START_FAILED,
                ),
            )
        }
        val (deviceEndpoint, tokenEndpoint) = runCatching {
            val device = validatedWindowsAuthEndpoint(config.authIssuer, metadata.get("device_authorization_endpoint")?.asString
                ?.takeIf { it.isNotBlank() }
                ?: "${config.authIssuer}/protocol/openid-connect/auth/device")
            val token = validatedWindowsAuthEndpoint(config.authIssuer, metadata.get("token_endpoint")?.asString
                ?.takeIf { it.isNotBlank() }
                ?: error("Keycloak metadata has no token endpoint"))
            device to token
        }.getOrElse {
            return RuntimeCommandResult(
                1,
                "Authorization endpoints are invalid.",
                accessFailure = WindowsAccessFailure(
                    WindowsAccessStage.AUTHORIZATION,
                    WindowsAccessCode.AUTH_START_FAILED,
                ),
            )
        }
        val pkce = newPkcePair()
        val deviceResponse = try {
            postForm(
                deviceEndpoint,
                mapOf(
                    "client_id" to config.authClientId,
                    "scope" to "openid profile email",
                    "code_challenge" to pkce.challenge,
                    "code_challenge_method" to "S256",
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return RuntimeCommandResult(
                1,
                "Login start failed.",
                accessFailure = WindowsAccessFailure(
                    WindowsAccessStage.AUTHORIZATION,
                    WindowsAccessCode.AUTH_START_FAILED,
                ),
            )
        }
        if (deviceResponse.statusCode() !in 200..299) {
            return RuntimeCommandResult(
                deviceResponse.statusCode(),
                "Login start failed: HTTP ${deviceResponse.statusCode()}",
                accessFailure = windowsAccessFailure(WindowsAccessStage.AUTHORIZATION, deviceResponse.statusCode()),
            )
        }

        val device = runCatching {
            val body = JsonParser.parseString(deviceResponse.body()).asJsonObject
            WindowsDeviceAuthorization(
                deviceCode = body.get("device_code")?.asString ?: error("device_code is missing"),
                userCode = body.get("user_code")?.asString.orEmpty(),
                verificationUri = validatedWindowsAuthEndpoint(config.authIssuer, body.get("verification_uri_complete")?.asString
                    ?: body.get("verification_uri")?.asString
                    ?: error("verification_uri is missing")),
                expiresIn = (body.get("expires_in")?.asLong ?: 600L).coerceIn(1L, 900L),
                intervalSeconds = (body.get("interval")?.asLong ?: 5L).coerceIn(2L, 30L),
            )
        }.getOrElse {
            return RuntimeCommandResult(
                1,
                "Login start failed: invalid authorization response.",
                accessFailure = WindowsAccessFailure(
                    WindowsAccessStage.AUTHORIZATION,
                    WindowsAccessCode.AUTH_START_FAILED,
                ),
            )
        }
        var intervalSeconds = device.intervalSeconds

        checkActive()
        authorizationPrompt.value = WindowsAuthorizationPrompt(device.verificationUri, device.userCode)
        authorizationPhase.value = WindowsAuthorizationPhase.Waiting
        val opened = openUri(device.verificationUri)
        val startedAt = Instant.now().epochSecond
        val deadline = startedAt + device.expiresIn
        while (Instant.now().epochSecond < deadline) {
            checkActive()
            val pauseMillis = intervalSeconds.coerceIn(2L, 60L) * 1000L
            authorizationPause?.invoke(pauseMillis) ?: Thread.sleep(pauseMillis)
            checkActive()
            val tokenResponse = try {
                postForm(
                    tokenEndpoint,
                    mapOf(
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                        "device_code" to device.deviceCode,
                        "client_id" to config.authClientId,
                        "code_verifier" to pkce.verifier,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return RuntimeCommandResult(
                    1,
                    "Login token exchange failed.",
                    accessFailure = WindowsAccessFailure(
                        WindowsAccessStage.TOKEN,
                        WindowsAccessCode.TRANSPORT_ERROR,
                    ),
                )
            }
            if (tokenResponse.statusCode() in 200..299) {
                synchronized(authorizationCommitLock) {
                    checkActive()
                    authorizationPrompt.value = null
                    authorizationPhase.value = WindowsAuthorizationPhase.ReceivingProfile
                }
                val tokenPayload = runCatching {
                    JsonParser.parseString(tokenResponse.body()).asJsonObject
                }.getOrElse {
                    return RuntimeCommandResult(
                        1,
                        "Login token response is invalid.",
                        accessFailure = WindowsAccessFailure(
                            WindowsAccessStage.TOKEN,
                            WindowsAccessCode.UNKNOWN,
                        ),
                    )
                }
                try {
                    saveAuthState(config, tokenEndpoint, tokenPayload, readAuthState(config))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return RuntimeCommandResult(
                        1,
                        "Login token could not be stored.",
                        accessFailure = WindowsAccessFailure(
                            WindowsAccessStage.TOKEN,
                            WindowsAccessCode.UNKNOWN,
                        ),
                    )
                }
                checkActive()
                refreshAccountLabel(config, metadata, tokenPayload)
                checkActive()
                val imported = importAuthenticatedAppConfig()
                return RuntimeCommandResult(
                    imported.exitCode,
                    buildString {
                        appendLine("Signed in with Keycloak.")
                        if (opened) {
                            appendLine("Browser was opened for device authorization.")
                        }
                        append(imported.output)
                    }.trim(),
                    accessFailure = imported.accessFailure,
                    accessPending = imported.accessPending,
                )
            }

            val error = runCatching {
                JsonParser.parseString(tokenResponse.body()).asJsonObject.get("error")?.asString
            }.getOrNull().orEmpty()
            when (error) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalSeconds += 5
                "access_denied" -> return RuntimeCommandResult(
                    1,
                    "Login was denied.",
                    accessFailure = WindowsAccessFailure(WindowsAccessStage.AUTHORIZATION, WindowsAccessCode.AUTH_DENIED),
                )
                "expired_token" -> return RuntimeCommandResult(
                    1,
                    "Login expired. Start sign in again.",
                    accessFailure = WindowsAccessFailure(WindowsAccessStage.AUTHORIZATION, WindowsAccessCode.AUTH_EXPIRED),
                )
                else -> return RuntimeCommandResult(
                    tokenResponse.statusCode(),
                    "Login token exchange failed: HTTP ${tokenResponse.statusCode()}",
                    accessFailure = windowsTokenAccessFailure(tokenResponse.statusCode(), tokenResponse.body()),
                )
            }
        }

        return RuntimeCommandResult(
            1,
            buildString {
                append("Login timed out.")
                if (!opened) append(" Browser could not be opened.")
            },
            accessFailure = WindowsAccessFailure(WindowsAccessStage.AUTHORIZATION, WindowsAccessCode.AUTH_TIMEOUT),
        )
        } finally {
            authorizationPrompt.value = null
            authorizationPhase.value = WindowsAuthorizationPhase.Idle
        }
    }

    private fun importAuthenticatedAppConfig(): RuntimeCommandResult {
        ensureDirs()
        val config = readConfig()
        val token = try {
            freshAccessToken(config)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return RuntimeCommandResult(
                1,
                "Session refresh failed.",
                accessFailure = WindowsAccessFailure(
                    WindowsAccessStage.PROFILE,
                    windowsSessionRefreshFailure(error),
                ),
            )
        }
        if (token == null) {
            return RuntimeCommandResult(
                401,
                "Sign in first.",
                accessFailure = WindowsAccessFailure(
                    WindowsAccessStage.PROFILE,
                    WindowsAccessCode.AUTH_REQUIRED,
                    401,
                ),
            )
        }
        val query = mutableMapOf(
            "platform" to WINDOWS_PLATFORM,
            "device" to config.authDevice,
        )
        if (config.authRoom.isNotBlank()) {
            query["room"] = config.authRoom
        }
        val response = try {
            sendAppApiGetWithFallback(
                config = config,
                path = "/api/app/singbox",
                query = query,
                bearerToken = token,
                extraHeaders = mapOf("X-Yokhan-Device" to config.authDevice),
                timeoutSeconds = 30,
                allowRedirects = false,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return RuntimeCommandResult(
                1,
                "App config request failed: transport error.",
                accessFailure = WindowsAccessFailure(
                    WindowsAccessStage.PROFILE,
                    WindowsAccessCode.TRANSPORT_ERROR,
                ),
            )
        }
        if (response.statusCode() !in 200..299) {
            if (response.statusCode() == 401) requireReauthentication(config)
            val backendCode = windowsBackendAccessCode(response.body())
            var failure = windowsAccessFailure(WindowsAccessStage.PROFILE, response.statusCode(), backendCode)
            if (failure.code == WindowsAccessCode.ACCESS_NOT_LINKED) {
                failure = probeAppAccess(config, token, failure)
            }
            return RuntimeCommandResult(
                response.statusCode(),
                "App config fetch failed: HTTP ${response.statusCode()}" +
                    backendCode?.let { " ($it)" }.orEmpty(),
                accessFailure = failure,
                accessPending = failure.code == WindowsAccessCode.ACCESS_PENDING,
            )
        }
        val stored = storeImportedProfile("account", response.body())
        return if (stored.exitCode == 0) stored else stored.copy(
            accessFailure = windowsProfileAccessFailure(stored.output),
        )
    }

    /** Distinguish an open access request from a genuinely unlinked account. */
    private fun probeAppAccess(
        config: WindowsAppConfig,
        token: String,
        original: WindowsAccessFailure,
    ): WindowsAccessFailure {
        val response = try {
            sendAppApiGetWithFallback(
                config = config,
                path = "/api/app/me",
                bearerToken = token,
                extraHeaders = mapOf("X-Yokhan-Device" to config.authDevice),
                timeoutSeconds = 12,
                allowRedirects = false,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return original
        }
        if (response.statusCode() in 200..299) {
            val pending = runCatching {
                JsonParser.parseString(response.body()).asJsonObject.get("status")?.asString
            }.getOrNull() == "pending"
            return if (pending) {
                WindowsAccessFailure(WindowsAccessStage.PROFILE, WindowsAccessCode.ACCESS_PENDING, response.statusCode())
            } else {
                original
            }
        }
        if (response.statusCode() == 401) {
            requireReauthentication(config)
            return WindowsAccessFailure(WindowsAccessStage.PROFILE, WindowsAccessCode.AUTH_REQUIRED, 401)
        }
        val probed = windowsAccessFailure(
            WindowsAccessStage.PROFILE,
            response.statusCode(),
            windowsBackendAccessCode(response.body()),
        )
        return if (probed.code in setOf(
                WindowsAccessCode.ACCESS_NOT_LINKED,
                WindowsAccessCode.USER_NOT_FOUND,
                WindowsAccessCode.DEVICE_NOT_FOUND,
                WindowsAccessCode.ROOM_FORBIDDEN,
            )
        ) {
            probed
        } else {
            original
        }
    }

    private fun requestAppAccess(): RuntimeCommandResult {
        ensureDirs()
        val config = readConfig()
        val token = try {
            freshAccessToken(config)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return RuntimeCommandResult(
                1,
                "Session refresh failed.",
                accessFailure = WindowsAccessFailure(
                    WindowsAccessStage.ACCESS_REQUEST,
                    windowsSessionRefreshFailure(error),
                ),
            )
        }
        if (token == null) {
            return RuntimeCommandResult(
                401,
                "Sign in first.",
                accessFailure = WindowsAccessFailure(
                    WindowsAccessStage.ACCESS_REQUEST,
                    WindowsAccessCode.AUTH_REQUIRED,
                    401,
                ),
            )
        }
        val payload = JsonObject().apply { addProperty("device", config.authDevice) }
        val request = HttpRequest.newBuilder(URI.create(appEndpoint(config, "/api/app/access-request")))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $token")
            .header("X-Yokhan-Device", config.authDevice)
            .header("X-Yokhan-Client", "GATES-Windows")
            .header("X-Yokhan-App-Version", WINDOWS_APP_VERSION)
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
            .build()
        val response = try {
            httpClient(allowRedirects = false).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return RuntimeCommandResult(
                1,
                "Access request failed: transport error.",
                accessFailure = WindowsAccessFailure(
                    WindowsAccessStage.ACCESS_REQUEST,
                    WindowsAccessCode.TRANSPORT_ERROR,
                ),
            )
        }
        if (response.statusCode() !in 200..299) {
            if (response.statusCode() == 401) requireReauthentication(config)
            val backendCode = windowsBackendAccessCode(response.body())
            val failure = windowsAccessFailure(WindowsAccessStage.ACCESS_REQUEST, response.statusCode(), backendCode)
            return RuntimeCommandResult(
                response.statusCode(),
                "Access request failed: HTTP ${response.statusCode()}" +
                    backendCode?.let { " ($it)" }.orEmpty(),
                accessFailure = failure,
                accessPending = failure.code == WindowsAccessCode.ACCESS_PENDING,
            )
        }
        return RuntimeCommandResult(
            0,
            "Access request is pending.",
            accessPending = true,
        )
    }

    /**
     * Pre-login Tree gate: a short-lived XHTTP subscription for exactly one
     * device. The temporary UUID is published to the XHTTP pilot by the
     * existing sync endpoint and disappears together with the server state.
     */
    private fun claimBootstrapAccess(code: String): RuntimeCommandResult {
        ensureDirs()
        val config = readConfig()
        val normalizedCode = code.trim()
        if (normalizedCode.isBlank()) return RuntimeCommandResult(1, "Введите пароль Древа.")
        val deviceId = bootstrapDeviceId()
        val payload = JsonObject().apply {
            addProperty("code", normalizedCode)
            addProperty("device_id", deviceId)
            addProperty("platform", "windows")
        }
        val response = try {
            sendAppApiPostWithFallback(
                config = config,
                path = "/api/bootstrap/claim",
                bodyJson = gson.toJson(payload),
                timeoutSeconds = 25,
                extraHeaders = mapOf("X-Yokhan-Device" to deviceId),
                allowRedirects = false,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return RuntimeCommandResult(1, "Не удалось связаться с сервером разового доступа.")
        }
        if (response.statusCode() !in 200..299) {
            val message = when (response.statusCode()) {
                403 -> "Древо молчит: пароль не подошёл. Попробуйте ещё раз."
                429 -> "Разовый доступ для этого устройства уже использован. Следующий — через 24 часа."
                503 -> "Разовый доступ временно недоступен."
                else -> "Разовый доступ не выдан: HTTP ${response.statusCode()}."
            }
            return RuntimeCommandResult(response.statusCode(), message)
        }
        val bodyObj = runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrNull()
        val subscriptionUrl = bodyObj?.get("subscription_url")?.asString?.trim().orEmpty()
        if (subscriptionUrl.isBlank()) {
            return RuntimeCommandResult(1, "Сервер не вернул подписку.")
        }
        val now = System.currentTimeMillis()
        val expiresAtMillis = bodyObj?.get("expires_at")?.takeIf { it.isJsonPrimitive }?.let { prim ->
            runCatching { prim.asLong * 1000L }.getOrNull()
        }?.takeIf { it > now } ?: (now + 15 * 60 * 1000L)
        val imported = importSubscription(
            input = subscriptionUrl,
            source = "bootstrap",
            bootstrapExpiresAtEpochMillis = expiresAtMillis,
        )
        if (imported.exitCode != 0) return imported
        val connected = connect()
        return if (connected.exitCode == 0) {
            awaitBootstrapRelayReady(readConfig())
            RuntimeCommandResult(
                0,
                "Проход Древа открыт на 15 минут. Войдите в аккаунт GATES или вставьте личную подписку.",
            )
        } else {
            connected
        }
    }

    private fun awaitBootstrapRelayReady(config: WindowsAppConfig) {
        if (authHttpClient != null) return
        if (!allowRuntimeServiceDelegation && managedProcess()?.isAlive != true) return
        val probeUrl = appEndpoint(config, "/api/health")
        val deadline = System.currentTimeMillis() + 6_500L
        while (System.currentTimeMillis() < deadline) {
            val res = runCatching { curl(config, probeUrl, timeoutSeconds = 3) }.getOrNull()
            if (res != null && res.exitCode == 0 && res.output.trimStart().startsWith("2")) return
            runCatching { Thread.sleep(1_500L) }
        }
    }

    private fun revokeBootstrapAccessBestEffort(config: WindowsAppConfig) {
        runCatching {
            val deviceId = bootstrapDeviceId()
            val payload = JsonObject().apply { addProperty("device_id", deviceId) }
            sendAppApiPostWithFallback(
                config = config,
                path = "/api/bootstrap/revoke",
                bodyJson = gson.toJson(payload),
                timeoutSeconds = 6,
                extraHeaders = mapOf("X-Yokhan-Device" to deviceId),
                allowRedirects = false,
            )
        }
    }

    private fun bootstrapDeviceId(): String {
        val file = File(appDir, "bootstrap-device-id")
        if (file.isFile) {
            val existing = runCatching {
                file.readText(StandardCharsets.UTF_8).trim()
            }.getOrDefault("")
            if (existing.matches(Regex("^[A-Za-z0-9._:-]{8,128}$"))) return existing
        }
        val id = "win-" + UUID.randomUUID().toString()
        runCatching { file.writeText(id, StandardCharsets.UTF_8) }
        return id
    }

    private fun importSubscription(
        input: String? = null,
        source: String = "manual",
        bootstrapExpiresAtEpochMillis: Long = 0L,
    ): RuntimeCommandResult {
        ensureDirs()
        val config = readConfig()
        val subscriptionUrl = input?.trim() ?: config.subscriptionUrl
        val url = runCatching { subscriptionJsonEndpoint(subscriptionUrl) }.getOrElse {
            return RuntimeCommandResult(1, "Укажите HTTPS-ссылку подписки GATES.")
        }
        return try {
            val json = fetchSubscriptionJson?.invoke(url) ?: run {
                val request = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .GET().build()
                val response = httpClient(allowRedirects = false).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                if (response.statusCode() !in 200..299) {
                    return RuntimeCommandResult(response.statusCode(), "Не удалось получить подписку: HTTP ${response.statusCode()}.")
                }
                response.body()
            }
            storeImportedProfile(source, json, subscriptionUrl, bootstrapExpiresAtEpochMillis)
        } catch (_: Exception) {
            RuntimeCommandResult(1, "Не удалось получить подписку. Сохранённые профили не изменены.")
        }
    }

    @Synchronized
    private fun generateConfig(forceProxyMode: Boolean = false): RuntimeCommandResult {
        ensureDirs()
        if (!rawConfigFile.isFile) {
            return RuntimeCommandResult(1, "Missing imported config. Run Import first.")
        }
        val appConfig = readConfig()
        val runtimeMode = effectiveRuntimeMode(appConfig, forceProxyMode)
        val root = JsonParser.parseString(profileStore.readText(rawConfigFile, StandardCharsets.UTF_8)).asJsonObject
        root.add("inbounds", buildInbounds(appConfig, runtimeMode))
        val xhttpPlan = if (windowsXhttpEnabled(root)) {
            if (appConfig.activeRoute != WINDOWS_XHTTP_ROUTE_TAG) {
                stripWindowsXhttpOutbound(root)
                null
            } else {
                if (runtimeMode != "tun") {
                    return RuntimeCommandResult(1, "XHTTP транспорт доступен только в режиме TUN через GATES Runtime service.")
                }
                val sidecarPort = chooseWindowsXhttpPort(appConfig.xhttpSidecarPort) { port ->
                    port != appConfig.socksPort && port != appConfig.httpPort
                } ?: return RuntimeCommandResult(1, "Нет свободного локального порта для XHTTP sidecar.")
                val bind = if (appConfig.physicalBindEnabled == true) {
                    resolvePhysicalBindTarget(appConfig)?.let { WindowsXhttpBind(it.interfaceName, it.ipv4) }
                } else {
                    null
                }
                val bindInterface = bind?.interfaceName?.takeIf { it.isNotBlank() }
                if (bindInterface == null || bindInterface.equals(appConfig.tunInterfaceName, ignoreCase = true)) {
                    return RuntimeCommandResult(
                        1,
                        "XHTTP в режиме TUN требует Physical bind к физическому интерфейсу (не TUN).",
                    )
                }
                try {
                    applyWindowsXhttpTransport(root, appConfig.copy(xhttpSidecarPort = sidecarPort), bind)
                } catch (failure: IllegalArgumentException) {
                    return RuntimeCommandResult(1, failure.message ?: "XHTTP профиль не поддерживается.")
                }
            }
        } else {
            null
        }
        normalizeLog(root)
        normalizeDns(root, appConfig)
        normalizeRoute(root, appConfig, runtimeMode)
        applyPhysicalBind(root, appConfig)
        generatedModeFile.writeText(runtimeMode, StandardCharsets.US_ASCII)
        generatedConfigFile.writeText(gson.toJson(root), StandardCharsets.UTF_8)
        if (xhttpPlan != null) {
            val pin = xrayPinProvider()
                ?: return RuntimeCommandResult(1, "XHTTP runtime is not installed or is not pinned. Reinstall GATES.")
            val finalRoute = root.getAsJsonObject("route")?.get("final")?.asString
            val spec = windowsXhttpSpecJson(
                xhttpPlan,
                required = finalRoute == xhttpPlan.outboundTag || isBootstrapProfile(appConfig),
                executableSha256 = pin,
            )
            profileStore.writeAtomic(xhttpSpecFile, spec.toByteArray(StandardCharsets.UTF_8))
        } else {
            runCatching { xhttpSpecFile.delete() }
        }
        return (validateGeneratedConfig?.invoke(generatedConfigFile) ?: checkConfig()).let { check ->
            if (check.exitCode == 0) {
                RuntimeCommandResult(
                    0,
                    buildString {
                        appendLine("Generated $runtimeMode config: ${generatedConfigFile.absolutePath}")
                        append("Physical bind: ${physicalBindSummary(appConfig)}")
                    }.trim(),
                )
            } else {
                check
            }
        }
    }

    private fun effectiveRuntimeMode(config: WindowsAppConfig, forceProxyMode: Boolean = false): String =
        if (forceProxyMode || config.runtimeMode != "tun") "proxy" else "tun"

    private fun buildInbounds(config: WindowsAppConfig, runtimeMode: String): JsonArray =
        JsonArray().apply {
            if (runtimeMode == "tun") {
                add(JsonObject().apply {
                    addProperty("type", "tun")
                    addProperty("tag", "tun-in")
                    addProperty("interface_name", config.tunInterfaceName)
                    add("address", JsonArray().also { array ->
                        config.tunAddress.forEach { array.add(it) }
                    })
                    addProperty("mtu", config.tunMtu)
                    addProperty("auto_route", config.tunAutoRoute)
                    addProperty("strict_route", config.tunStrictRoute)
                    add("route_exclude_address", JsonArray().also { array ->
                        effectiveTunExcludeAddresses(config).forEach { array.add(it) }
                    })
                    addProperty("stack", config.tunStack)
                })
            }
            add(JsonObject().apply {
                addProperty("type", "mixed")
                addProperty("tag", "mixed-in")
                addProperty("listen", config.listenHost)
                addProperty("listen_port", config.socksPort)
            })
            add(JsonObject().apply {
                addProperty("type", "http")
                addProperty("tag", "http-in")
                addProperty("listen", config.listenHost)
                addProperty("listen_port", config.httpPort)
            })
        }

    private fun checkConfig(): RuntimeCommandResult {
        if (!generatedConfigFile.isFile) {
            return RuntimeCommandResult(1, "Missing generated config. Run Generate first.")
        }
        return runProcess(
            listOf(singBox().absolutePath, "check", "-c", generatedConfigFile.absolutePath),
            binDir,
            timeoutSeconds = 30,
        )
    }

    @Synchronized
    private fun connect(): RuntimeCommandResult {
        ensureDirs()
        val config = readConfig()
        if (allowRuntimeServiceDelegation && runtimeService.health().isRunningServiceTunnel()) {
            if (config.killSwitchEnabled && parseWindowsProtectionState(runtimeService.protectionStatus()) != WindowsProtectionState.ARMED) {
                return RuntimeCommandResult(1, "GATES_PROTECTION_NOT_ARMED")
            }
            return RuntimeCommandResult(
                0,
                "Runtime is already running in GATES Runtime service. Saved configurations apply on the next connection.",
            )
        }
        val existingId = managedProcessId()
        val existingProcess = managedProcess()
        if (isManagedProcessRunning(existingId, existingProcess)) {
            if (config.killSwitchEnabled) return RuntimeCommandResult(1, "GATES_PROTECTION_REQUIRES_SERVICE")
            return RuntimeCommandResult(
                0,
                "Runtime is already running ${runningRuntimeModeSummary()} runtime. Saved configurations apply on the next connection.",
            )
        }
        extractRuntime()
        val steps = mutableListOf<String>()
        if (
            isBootstrapProfile(config) &&
            config.bootstrapExpiresAtEpochMillis in 1..System.currentTimeMillis()
        ) {
            return RuntimeCommandResult(
                1,
                "Время разового доступа Древа истекло. Войдите в аккаунт или запросите доступ снова.",
            )
        }
        if (
            readAuthState(config) == null &&
            !rawConfigFile.isFile &&
            config.subscriptionUrl.contains("REPLACE_WITH_PERSONAL_TOKEN")
        ) {
            val signedIn = login()
            steps += "login exit=${signedIn.exitCode}"
            if (signedIn.exitCode != 0) {
                return RuntimeCommandResult(signedIn.exitCode, steps.joinToString("\n") + "\n" + signedIn.output)
            }
        }
        if (readAuthState(config) != null && config.profileSource != "manual") {
            val imported = runCatching { importAuthenticatedAppConfig() }.getOrElse {
                RuntimeCommandResult(1, "Не удалось обновить аккаунтную конфигурацию.")
            }
            steps += "import-app exit=${imported.exitCode}"
            if (imported.exitCode != 0 && !rawConfigFile.isFile) {
                return RuntimeCommandResult(imported.exitCode, steps.joinToString("\n") + "\n" + imported.output)
            }
        }
        if (!rawConfigFile.isFile) {
            val imported = importSubscription()
            steps += "import-sub exit=${imported.exitCode}"
            if (imported.exitCode != 0) {
                return RuntimeCommandResult(imported.exitCode, steps.joinToString("\n") + "\n" + imported.output)
            }
        }
        val selectedRoute = readConfig().activeRoute
        if (selectedRoute != "auto" && availableRoutes().none { it.tag == selectedRoute }) {
            return RuntimeCommandResult(1, "Selected route is not available in the imported profile")
        }
        val generated = generateConfig()
        steps += "generate exit=${generated.exitCode}"
        if (generated.exitCode != 0) {
            return RuntimeCommandResult(generated.exitCode, steps.joinToString("\n") + "\n" + generated.output)
        }
        val generatedMode = generatedRuntimeModeSummary()
        if (config.killSwitchEnabled) {
            if (generatedMode != "tun" || !allowRuntimeServiceDelegation) {
                return RuntimeCommandResult(1, "GATES_PROTECTION_REQUIRES_TUN")
            }
            return startThroughRuntimeService()
        }
        if (shouldRequestTunElevation(config, generatedMode)) {
            if (allowRuntimeServiceDelegation) {
                val service = startThroughRuntimeService()
                steps += "runtime-helper-connect exit=${service.exitCode}"
                return RuntimeCommandResult(service.exitCode, steps.joinToString("\n") + "\n" + service.output)
            }
            val elevated = requestElevatedCli("connect", expectedRunning = true)
            steps += "elevate-connect exit=${elevated.exitCode}"
            return RuntimeCommandResult(elevated.exitCode, steps.joinToString("\n") + "\n" + elevated.output)
        }
        val started = startProxy()
        steps += "start-runtime exit=${started.exitCode}"
        val startedMode = generatedRuntimeModeSummary()
        if (started.exitCode != 0 && config.runtimeMode == "tun" && startedMode == "tun") {
            val elevated = if (
                isTunPermissionFailure(started.output) &&
                !isRunningAsAdministrator() &&
                allowRuntimeServiceDelegation
            ) {
                val delegated = startThroughRuntimeService()
                steps += "runtime-helper-connect-after-error exit=${delegated.exitCode}"
                delegated
            } else if (isTunPermissionFailure(started.output) && !isRunningAsAdministrator()) {
                val requested = requestElevatedCli("connect", expectedRunning = true)
                steps += "elevate-connect-after-error exit=${requested.exitCode}"
                requested
            } else {
                RuntimeCommandResult(
                    started.exitCode,
                    "TUN failed. Proxy fallback was not used; set runtime to proxy explicitly if a fallback is needed.",
                )
            }
            return RuntimeCommandResult(
                elevated.exitCode,
                steps.joinToString("\n") + "\n" + started.output + "\n" + elevated.output,
                recoveryFailureStage = if (elevated.exitCode == 0) null else started.recoveryFailureStage,
            )
        }
        val systemProxy = if (started.exitCode == 0 && startedMode != "tun" && config.systemProxyEnabled) {
            enableSystemProxy(config).also {
                steps += "system-proxy exit=${it.exitCode}"
            }
        } else {
            RuntimeCommandResult(0, "")
        }
        val roomNet = startRoomNetForConnect()
        steps += "roomnet-start exit=${roomNet.exitCode}"
        return RuntimeCommandResult(
            when {
                started.exitCode != 0 -> started.exitCode
                systemProxy.exitCode != 0 -> systemProxy.exitCode
                else -> 0
            },
            buildString {
                appendLine(steps.joinToString("\n"))
                append(started.output)
                if (systemProxy.output.isNotBlank()) {
                    appendLine()
                    append(systemProxy.output)
                }
                if (roomNet.output.isNotBlank()) {
                    appendLine()
                    append(roomNet.output)
                }
            }.trim(),
            recoveryFailureStage = started.recoveryFailureStage,
        )
    }

    @Synchronized
    private fun startProxy(): RuntimeCommandResult {
        ensureDirs()
        if (xhttpSpecFile.isFile) {
            return RuntimeCommandResult(1, "XHTTP транспорт требует GATES Runtime service.")
        }
        if (readConfig().killSwitchEnabled) {
            return RuntimeCommandResult(1, "GATES_PROTECTION_REQUIRES_SERVICE")
        }
        val existing = managedProcess()
        if (existing != null && existing.isAlive) {
            return RuntimeCommandResult(0, "Already running ${runningRuntimeModeSummary()} runtime. PID ${existing.pid()}")
        }
        val check = checkConfig()
        if (check.exitCode != 0) {
            return check
        }

        val stamp = Instant.now().toString().replace(":", "-")
        val outLog = File(logsDir, "sing-box-$stamp.out.log")
        val errLog = File(logsDir, "sing-box-$stamp.err.log")
        val runtimeMode = generatedRuntimeModeSummary()
        if (runtimeMode == "tun") {
            val prepared = prepareTunInterfaceForStart(readConfig())
            if (prepared.exitCode != 0) {
                return RuntimeCommandResult(
                    prepared.exitCode,
                    "TUN interface cleanup failed before start.\n${prepared.output}".trim(),
                )
            }
        }
        val process = try {
            ProcessBuilder(singBox().absolutePath, "run", "-c", generatedConfigFile.absolutePath)
                .directory(binDir)
                .redirectOutput(outLog)
                .redirectError(errLog)
                .start()
        } catch (_: Exception) {
            return RuntimeCommandResult(1, "sing-box could not be started.", RecoveryFailureStage.CORE_STARTUP)
        }
        pidFile.writeText(process.pid().toString(), StandardCharsets.US_ASCII)
        processStateFile.writeText(
            gson.toJson(
                mapOf(
                    "pid" to process.pid(),
                    "out_log" to outLog.absolutePath,
                    "err_log" to errLog.absolutePath,
                    "started_at" to Instant.now().toString(),
                    "mode" to runtimeMode,
                ),
            ),
            StandardCharsets.UTF_8,
        )
        Thread.sleep(1800)
        if (!process.isAlive) {
            pidFile.delete()
            processStateFile.delete()
            return RuntimeCommandResult(
                1,
                buildString {
                    appendLine("sing-box exited early")
                    if (errLog.isFile) append(errLog.readText(StandardCharsets.UTF_8).takeLast(6000))
                }.trim(),
                RecoveryFailureStage.CORE_STARTUP,
            )
        }
        return RuntimeCommandResult(0, "Started $runtimeMode runtime. PID ${process.pid()}")
    }

    internal fun standaloneProxyDiagnosticSnapshot(sessionId: String): com.yokhan.gates.windows.service.TunnelDiagnosticSnapshot? = runCatching {
        if (generatedRuntimeModeSummary() != "proxy" || readProcessState()["mode"] != "proxy") return null
        val processId = managedProcessId() ?: return null
        val process = ProcessHandle.of(processId).orElse(null)
        if (process?.isAlive == true) {
            val expectedExecutable = singBox().canonicalPath
            val actualExecutable = process.info().command().orElse("")
            if (!actualExecutable.equals(expectedExecutable, ignoreCase = true)) return null
        }
        val validated = com.yokhan.gates.windows.service.SingBoxConfigValidator.validate(generatedConfigFile.readBytes())
            as? com.yokhan.gates.windows.service.ConfigValidationResult.Accepted ?: return null
        com.yokhan.gates.windows.service.TunnelDiagnosticSnapshot(sessionId, process?.isAlive == true, validated.config)
    }.getOrNull()

    private fun startRoomNetForConnect(): RuntimeCommandResult =
        runCatching { startRoomNet() }.getOrElse { error ->
            RuntimeCommandResult(0, "RoomNet not started: ${error.message ?: error.toString()}")
        }

    private fun prepareTunInterfaceForStart(config: WindowsAppConfig): RuntimeCommandResult {
        val addresses = windowsTunAddressIps(config.tunAddress)
        if (addresses.isEmpty()) return RuntimeCommandResult(0, "")
        val script = buildWindowsTunAddressCleanupScript(config.tunInterfaceName, addresses)
        return powershell(script, timeoutSeconds = 10)
    }

    @Synchronized
    private fun stop(): RuntimeCommandResult {
        val config = readConfig()
        val roomNetStop = stopRoomNet()
        val service = if (allowRuntimeServiceDelegation) {
            runCatching { stopThroughRuntimeService() }.getOrElse { RuntimeCommandResult(1, "Service stop was not confirmed") }
        } else RuntimeCommandResult(0, "")
        val local = runCatching { stopLocalRuntime?.invoke(config) ?: stopManagedLocalRuntime(config, roomNetStop) }
            .getOrElse { RuntimeCommandResult(1, "Local runtime stop was not confirmed") }
        return RuntimeCommandResult(
            if (service.exitCode != 0) service.exitCode else local.exitCode,
            listOf(service.output, local.output).filter(String::isNotBlank).joinToString("\n"),
        )
    }

    private fun stopManagedLocalRuntime(config: WindowsAppConfig, roomNetStop: RuntimeCommandResult): RuntimeCommandResult {
        val processId = managedProcessId()
        val process = managedProcess()
        if (!isManagedProcessRunning(processId, process)) {
            clearRuntimeProcessState()
            val proxyRestore = if (config.restoreSystemProxyOnStop) disableSystemProxy() else RuntimeCommandResult(0, "")
            return RuntimeCommandResult(
                if (proxyRestore.exitCode != 0) proxyRestore.exitCode else 0,
                buildString {
                    appendLine("Runtime is not running")
                    appendLine(roomNetStop.output)
                    if (proxyRestore.output.isNotBlank()) append(proxyRestore.output)
                }.trim(),
            )
        }
        val runningMode = runningRuntimeModeSummary()
        val stopError = runCatching {
            if (process?.isAlive == true) {
                process.destroy()
                runCatching { process.onExit().get(5, TimeUnit.SECONDS) }
                if (process.isAlive) {
                    process.destroyForcibly()
                    runCatching { process.onExit().get(5, TimeUnit.SECONDS) }
                }
            } else if (processId != null) {
                stopWindowsProcess(processId)
            }
        }.exceptionOrNull()
        if (isManagedProcessRunning(processId, process)) {
            if (runningMode == "tun" && !isRunningAsAdministrator()) {
                val elevated = requestElevatedCli("stop", expectedRunning = false)
                return RuntimeCommandResult(
                    elevated.exitCode,
                    buildString {
                        appendLine("Stop needs Windows administrator rights for TUN runtime.")
                        appendLine(roomNetStop.output)
                        append(elevated.output)
                    }.trim(),
                )
            }
            return RuntimeCommandResult(
                1,
                buildString {
                    appendLine("Failed to stop runtime. PID ${processId ?: "unknown"}")
                    if (stopError != null) appendLine(stopError.message.orEmpty())
                }.trim(),
            )
        }
        clearRuntimeProcessState()
        val proxyRestore = if (config.restoreSystemProxyOnStop) disableSystemProxy() else RuntimeCommandResult(0, "")
        return RuntimeCommandResult(
            if (proxyRestore.exitCode != 0) proxyRestore.exitCode else 0,
            buildString {
                appendLine("Stopped runtime. PID ${processId ?: "unknown"}")
                appendLine(roomNetStop.output)
                if (proxyRestore.output.isNotBlank()) append(proxyRestore.output)
            }.trim(),
        )
    }

    private fun status(): RuntimeCommandResult {
        val config = readConfig()
        val processId = managedProcessId()
        val process = managedProcess()
        val serviceState = runtimeService.health()
        val protection = parseWindowsProtectionState(runtimeService.protectionStatus())
        val isServiceTunnelRunning = serviceState.isRunningServiceTunnel() &&
            (!config.killSwitchEnabled || protection == WindowsProtectionState.ARMED)
        val isRunning = isManagedProcessRunning(processId, process) || isServiceTunnelRunning
        val exit = if (isRunning) {
            curlBody(config, "https://api.ipify.org", timeoutSeconds = 15).let { result ->
                if (result.exitCode == 0) result.output.trim() else "unknown: ${result.output}"
            }
        } else {
            "not running"
        }
        return RuntimeCommandResult(
            0,
            buildString {
                appendLine(
                    "Process: ${when {
                        isServiceTunnelRunning -> "running in GATES Runtime service"
                        isRunning -> "running PID $processId"
                        else -> "stopped"
                    }}",
                )
                appendLine(
                    "Mode: requested=${config.runtimeMode}; generated=${generatedRuntimeModeSummary()}; " +
                        "running=${if (isServiceTunnelRunning) "tun-service" else if (isRunning) runningRuntimeModeSummary() else "none"}",
                )
                appendLine("TUN privileges: ${tunPrivilegeSummary(config)}")
                appendLine("Runtime helper: ${runtimeHelperSummary()}")
                appendLine("Protection: ${protection.name}")
                appendLine("SOCKS: ${config.listenHost}:${config.socksPort}")
                appendLine("HTTP: ${config.listenHost}:${config.httpPort}")
                appendLine("System proxy: ${systemProxySummary()}")
                appendLine("Active route: ${config.activeRoute}")
                appendLine("Auth: ${authSummary()}")
                appendLine("RoomNet: ${roomNetSummary(config)}")
                appendLine("Physical bind: ${physicalBindSummary(config)}")
                appendLine("Exit IP via local proxy: $exit")
                appendLine("Connectivity history: ${windowsConnectivityHistory().get("availability").asString}")
                appendLine("AppData: ${appDir.absolutePath}")
            }.trim(),
        )
    }

    /**
     * Queries local runtime health, protection state, resolved route, and process uptime
     * without performing network probes or spawning PowerShell subprocesses.
     */
    internal fun queryLiveRuntimeStatus(): WindowsLiveRuntimeSnapshot {
        val config = readConfig()
        val processId = managedProcessId()
        val process = processId?.let { ProcessHandle.of(it).orElse(null) }
        val localRunning = process?.isAlive == true
        val serviceHealth = if (allowRuntimeServiceDelegation) runtimeService.health() else RuntimeCommandResult(1, "")
        val protection = if (allowRuntimeServiceDelegation) {
            parseWindowsProtectionState(runtimeService.protectionStatus())
        } else {
            WindowsProtectionState.UNAVAILABLE
        }
        val serviceRunning = serviceHealth.isRunningServiceTunnel() &&
            (!config.killSwitchEnabled || protection == WindowsProtectionState.ARMED)
        val connected = serviceRunning || localRunning
        val resolvedRoute = if (config.activeRoute == "auto") {
            val detected = reportTelemetryRoute(config)
            if (detected.isNotBlank() && detected != "auto") detected else "auto"
        } else {
            config.activeRoute
        }
        val startedAt = when {
            localRunning -> process?.info()?.startInstant()?.orElse(null)?.toEpochMilli()
            serviceRunning -> generatedConfigFile.takeIf { it.isFile }?.lastModified()?.takeIf { it > 0L }
            else -> null
        }
        val effectiveMode = when {
            serviceRunning -> "tun-service"
            localRunning -> runningRuntimeModeSummary()
            else -> config.runtimeMode
        }
        return WindowsLiveRuntimeSnapshot(
            connected = connected,
            resolvedRoute = resolvedRoute,
            runtimeMode = effectiveMode,
            protectionState = protection,
            tunnelPid = if (localRunning) processId else null,
            startedAtEpochMillis = startedAt,
        )
    }

    private fun generatedRuntimeModeSummary(): String =
        if (generatedModeFile.isFile) {
            generatedModeFile.readText(StandardCharsets.US_ASCII).trim().ifBlank { "unknown" }
        } else {
            "none"
        }

    private fun runningRuntimeModeSummary(): String =
        readProcessState()["mode"]?.ifBlank { null } ?: "unknown"

    private fun shouldRequestTunElevation(config: WindowsAppConfig, generatedMode: String): Boolean =
        config.runtimeMode == "tun" && generatedMode == "tun" && !isRunningAsAdministrator()

    private fun tunPrivilegeSummary(config: WindowsAppConfig): String {
        val tunSelected = config.runtimeMode == "tun" || generatedRuntimeModeSummary() == "tun"
        if (!tunSelected) return "not required"
        return if (isRunningAsAdministrator()) "available" else "required"
    }

    private fun runtimeHelperSummary(): String =
        if (runtimeService.health().exitCode == 0) "running" else "not running"

    private fun startThroughRuntimeService(): RuntimeCommandResult {
        val prepared = prepareRuntimeService()
        if (prepared.exitCode != 0) return prepared
        if (!generatedConfigFile.isFile) return RuntimeCommandResult(1, "Missing generated TUN config.")
        val config = readConfig()
        if (config.killSwitchEnabled && runtimeService.protectionStatus().exitCode != 0) {
            return RuntimeCommandResult(1, "GATES_PROTECTION_SERVICE_UNAVAILABLE")
        }
        val serviceConfig = windowsServiceTransportConfig(
            generatedConfigFile.readText(StandardCharsets.UTF_8),
            xhttpSpecFile.takeIf { it.isFile }?.readText(StandardCharsets.UTF_8),
            config,
        )
        val result = runtimeService.startTunnel(serviceConfig)
        if (config.killSwitchEnabled && result.exitCode == 0 &&
            parseWindowsProtectionState(runtimeService.protectionStatus()) != WindowsProtectionState.ARMED) {
            return RuntimeCommandResult(1, "GATES_PROTECTION_NOT_ARMED")
        }
        return mergeRuntimeServiceResults(prepared, requireRuntimeStartAcknowledgement(result))
    }

    private fun setKillSwitch(value: String?): RuntimeCommandResult {
        val enabled = when (value) {
            "true" -> true
            "false" -> false
            else -> return RuntimeCommandResult(1, "GATES_PROTECTION_INVALID_SETTING")
        }
        if (isManagedProcessRunning(managedProcessId(), managedProcess())) {
            return RuntimeCommandResult(1, "GATES_PROTECTION_STOP_FIRST")
        }
        val currentConfig = readConfig()
        val updated = currentConfig.copy(killSwitchEnabled = enabled)
        if (enabled) {
            runCatching { windowsProtectionConfig("{}", updated) }.getOrElse {
                return RuntimeCommandResult(1, it.message ?: "GATES_PROTECTION_REQUIRES_EXE_PATHS")
            }
        }
        var health = if (allowRuntimeServiceDelegation) runtimeService.health() else RuntimeCommandResult(1, "")
        val brokerConfirmedAbsent = allowRuntimeServiceDelegation && health.exitCode != 0 &&
            runtimeServiceInstalled() == false
        if (allowRuntimeServiceDelegation && !brokerConfirmedAbsent && health.exitCode != 0) {
            val prepared = prepareRuntimeService()
            if (prepared.exitCode != 0) return prepared
            health = runtimeService.health()
        }
        val digest = if (health.exitCode == 0) runtimeService.configurationDigest() else null
        val serviceHasIntent = digest != null && digest.exitCode == 0 && digest.output.trim().matches(Regex("[0-9a-f]{64}"))
        val serviceRunning = health.isRunningServiceTunnel() || serviceHasIntent

        if (!serviceRunning) {
            return applyRoutingChange(
                updated,
                if (enabled) "Защита при обрыве включена: выбранные EXE заблокированы до подключения."
                else "Защита при обрыве выключена.",
            )
        }

        if (!rawConfigFile.isFile || !generatedConfigFile.isFile) {
            return RuntimeCommandResult(1, "GATES_PROTECTION_STOP_FIRST")
        }
        val stopped = stopThroughRuntimeService()
        if (stopped.exitCode != 0) {
            return RuntimeCommandResult(1, "GATES_PROTECTION_STOP_FIRST: ${stopped.output}".trim())
        }
        val syncResult = if (enabled) {
            runtimeService.armProtection(windowsProtectionPayloadJson(updated))
        } else {
            runtimeService.disarmProtection()
        }
        if (syncResult.exitCode != 0) {
            if (currentConfig.killSwitchEnabled) {
                runCatching { runtimeService.armProtection(windowsProtectionPayloadJson(currentConfig)) }
            }
            startThroughRuntimeService()
            val code = when {
                "UNSUPPORTED_COMMAND" in syncResult.output -> "GATES_PROTECTION_SERVICE_UNAVAILABLE"
                enabled -> "GATES_PROTECTION_NOT_ARMED"
                else -> "GATES_PROTECTION_NOT_DISARMED"
            }
            return RuntimeCommandResult(1, "$code: ${syncResult.output}".trim())
        }
        writeConfig(updated)
        val generated = generateConfig()
        val restarted = if (generated.exitCode == 0) startThroughRuntimeService() else generated
        if (restarted.exitCode != 0) {
            writeConfig(currentConfig)
            generateConfig()
            if (currentConfig.killSwitchEnabled) {
                runCatching { runtimeService.armProtection(windowsProtectionPayloadJson(currentConfig)) }
            } else {
                runCatching { runtimeService.disarmProtection() }
            }
            return RuntimeCommandResult(restarted.exitCode, restarted.output)
        }
        return RuntimeCommandResult(
            0,
            if (enabled) "Защита при обрыве включена и применена." else "Защита при обрыве выключена.",
        )
    }

    private fun stopThroughRuntimeService(): RuntimeCommandResult {
        val result = runtimeService.stop().let {
            requireRuntimeStopAcknowledgement(it)
        }
        return mergeRuntimeServiceResults(RuntimeCommandResult(0, ""), result)
    }

    private fun mergeRuntimeServiceResults(
        prepared: RuntimeCommandResult,
        result: RuntimeCommandResult,
    ): RuntimeCommandResult {
        return RuntimeCommandResult(
            result.exitCode,
            buildString {
                appendLine(prepared.output)
                append(result.output)
            }.trim(),
        )
    }

    private fun test(): RuntimeCommandResult {
        val config = readConfig()
        val processId = managedProcessId()
        val isServiceTunnelRunning = runtimeService.health().isRunningServiceTunnel()
        if (!isManagedProcessRunning(processId, managedProcess()) && !isServiceTunnelRunning) {
            return RuntimeCommandResult(1, "Runtime is not running. Start proxy first.")
        }
        var failed = 0
        val qualitySamples = mutableListOf<WindowsCurlQualitySample>()
        val output = buildString {
            for (url in config.testUrls) {
                val result = curl(config, url, timeoutSeconds = 25)
                parseWindowsCurlQualitySample(url, result)?.let(qualitySamples::add)
                if (result.exitCode == 0) {
                    appendLine("OK   $url :: ${result.output}")
                } else {
                    failed += 1
                    appendLine("FAIL $url :: ${result.output}")
                }
            }
        }.trim()
        val routeId = reportTelemetryRoute(config)
        if (routeId.isNotBlank() && qualitySamples.isNotEmpty()) {
            val payload = buildWindowsQualityTelemetryPayload(routeId, qualitySamples)
            val bearer = runCatching { freshAccessToken(config) }.getOrNull()
            sendRouteTelemetry(config, payload, bearer)
        }
        return RuntimeCommandResult(if (failed == 0) 0 else 1, output)
    }

    @Synchronized
    private fun setRoute(route: String): RuntimeCommandResult {
        val normalized = route.lowercase()
        if (normalized != "auto" && !isWindowsProxyRouteId(normalized)) {
            return RuntimeCommandResult(1, "Unsupported route: $route")
        }
        val config = readConfig()
        if (normalized != "auto" && availableRoutes().none { it.tag == normalized }) {
            return RuntimeCommandResult(1, "Selected route is not available in the imported profile")
        }
        return applyRoutingChange(config.copy(activeRoute = normalized), "Active route set to $normalized")
    }

    @Synchronized
    private fun setRuntimeMode(mode: String): RuntimeCommandResult {
        val normalized = mode.lowercase()
        if (normalized !in listOf("tun", "system-proxy", "proxy")) {
            return RuntimeCommandResult(1, "Unsupported runtime mode: $mode")
        }
        val config = readConfig()
        if (config.killSwitchEnabled && normalized != "tun") {
            return RuntimeCommandResult(1, "GATES_PROTECTION_REQUIRES_TUN")
        }
        return applyRoutingChange(config.copy(runtimeMode = normalized), "Runtime mode set to $normalized")
    }

    @Synchronized
    private fun setTunRouting(mode: String): RuntimeCommandResult {
        val normalized = mode.lowercase()
        if (normalized !in listOf("all", "selected")) {
            return RuntimeCommandResult(1, "Unsupported TUN routing mode: $mode")
        }
        val config = readConfig()
        return applyRoutingChange(config.copy(tunRouteAll = normalized == "all"), "TUN routing set to $normalized")
    }

    @Synchronized
    fun addAppRouting(target: String, proxy: Boolean): RuntimeCommandResult {
        val app = parseWindowsAppRoutingTarget(target)
            ?: return RuntimeCommandResult(1, "Expected .exe path or process name.")
        val config = readConfig()
        val updated = updateWindowsAppRouting(config, app, proxy, remove = false)
        val mode = if (proxy) "GATES" else "direct"
        return applyRoutingChange(updated, "App route set to $mode: ${app.label}")
    }

    @Synchronized
    fun addAppsRouting(targets: List<String>, proxy: Boolean): RuntimeCommandResult {
        val apps = targets.mapNotNull(::parseWindowsAppRoutingTarget)
        if (apps.isEmpty()) {
            return RuntimeCommandResult(1, "Expected .exe path or process name.")
        }
        var updated = readConfig()
        apps.forEach { app ->
            updated = updateWindowsAppRouting(updated, app, proxy, remove = false)
        }
        val mode = if (proxy) "GATES" else "direct"
        return applyRoutingChange(updated, "Apps (${apps.size}) set to $mode")
    }

    @Synchronized
    fun moveAppRouting(target: String, toProxy: Boolean): RuntimeCommandResult =
        addAppRouting(target, proxy = toProxy)

    @Synchronized
    fun removeAppRouting(target: String, proxy: Boolean): RuntimeCommandResult {
        val app = parseWindowsAppRoutingTarget(target)
            ?: return RuntimeCommandResult(1, "Expected .exe path or process name.")
        val config = readConfig()
        val updated = updateWindowsAppRouting(config, app, proxy, remove = true)
        return applyRoutingChange(updated, "App route removed: ${app.label}")
    }

    @Synchronized
    fun setRuBypass(enabled: Boolean): RuntimeCommandResult =
        applyRoutingChange(
            readConfig().withRuBypassChanged(enabled),
            if (enabled) "Умный автообход РФ включён" else "Умный автообход РФ выключен",
        )

    @Synchronized
    fun setWholeTraffic(enabled: Boolean): RuntimeCommandResult =
        applyRoutingChange(
            readConfig().withWholeTrafficChanged(enabled),
            if (enabled) "Весь трафик направлен через GATES" else "Режим всего трафика выключен",
        )

    @Synchronized
    fun setBypassLan(enabled: Boolean): RuntimeCommandResult =
        applyRoutingChange(
            readConfig().copy(bypassLan = enabled),
            if (enabled) "Обход локальной сети включён" else "Обход локальной сети выключен",
        )

    @Synchronized
    fun setBlockQuic(enabled: Boolean): RuntimeCommandResult =
        applyRoutingChange(
            readConfig().copy(blockQuic = enabled),
            if (enabled) "Блокировка QUIC (UDP 443) включена" else "Блокировка QUIC выключена",
        )

    @Synchronized
    fun setRouteOrder(order: List<String>): RuntimeCommandResult {
        val valid = order.map { it.trim().lowercase() }
            .filter { isWindowsProxyRouteId(it) }
            .distinct()
        if (valid.isEmpty()) {
            return RuntimeCommandResult(1, "Список приоритета маршрутов пуст.")
        }
        val current = readConfig()
        val normalized = (valid + current.routeOrder.filter { it !in valid }).distinct()
        return applyRoutingChange(
            current.copy(routeOrder = normalized),
            "Приоритет маршрутов обновлён: ${normalized.joinToString(" -> ")}",
        )
    }

    @Synchronized
    fun updateNetworkSettings(
        listenHost: String,
        socksPort: Int,
        httpPort: Int,
        systemProxyEnabled: Boolean,
        restoreSystemProxyOnStop: Boolean,
        tunStack: String,
        tunMtu: Int,
        tunStrictRoute: Boolean,
        physicalBindEnabled: Boolean,
        physicalBindInterface: String,
    ): RuntimeCommandResult {
        val current = readConfig()
        val host = listenHost.trim().ifBlank { "127.0.0.1" }
        if (socksPort !in 1024..65535 || httpPort !in 1024..65535) {
            return RuntimeCommandResult(1, "Порты должны быть в диапазоне 1024..65535.")
        }
        if (socksPort == httpPort || socksPort == current.xhttpSidecarPort || httpPort == current.xhttpSidecarPort) {
            return RuntimeCommandResult(1, "Порты SOCKS, HTTP и XHTTP не должны совпадать.")
        }
        if (tunStack !in listOf("mixed", "system", "gvisor")) {
            return RuntimeCommandResult(1, "Неподдерживаемый стек TUN: $tunStack")
        }
        if (tunMtu !in 1280..9000) {
            return RuntimeCommandResult(1, "MTU должен быть в диапазоне 1280..9000.")
        }
        val updated = current.copy(
            listenHost = host,
            socksPort = socksPort,
            httpPort = httpPort,
            systemProxyEnabled = systemProxyEnabled,
            restoreSystemProxyOnStop = restoreSystemProxyOnStop,
            tunStack = tunStack,
            tunMtu = tunMtu,
            tunStrictRoute = tunStrictRoute,
            physicalBindEnabled = physicalBindEnabled,
            physicalBindInterface = physicalBindInterface.trim(),
        )
        return applyRoutingChange(updated, "Сетевые настройки применены.")
    }

    @Synchronized
    fun reconnectRuntime(): RuntimeCommandResult {
        ensureDirs()
        val initial = readConfig()
        var importNote = ""
        if (readAuthState(initial) != null && initial.profileSource == "account") {
            val imported = runCatching { importAuthenticatedAppConfig() }.getOrElse {
                RuntimeCommandResult(1, "Не удалось обновить профиль с сервера.")
            }
            if (imported.exitCode != 0 && !rawConfigFile.isFile) {
                return imported
            }
            importNote = if (imported.exitCode == 0) "Профиль обновлён с сервера." else "Использован локальный профиль."
        }
        val generated = generateConfig()
        if (generated.exitCode != 0) {
            return generated
        }
        val serviceHealth = if (allowRuntimeServiceDelegation) runtimeService.health() else RuntimeCommandResult(1, "")
        val digest = if (serviceHealth.exitCode == 0) runtimeService.configurationDigest() else RuntimeCommandResult(1, "")
        val serviceHasIntent = digest.exitCode == 0 && digest.output.trim().matches(Regex("[0-9a-f]{64}"))
        return if (allowRuntimeServiceDelegation && (serviceHealth.isRunningServiceTunnel() || serviceHasIntent)) {
            val applied = startThroughRuntimeService()
            val text = listOf(importNote, generated.output, applied.output).filter { it.isNotBlank() }.joinToString("\n")
            RuntimeCommandResult(applied.exitCode, text)
        } else {
            connect()
        }
    }

    private fun parseBooleanArg(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
        "true", "1", "on", "yes" -> true
        "false", "0", "off", "no" -> false
        else -> null
    }

    /** Resolves a dropped/picked `.lnk` to its target path before routing. */
    internal fun resolveAppTarget(path: String): String {
        if (!path.endsWith(".lnk", ignoreCase = true)) return path
        return resolveWindowsShortcutTarget(path, ::runProcess) ?: path
    }

    private fun applyRoutingChange(updated: WindowsAppConfig, message: String): RuntimeCommandResult {
        val previousConfig = readConfig()
        if (updated.killSwitchEnabled) {
            runCatching { windowsProtectionConfig("{}", updated) }.getOrElse {
                return RuntimeCommandResult(1, it.message ?: "GATES_PROTECTION_CONFIG_REJECTED")
            }
        }
        val serviceState = if (allowRuntimeServiceDelegation) runtimeService.health() else RuntimeCommandResult(1, "")
        val brokerConfirmedAbsent = allowRuntimeServiceDelegation && serviceState.exitCode != 0 &&
            runtimeServiceInstalled() == false
        if (allowRuntimeServiceDelegation && !brokerConfirmedAbsent &&
            (serviceState.exitCode != 0 || serviceState.output.trim() !in setOf("RUNNING", "STOPPED"))) {
            return RuntimeCommandResult(1, "Runtime state could not be verified; routing settings were not changed")
        }
        val digest = if (serviceState.exitCode == 0) runtimeService.configurationDigest() else RuntimeCommandResult(1, "")
        val serviceHasIntent = digest.exitCode == 0 && digest.output.trim().matches(Regex("[0-9a-f]{64}"))
        if (serviceState.exitCode == 0 && (digest.exitCode != 0 || (!serviceHasIntent && digest.output.trim() != "NONE"))) {
            return RuntimeCommandResult(1, "Runtime configuration could not be verified; routing settings were not changed")
        }
        val serviceNeedsApply = serviceState.isRunningServiceTunnel() || serviceHasIntent
        if (serviceNeedsApply && (!rawConfigFile.isFile || !generatedConfigFile.isFile)) {
            return RuntimeCommandResult(1, "Cannot apply app routing without the imported and previous runtime configurations")
        }
        if (serviceNeedsApply) {
            val localDigest = runCatching { runtimeConfigurationDigest(generatedConfigFile.readText(StandardCharsets.UTF_8)) }.getOrNull()
            if (digest.exitCode != 0 || localDigest == null || digest.output.trim() != localDigest) {
                return RuntimeCommandResult(1, "Runtime configuration differs from the local snapshot; synchronize it before editing app routing")
            }
        }
        if (!serviceNeedsApply && isManagedProcessRunning(managedProcessId(), managedProcess())) {
            return RuntimeCommandResult(1, "Stop the legacy runtime before editing app routing")
        }
        val files = listOf(appConfigFile, generatedConfigFile, generatedModeFile, xhttpSpecFile)
        val previous = files.associateWith { if (it.isFile) it.readBytes() else null }
        var applyAttempted = false
        val result = runCatching {
            writeConfig(updated)
            val generated = if (rawConfigFile.isFile) generateConfig() else RuntimeCommandResult(0, "Config will be generated after import")
            if (generated.exitCode != 0) generated
            else if (serviceNeedsApply) {
                applyAttempted = true
                val applied = startThroughRuntimeService()
                RuntimeCommandResult(applied.exitCode, listOf(message, generated.output, applied.output).joinToString("\n"))
            } else {
                if (allowRuntimeServiceDelegation && !brokerConfirmedAbsent && updated.killSwitchEnabled) {
                    val armResult = runtimeService.armProtection(windowsProtectionPayloadJson(updated))
                    val armed = armResult.exitCode == 0 &&
                        parseWindowsProtectionState(runtimeService.protectionStatus()) in setOf(
                            WindowsProtectionState.BLOCKED,
                            WindowsProtectionState.ARMED,
                        )
                    if (!armed) {
                        val code = if ("UNSUPPORTED_COMMAND" in armResult.output) {
                            "GATES_PROTECTION_SERVICE_UNAVAILABLE"
                        } else {
                            "GATES_PROTECTION_NOT_ARMED"
                        }
                        RuntimeCommandResult(1, "$code: ${armResult.output}".trim())
                    } else {
                        RuntimeCommandResult(0, "$message\nSaved and WFP updated\n${generated.output}")
                    }
                } else if (allowRuntimeServiceDelegation && !brokerConfirmedAbsent && previousConfig.killSwitchEnabled && !updated.killSwitchEnabled) {
                    val disarmResult = runtimeService.disarmProtection()
                    val disarmed = disarmResult.exitCode == 0 &&
                        parseWindowsProtectionState(runtimeService.protectionStatus()) == WindowsProtectionState.DISABLED
                    if (!disarmed) {
                        val code = if ("UNSUPPORTED_COMMAND" in disarmResult.output) {
                            "GATES_PROTECTION_SERVICE_UNAVAILABLE"
                        } else {
                            "GATES_PROTECTION_NOT_DISARMED"
                        }
                        RuntimeCommandResult(1, "$code: ${disarmResult.output}".trim())
                    } else {
                        RuntimeCommandResult(0, "$message\nWFP disarmed\n${generated.output}")
                    }
                } else {
                    RuntimeCommandResult(0, "$message\nSaved for the next connection\n${generated.output}")
                }
            }
        }.getOrElse { RuntimeCommandResult(1, "App routing update failed") }
        if (result.exitCode != 0) {
            val filesRestored = runCatching {
                previous.forEach { (file, bytes) ->
                    if (bytes != null) file.writeBytes(bytes) else check(!file.exists() || file.delete())
                }
            }.isSuccess
            // An IPC timeout can hide a successful apply; explicitly restore the old service config.
            val runtimeRestore = if (applyAttempted) {
                val oldJson = previous[generatedConfigFile]?.toString(StandardCharsets.UTF_8)
                val oldXhttpSpec = previous[xhttpSpecFile]?.toString(StandardCharsets.UTF_8)
                if (oldJson == null) RuntimeCommandResult(1, "Previous runtime config is unavailable")
                else runCatching {
                    requireRuntimeStartAcknowledgement(
                        runtimeService.startTunnel(windowsServiceTransportConfig(oldJson, oldXhttpSpec, previousConfig)),
                    )
                }
                    .getOrElse { RuntimeCommandResult(1, "Runtime rollback failed") }
            } else RuntimeCommandResult(0, "")
            val restored = filesRestored && runtimeRestore.exitCode == 0
            return RuntimeCommandResult(
                result.exitCode,
                "App routing was not applied; ${if (restored) "previous settings restored" else "rollback incomplete; check runtime state"}\n${result.output}",
            )
        }
        return result
    }

    private fun appRoutingSummary(): RuntimeCommandResult {
        val state = appRoutingState()
        return RuntimeCommandResult(
            0,
            buildString {
                appendLine("Mode: ${if (state.routeAll) "all" else "selected"}")
                appendLine("Through GATES:")
                state.proxyProcessNames.forEach { appendLine("- name: $it") }
                state.proxyProcessPaths.forEach { appendLine("- path: $it") }
                appendLine("Direct:")
                state.directProcessNames.forEach { appendLine("- name: $it") }
                state.directProcessPaths.forEach { appendLine("- path: $it") }
            }.trim(),
        )
    }

    private fun runningAppsSummary(): RuntimeCommandResult {
        val apps = listRunningApps()
        return RuntimeCommandResult(
            0,
            apps.joinToString("\n") { "${it.name} pid=${it.pid} path=${it.path}" },
        )
    }


    private fun probeRoutes(): RuntimeCommandResult {
        val original = readConfig()
        val serviceRunning = allowRuntimeServiceDelegation && runtimeService.health().isRunningServiceTunnel()
        if (serviceRunning || xhttpSpecFile.isFile || original.killSwitchEnabled) {
            return probeRoutesNonDisruptive(original, serviceRunning)
        }
        val wasRunning = isManagedProcessRunning(managedProcessId(), managedProcess())
        val rows = mutableListOf<String>()
        try {
            for (route in original.routeOrder) {
                writeConfig(readConfig().copy(activeRoute = route))
                val generated = generateConfig(forceProxyMode = true)
                if (generated.exitCode != 0) {
                    rows += "$route: config failed: ${generated.output}"
                    continue
                }
                stop()
                val started = startProxy()
                if (started.exitCode != 0) {
                    rows += "$route: start failed: ${started.output}"
                    continue
                }
                val targets = listOf(
                    "https://www.gstatic.com/generate_204",
                    "https://www.youtube.com/generate_204",
                    "https://api.telegram.org",
                )
                var ok = 0
                var failed = 0
                for (target in targets) {
                    val result = curl(readConfig(), target, timeoutSeconds = 20, compact = true)
                    if (result.exitCode == 0) ok += 1 else failed += 1
                    rows += "$route ${if (result.exitCode == 0) "OK" else "FAIL"} $target :: ${result.output}"
                }
                rows += "$route summary: ok=$ok failed=$failed"
            }
        } finally {
            stop()
            writeConfig(readConfig().copy(activeRoute = original.activeRoute))
            if (rawConfigFile.isFile) generateConfig()
            if (wasRunning) startProxy()
        }
        val report = rows.joinToString("\n")
        val reportFile = File(logsDir, "route-probe-${Instant.now().toString().replace(":", "-")}.txt")
        reportFile.writeText(report, StandardCharsets.UTF_8)
        return RuntimeCommandResult(
            0,
            buildString {
                appendLine("Route probe report: ${reportFile.absolutePath}")
                append(report)
            },
        )
    }

    private fun probeRoutesNonDisruptive(config: WindowsAppConfig, serviceRunning: Boolean): RuntimeCommandResult {
        ensureDirs()
        val rows = mutableListOf<String>()
        val health = fetchRouteHealthSnapshot(config)
        val routes = availableRoutes()
        val rawObj = runCatching {
            if (rawConfigFile.isFile) JsonParser.parseString(profileStore.readText(rawConfigFile, StandardCharsets.UTF_8)).asJsonObject
            else null
        }.getOrNull()
        val outboundMap = rawObj?.getAsJsonArray("outbounds")
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            ?.associateBy { it.get("tag")?.asString.orEmpty() }
            .orEmpty()
        val bindTarget = if (config.physicalBindEnabled == true) resolvePhysicalBindTarget(config) else null
        val bindAddress = bindTarget?.ipv4?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }

        rows += "mode=non-disruptive service_running=$serviceRunning active_route=${config.activeRoute}"
        if (serviceRunning) {
            val liveCheck = curl(config, config.healthcheckUrl, timeoutSeconds = 10, compact = true)
            rows += "active_tunnel_http ${if (liveCheck.exitCode == 0) "OK" else "FAIL"} ${config.healthcheckUrl} :: ${liveCheck.output}"
        }

        val orderedTags = (routes.map { it.tag } + config.routeOrder).distinct()
        for (tag in orderedTags) {
            val profileRoute = routes.firstOrNull { it.tag == tag }
            val outbound = outboundMap[tag]
            val server = profileRoute?.server?.takeIf { it.isNotBlank() }
                ?: outbound?.get("server")?.asString.orEmpty()
            val port = outbound?.get("server_port")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 443
            val serverHealth = health.routes[tag.lowercase()]?.let { "status=${it.status} score=${it.score}" }
                ?: "unknown"
            if (server.isBlank()) {
                rows += "$tag | status=UNKNOWN | tcp=- | health=$serverHealth"
                continue
            }
            val tcpMs = measureTcpHandshakeMs(server, port, bindAddress)
            val status = if (tcpMs != null) "OK" else "FAIL"
            rows += "$tag | status=$status | server=$server:$port | tcp_ms=${tcpMs ?: -1} | health=$serverHealth"
        }

        val report = rows.joinToString("\n")
        val reportFile = File(logsDir, "route-probe-${Instant.now().toString().replace(":", "-")}.txt")
        reportFile.writeText(report, StandardCharsets.UTF_8)
        return RuntimeCommandResult(
            0,
            buildString {
                appendLine("Route probe report (non-disruptive): ${reportFile.absolutePath}")
                append(report)
            },
        )
    }

    private fun measureTcpHandshakeMs(host: String, port: Int, bindAddress: InetAddress?): Long? {
        return runCatching {
            Socket().use { socket ->
                if (bindAddress != null) {
                    socket.bind(InetSocketAddress(bindAddress, 0))
                }
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(host, port), 3500)
                (System.nanoTime() - start) / 1_000_000L
            }
        }.getOrNull()
    }

    private fun adoptStateFrom(sourceDir: File): RuntimeCommandResult {
        ensureDirs()
        if (!sourceDir.isDirectory) {
            return RuntimeCommandResult(1, "Source runtime directory does not exist: ${sourceDir.absolutePath}")
        }
        val copied = mutableListOf<String>()
        fun copyIfPresent(sourceName: String, target: File, publicName: String) {
            val source = File(sourceDir, sourceName)
            if (!source.isFile) return
            source.copyTo(target, overwrite = true)
            copied += publicName
        }
        copyIfPresent("app-config.json", appConfigFile, "app-config.json")
        copyIfPresent("subscription.singbox.json", legacyConfigFile, "subscription.singbox.json")
        copyIfPresent("account.singbox.json", accountConfigFile, "account.singbox.json")
        copyIfPresent("manual.singbox.json", manualConfigFile, "manual.singbox.json")
        copyIfPresent("config.json", generatedConfigFile, "config.json")
        return if (copied.isEmpty()) {
            RuntimeCommandResult(1, "No compatible runtime state files found in ${sourceDir.absolutePath}")
        } else {
            RuntimeCommandResult(
                0,
                buildString {
                    appendLine("Adopted runtime state into ${appDir.absolutePath}")
                    appendLine("Copied: ${copied.joinToString(", ")}")
                    append("Sensitive URLs and keys were not printed.")
                },
            )
        }
    }

    private fun logs(): RuntimeCommandResult =
        RuntimeCommandResult(0, readDiagnosticLogs("all"))

    fun readDiagnosticLogs(sourceFilter: String = "all"): String {
        val state = readProcessState()
        val outLog = state["out_log"]?.let { File(it) }
        val errLog = state["err_log"]?.let { File(it) }
        val serviceLog = File(serviceDataDir, "service.log")
        val xrayLog = File(serviceDataDir, "xhttp\\xray.log")
        val serviceErrLog = File(serviceDataDir, "Logs\\Gates.Service.err.log")
        val serviceWrapperLog = File(serviceDataDir, "Logs\\Gates.Service.wrapper.log")
        val newestCliLog = File(logsDir, "cli-last.txt")
        val newestLocalSingBox = logsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") }
            ?.maxByOrNull { it.lastModified() }

        return buildString {
            if (sourceFilter in setOf("all", "service")) {
                appendLine("=== [service] ${serviceLog.absolutePath} ===")
                appendLine(readLogTailUtf8(serviceLog))
                if (serviceErrLog.isFile) {
                    appendLine("=== [service-err] ${serviceErrLog.absolutePath} ===")
                    appendLine(readLogTailUtf8(serviceErrLog, maxBytes = 8_192))
                }
                if (serviceWrapperLog.isFile) {
                    appendLine("=== [service-wrapper] ${serviceWrapperLog.absolutePath} ===")
                    appendLine(readLogTailUtf8(serviceWrapperLog, maxBytes = 8_192))
                }
            }
            if (sourceFilter in setOf("all", "xray")) {
                appendLine("=== [xray] ${xrayLog.absolutePath} ===")
                appendLine(readLogTailUtf8(xrayLog))
            }
            if (sourceFilter in setOf("all", "local")) {
                if (outLog != null) {
                    appendLine("=== [local-stdout] ${outLog.absolutePath} ===")
                    appendLine(readLogTailUtf8(outLog, maxBytes = 12_288))
                }
                if (errLog != null) {
                    appendLine("=== [local-stderr] ${errLog.absolutePath} ===")
                    appendLine(readLogTailUtf8(errLog, maxBytes = 12_288))
                }
                if (newestLocalSingBox != null && newestLocalSingBox.absolutePath != outLog?.absolutePath) {
                    appendLine("=== [local-singbox] ${newestLocalSingBox.absolutePath} ===")
                    appendLine(readLogTailUtf8(newestLocalSingBox, maxBytes = 12_288))
                }
                if (newestCliLog.isFile) {
                    appendLine("=== [cli-last] ${newestCliLog.absolutePath} ===")
                    appendLine(readLogTailUtf8(newestCliLog, maxBytes = 8_192))
                }
            }
        }.trim()
    }

    internal fun readLogTailUtf8(file: File, maxBytes: Int = 24_576): String {
        if (!file.isFile) return "(файл отсутствует)"
        return runCatching {
            val length = file.length()
            if (length == 0L) return@runCatching "(пусто)"
            val toRead = length.coerceAtMost(maxBytes.toLong()).toInt()
            val bytes = ByteArray(toRead)
            RandomAccessFile(file, "r").use { raf ->
                val startOffset = (length - toRead).coerceAtLeast(0L)
                raf.seek(startOffset)
                raf.readFully(bytes)
            }
            val raw = String(bytes, StandardCharsets.UTF_8)
            val cleaned = if (length > toRead) {
                val firstNewline = raw.indexOf('\n')
                if (firstNewline >= 0 && firstNewline + 1 < raw.length) raw.substring(firstNewline + 1) else raw
            } else {
                raw
            }
            redactSecrets(cleaned).trim()
        }.getOrElse { error -> "(ошибка чтения: ${error.message})" }
    }

    private fun roomNetInfo(): RuntimeCommandResult {
        val config = readConfig()
        if (!config.roomNetEnabled) {
            return RuntimeCommandResult(0, "RoomNet disabled in app config.")
        }
        val token = freshAccessToken(config)
            ?: return RuntimeCommandResult(1, "Sign in first.")
        val info = fetchRoomNetInfo(config, token)
        return RuntimeCommandResult(
            0,
            describeRoomNetInfo(info),
        )
    }

    private fun startRoomNet(): RuntimeCommandResult {
        ensureDirs()
        val config = readConfig()
        if (!config.roomNetEnabled) {
            return RuntimeCommandResult(0, "RoomNet disabled in app config.")
        }
        val token = freshAccessToken(config)
            ?: return RuntimeCommandResult(0, "RoomNet not started: sign in first.")
        val info = fetchRoomNetInfo(config, token)
        if (!info.enabled || info.self == null) {
            return RuntimeCommandResult(0, "RoomNet not enabled for this room.")
        }
        if (info.peers.isEmpty()) {
            return RuntimeCommandResult(0, "RoomNet enabled, but there are no other devices in the room.")
        }
        val existing = managedRoomNetProcess()
        if (existing != null && existing.isAlive) {
            return RuntimeCommandResult(0, "RoomNet already running. PID ${existing.pid()}\n${describeRoomNetInfo(info)}")
        }
        val launcher = selfLauncher()
            ?: return RuntimeCommandResult(1, "RoomNet cannot find the GATES launcher path.")
        val stamp = Instant.now().toString().replace(":", "-")
        val outLog = File(logsDir, "roomnet-$stamp.out.log")
        val errLog = File(logsDir, "roomnet-$stamp.err.log")
        val process = ProcessBuilder(launcher, "--cli", "roomnet-worker")
            .directory(appDir)
            .redirectOutput(outLog)
            .redirectError(errLog)
            .start()
        roomNetPidFile.writeText(process.pid().toString(), StandardCharsets.US_ASCII)
        roomNetStateFile.writeText(
            gson.toJson(
                mapOf(
                    "pid" to process.pid(),
                    "out_log" to outLog.absolutePath,
                    "err_log" to errLog.absolutePath,
                    "started_at" to Instant.now().toString(),
                    "room" to info.room,
                    "self" to (info.self.address),
                ),
            ),
            StandardCharsets.UTF_8,
        )
        Thread.sleep(1200)
        if (!process.isAlive) {
            return RuntimeCommandResult(
                1,
                buildString {
                    appendLine("RoomNet exited early")
                    if (errLog.isFile) appendLine(errLog.readText(StandardCharsets.UTF_8).takeLast(6000))
                    if (outLog.isFile) append(outLog.readText(StandardCharsets.UTF_8).takeLast(6000))
                }.trim(),
            )
        }
        return RuntimeCommandResult(
            0,
            "Started RoomNet. PID ${process.pid()}\n${describeRoomNetInfo(info)}",
        )
    }

    private fun stopRoomNet(): RuntimeCommandResult {
        val process = managedRoomNetProcess()
        if (process == null || !process.isAlive) {
            roomNetPidFile.delete()
            return RuntimeCommandResult(0, "RoomNet is not running")
        }
        process.destroy()
        runCatching { process.onExit().get(5, TimeUnit.SECONDS) }
        if (process.isAlive) {
            process.destroyForcibly()
        }
        roomNetPidFile.delete()
        return RuntimeCommandResult(0, "Stopped RoomNet. PID ${process.pid()}")
    }

    private fun roomNetWorker(): RuntimeCommandResult {
        ensureDirs()
        val config = readConfig()
        val token = freshAccessToken(config)
            ?: return RuntimeCommandResult(1, "Sign in first.")
        val info = fetchRoomNetInfo(config, token)
        if (!info.enabled || info.self == null) {
            return RuntimeCommandResult(1, "RoomNet disabled for this device.")
        }
        val relay = RoomNetRelay(
            gson = gson,
            config = config,
            info = info,
            accessTokenProvider = { freshAccessToken(config) ?: error("Sign in first.") },
            wsUrl = roomNetWebSocketUrl(config, info),
        )
        return relay.run()
    }

    private fun fetchRoomNetInfo(config: WindowsAppConfig, token: String): RoomNetInfo {
        val query = mutableMapOf("device" to config.authDevice)
        if (config.authRoom.isNotBlank()) {
            query["room"] = config.authRoom
        }
        val url = appEndpoint(config, "/api/app/roomnet", query)
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .header("X-Yokhan-Device", config.authDevice)
            .header("X-Yokhan-Client", "GATES-Windows")
            .header("X-Yokhan-App-Version", WINDOWS_APP_VERSION)
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            error("RoomNet fetch failed: HTTP ${response.statusCode()} ${response.body()}")
        }
        return gson.fromJson(response.body(), RoomNetInfo::class.java)
    }

    private fun roomNetWebSocketUrl(config: WindowsAppConfig, info: RoomNetInfo): String {
        return canonicalWindowsRoomNetWebSocketUrl(
            appApiBaseUrl = config.appApiBaseUrl,
            advertisedUrl = info.relay.url,
            device = config.authDevice,
            room = config.authRoom,
        )
    }

    private fun describeRoomNetInfo(info: RoomNetInfo): String {
        val self = info.self
        return buildString {
            appendLine("RoomNet: ${if (info.enabled) "enabled" else "disabled"}")
            appendLine("Room: ${info.room.ifBlank { "-" }}")
            appendLine("Network: ${info.network.ifBlank { "-" }}")
            appendLine("My room address: ${self?.address ?: "-"}")
            appendLine("Connected: ${info.connected.joinToString(", ").ifBlank { "-" }}")
            if (info.peers.isEmpty()) {
                append("Peers: -")
            } else {
                appendLine("Peers:")
                info.peers.forEach { peer ->
                    val online = if (peer.online) "online" else "offline"
                    appendLine("  ${peer.id} -> ${peer.address} ports=${peer.ports.joinToString(",")} $online")
                }
            }
        }.trim()
    }

    private fun roomNetSummary(config: WindowsAppConfig): String {
        val process = managedRoomNetProcess()
        val state = readRoomNetState()
        val running = if (process?.isAlive == true) "running PID ${process.pid()}" else "stopped"
        val room = state["room"] ?: config.authRoom.ifBlank { "-" }
        val self = state["self"] ?: "-"
        return "$running; room=$room; self=$self"
    }

    private fun readRoomNetState(): Map<String, String> {
        if (!roomNetStateFile.isFile) return emptyMap()
        return runCatching {
            val obj = JsonParser.parseString(roomNetStateFile.readText(StandardCharsets.UTF_8)).asJsonObject
            obj.entrySet().associate { it.key to it.value.asString }
        }.getOrDefault(emptyMap())
    }

    private fun supportBundle(): RuntimeCommandResult {
        ensureDirs()
        extractRuntime()
        val stamp = Instant.now().toString().replace(":", "-")
        val bundleFile = File(supportDir, "support-bundle-$stamp.zip")
        val statusResult = status()
        val testResult = test()
        val routeProbeResult = probeRoutes()
        val versionResult = runProcess(listOf(singBox().absolutePath, "version"), binDir, timeoutSeconds = 20)
        val entries = linkedMapOf(
            "README.txt" to buildString {
                appendLine("GATES Windows redacted support bundle")
                appendLine("Created at: ${Instant.now()}")
                appendLine("Mode: safe proxy only")
                appendLine("No TUN, default route, system proxy, DNS, phone, or server changes are made by this export.")
                appendLine("Raw subscription JSON, full generated sing-box config, and the Xray sidecar spec are intentionally excluded.")
            },
            "status.txt" to commandOutput("status", statusResult),
            "roomnet.txt" to commandOutput("roomnet-info", runCatching { roomNetInfo() }.getOrElse {
                RuntimeCommandResult(1, it.message ?: it.toString())
            }),
            "connection-test.txt" to commandOutput("test", testResult),
            "route-probe.txt" to commandOutput("probe-routes", routeProbeResult),
            "sing-box-version.txt" to commandOutput("sing-box version", versionResult),
            "app-config.redacted.json" to redactedAppConfig(),
            "generated-config-shape.redacted.json" to generatedConfigShape(),
            "runtime-files.txt" to runtimeInventory(),
            "recent-runtime-logs.redacted.txt" to recentRuntimeLogs(),
            "connectivity-history.redacted.json" to gson.toJson(windowsConnectivityHistory()),
        )
        ZipOutputStream(bundleFile.outputStream().buffered()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(redactSecrets(content).toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        return RuntimeCommandResult(
            0,
            buildString {
                appendLine("Support bundle: ${bundleFile.absolutePath}")
                appendLine("Included: ${entries.keys.joinToString(", ")}")
                append("Route probe exit=${routeProbeResult.exitCode}; test exit=${testResult.exitCode}")
            },
        )
    }

    private fun checkUpdate(download: Boolean): RuntimeCommandResult {
        ensureDirs()
        val config = readConfig()
        val expectedSigner = System.getProperty(WINDOWS_UPDATE_SIGNER_PROPERTY)
        if (download && expectedSigner.isNullOrBlank()) {
            // Manual distribution must still work when the update API is unavailable.
            return deliverWindowsUpdate(
                expectedSigner = null,
                downloadPageUrl = config.downloadPageUrl,
                releaseUrl = config.downloadPageUrl,
                assetName = "",
                assetUrl = "",
                openPage = ::openUri,
                downloadAsset = ::downloadUpdateAsset,
                verifySignature = ::verifyWindowsInstallerSignature,
                openInstaller = ::openFile,
                latestVersion = "",
            )
        }
        val response = sendAppApiGetWithFallback(
            config = config,
            path = "/api/app/release",
            query = mapOf("platform" to WINDOWS_PLATFORM, "version" to WINDOWS_APP_VERSION),
            timeoutSeconds = 25,
        )
        if (response.statusCode() !in 200..299) {
            return RuntimeCommandResult(response.statusCode(), "Update check failed: HTTP ${response.statusCode()} ${response.body()}")
        }

        val payload = JsonParser.parseString(response.body()).asJsonObject
        val latest = payload.get("latest_version")?.asString.orEmpty()
        val releaseUrl = payload.get("release_url")?.asString?.takeIf { it.isNotBlank() } ?: config.downloadPageUrl
        val updateAvailable = payload.get("update_available")
            ?.takeIf { !it.isJsonNull }
            ?.asBoolean
        val asset = payload.get("asset")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
        val assetName = asset?.get("name")?.asString.orEmpty()
        val assetUrl = asset?.get("download_url")?.asString.orEmpty()

        if (!download) {
            return RuntimeCommandResult(
                0,
                buildString {
                    appendLine("Current version: $WINDOWS_APP_VERSION")
                    appendLine("Latest version: ${latest.ifBlank { "unknown" }}")
                    appendLine("Update available: ${updateAvailable?.toString() ?: "unknown"}")
                    appendLine("Asset: ${assetName.ifBlank { "none" }}")
                    append("Release page: $releaseUrl")
                }.trim(),
            )
        }

        if (updateAvailable == false) {
            return RuntimeCommandResult(0, "No update. Current version: $WINDOWS_APP_VERSION")
        }
        return deliverWindowsUpdate(
            expectedSigner = expectedSigner,
            downloadPageUrl = config.downloadPageUrl,
            releaseUrl = releaseUrl,
            assetName = assetName,
            assetUrl = assetUrl,
            openPage = ::openUri,
            downloadAsset = ::downloadUpdateAsset,
            verifySignature = ::verifyWindowsInstallerSignature,
            openInstaller = ::openFile,
            latestVersion = latest,
        )
    }

    private fun reportBug(message: String): RuntimeCommandResult {
        ensureDirs()
        val config = readConfig()
        val statusResult = status()
        val logsResult = logs()
        val reportRoute = reportTelemetryRoute(config)
        val telemetryPayload = buildWindowsUnknownTelemetryPayload(reportRoute).apply {
            addProperty("selected_route_mode", config.activeRoute)
        }
        val payload = JsonObject().apply {
            addProperty("platform", WINDOWS_PLATFORM)
            addProperty("app_version", WINDOWS_APP_VERSION)
            addProperty("captured_at", Instant.now().toString())
            addProperty("message", message.ifBlank { "User sent a bug report from Windows app." }.take(2000))
            addProperty("auth", authSummary())
            addProperty("selected_route_mode", config.activeRoute)
            telemetryPayload.entrySet().forEach { (key, value) -> add(key, value.deepCopy()) }
            addProperty("runtime_status", statusResult.output.take(6000))
            addProperty("runtime_logs", logsResult.output.takeLast(9000))
            addProperty("generated_config_shape", generatedConfigShape().take(14000))
            addProperty("app_config", redactedAppConfig().take(8000))
            add("connectivity_history", windowsConnectivityHistory())
        }
        val localFile = File(reportsDir, "bug-report-${Instant.now().toString().replace(":", "-")}.json")
        localFile.writeText(gson.toJson(payload), StandardCharsets.UTF_8)

        val bearer = runCatching { freshAccessToken(config) }.getOrNull()
        sendRouteTelemetry(config, telemetryPayload, bearer)
        val failures = mutableListOf<String>()
        for (url in windowsCandidateBugReportUrls(config.reportUrls)) {
            val requestBuilder = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-Yokhan-Client", "GATES-Windows")
                .header("X-Yokhan-App-Version", WINDOWS_APP_VERSION)
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
            if (!bearer.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $bearer")
            }
            val response = try {
                httpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            } catch (error: Exception) {
                failures += "$url :: ${error.message ?: error.javaClass.simpleName}"
                continue
            }
            if (response.statusCode() in 200..299) {
                return RuntimeCommandResult(
                    0,
                    "Bug report sent. Local copy: ${localFile.absolutePath}\nServer response: ${response.body()}",
                )
            }
            failures += "$url :: HTTP ${response.statusCode()} ${response.body()}"
        }

        return RuntimeCommandResult(
            1,
            buildString {
                appendLine("Bug report saved locally, but upload failed: ${localFile.absolutePath}")
                append(failures.joinToString("\n"))
            }.trim(),
        )
    }

    private fun sendRouteTelemetry(
        config: WindowsAppConfig,
        payload: JsonObject,
        bearerToken: String?,
    ) {
        if (!payload.has("active_route")) return
        runCatching {
            sendAppApiPostWithFallback(
                config = config,
                path = "/api/app/route-telemetry",
                bodyJson = gson.toJson(payload),
                bearerToken = bearerToken,
                timeoutSeconds = 10,
            )
        }
    }

    private fun normalizeLog(root: JsonObject) {
        val log = root.getAsJsonObject("log") ?: JsonObject().also { root.add("log", it) }
        log.addProperty("level", "info")
        log.addProperty("timestamp", true)
    }

    private fun normalizeDns(root: JsonObject, config: WindowsAppConfig) {
        val dns = root.getAsJsonObject("dns") ?: return
        val tags = root.getAsJsonArray("outbounds")
            ?.mapNotNull { it.asJsonObject.get("tag")?.asString }
            ?.toSet()
            ?: emptySet()
        val finalRoute = selectWindowsDnsProxyOutbound(config.activeRoute, tags)
        dns.getAsJsonArray("servers")?.forEach { element ->
            val server = element.asJsonObject
            when (server.get("tag")?.asString) {
                "direct-dns" -> server.remove("detour")
                "proxy-dns" -> server.addProperty("detour", finalRoute)
            }
        }
        applyWindowsRuDnsRules(dns, config)
    }

    private fun normalizeRoute(root: JsonObject, config: WindowsAppConfig, runtimeMode: String) {
        val route = root.getAsJsonObject("route") ?: JsonObject().also { root.add("route", it) }
        val outbounds = root.getAsJsonArray("outbounds") ?: JsonArray().also { root.add("outbounds", it) }
        ensureDirectOutbound(outbounds)
        val tags = outbounds.mapNotNull { it.asJsonObject.get("tag")?.asString }.toSet()
        if (isBootstrapProfile(config)) {
            ensureBlockOutbound(outbounds)
            if (runtimeMode == "tun") {
                route.addProperty("auto_detect_interface", true)
            }
            val proxyOutbound = if (WINDOWS_XHTTP_ROUTE_TAG in tags) WINDOWS_XHTTP_ROUTE_TAG else selectedProxyOutbound(config)
            val scopedRules = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("action", "sniff")
                    addProperty("timeout", "1s")
                })
                add(JsonObject().apply {
                    addProperty("protocol", "dns")
                    addProperty("action", "hijack-dns")
                })
                add(JsonObject().apply {
                    addProperty("ip_is_private", true)
                    addProperty("outbound", "direct")
                })
                add(JsonObject().apply {
                    add("domain_suffix", JsonArray().also { array ->
                        bootstrapAllowedDomains(config).forEach(array::add)
                    })
                    addProperty("outbound", proxyOutbound)
                })
            }
            route.add("rules", scopedRules)
            route.addProperty("final", "block")
            return
        }
        if (config.disableBridgeMultiplex == true) {
            disableBridgeMultiplex(outbounds)
        }
        if (config.activeRoute == "auto" && "auto" !in tags) {
            val health = fetchRouteHealthSnapshot(config)
            val availableProxyRoutes = (if (config.profileSource == "account") {
                validWindowsProxyRouteTags(outbounds)
            } else {
                tags.filter { it != "direct" }
            }).filterNot { it == WINDOWS_XHTTP_ROUTE_TAG }
            val candidates = orderWindowsRoutesByHealth(
                windowsAutoRouteCandidates(
                    config.routeOrder,
                    availableProxyRoutes,
                    includeUnlistedRoutes = config.profileSource == "account",
                ),
                health,
            )
            val normalizedOutbounds = JsonArray()
            normalizedOutbounds.add(buildWindowsAutoOutbound(candidates, health, config.healthcheckUrl))
            outbounds.forEach { normalizedOutbounds.add(it) }
            root.add("outbounds", normalizedOutbounds)
        }
        if (runtimeMode == "tun") {
            route.addProperty("auto_detect_interface", true)
        }
        normalizeAppRouteRules(route, config)
        route.addProperty("final", finalRoute(config, runtimeMode))
    }

    private fun ensureDirectOutbound(outbounds: JsonArray) {
        val hasDirect = outbounds.any { it.asJsonObject.get("tag")?.asString == "direct" }
        if (!hasDirect) {
            outbounds.add(
                JsonObject().apply {
                    addProperty("type", "direct")
                    addProperty("tag", "direct")
                },
            )
        }
    }

    private fun ensureBlockOutbound(outbounds: JsonArray) {
        val hasBlock = outbounds.any { it.asJsonObject.get("tag")?.asString == "block" }
        if (!hasBlock) {
            outbounds.add(
                JsonObject().apply {
                    addProperty("type", "block")
                    addProperty("tag", "block")
                },
            )
        }
    }

    private fun isBootstrapProfile(config: WindowsAppConfig): Boolean =
        config.profileSource == "bootstrap" || config.subscriptionUrl.contains("/api/bootstrap/sub/")

    private fun bootstrapAllowedDomains(config: WindowsAppConfig): List<String> {
        val extras = listOfNotNull(
            runCatching { URI.create(config.appApiBaseUrl).host }.getOrNull(),
            runCatching { URI.create(config.authIssuer).host }.getOrNull(),
        ).mapNotNull { host ->
            val clean = host.trim().trim('.').lowercase()
            clean.takeIf { it.isNotBlank() && !it.endsWith("yokhan.xyz") && !it.endsWith("yokhanfitness.ru") }
        }
        return (listOf("yokhan.xyz", "yokhanfitness.ru") + extras).distinct()
    }

    private fun normalizeAppRouteRules(route: JsonObject, config: WindowsAppConfig) {
        val rules = route.getAsJsonArray("rules") ?: JsonArray()
        val prepend = JsonArray()
        if (config.blockQuic) {
            prepend.add(windowsQuicRejectRule())
        }
        val localBaseRule = if (config.bypassLan) windowsLocalDirectRouteRule() else windowsLoopbackDirectRule()
        if (!config.killSwitchEnabled) prepend.add(localBaseRule)
        windowsAppProcessRules(config, selectedProxyOutbound(config)).forEach { prepend.add(it) }
        windowsRuBypassRouteRules(config).forEach { prepend.add(it) }
        if (config.killSwitchEnabled) prepend.add(localBaseRule)
        val merged = JsonArray()
        prepend.forEach { merged.add(it) }
        rules.forEach { element ->
            if (!config.wholeTrafficMode || !element.isJsonObject || shouldKeepUpstreamRuleForWholeTraffic(element.asJsonObject)) {
                merged.add(element)
            }
        }
        route.add("rules", merged)
        route.addProperty("find_process", true)
    }


    private fun disableBridgeMultiplex(outbounds: JsonArray) {
        outbounds.forEach { element ->
            val outbound = element.asJsonObject
            if (
                outbound.get("tag")?.asString == "bridge" &&
                outbound.get("type")?.asString == "vless"
            ) {
                outbound.getAsJsonObject("multiplex")?.addProperty("enabled", false)
            }
        }
    }

    private fun finalRoute(config: WindowsAppConfig, runtimeMode: String): String =
        if (runtimeMode == "tun" && !config.tunRouteAll) "direct" else selectedProxyOutbound(config)

    private fun selectedProxyOutbound(config: WindowsAppConfig): String =
        if (config.activeRoute == "auto") "auto" else config.activeRoute

    private fun fetchRouteHealthSnapshot(config: WindowsAppConfig): WindowsRouteHealthSnapshot {
        return runCatching {
            val response = sendAppApiGetWithFallback(
                config = config,
                path = "/api/app/route-health",
                timeoutSeconds = 8,
            )
            if (response.statusCode() !in 200..299) return@runCatching WindowsRouteHealthSnapshot()
            parseWindowsRouteHealthPayload(response.body())
        }.getOrDefault(WindowsRouteHealthSnapshot())
    }

    private fun reportTelemetryRoute(config: WindowsAppConfig): String {
        if (config.activeRoute != "auto") return config.activeRoute
        val state = readProcessState()
        val logs = listOf("out_log", "err_log")
            .mapNotNull { key -> state[key]?.let(::File) }
            .filter(File::isFile)
            .joinToString("\n") { file ->
                runCatching { file.readText(StandardCharsets.UTF_8).takeLast(64 * 1024) }
                    .getOrDefault("")
            }
        return detectWindowsRuntimeRoute(logs)
    }

    private fun applyPhysicalBind(root: JsonObject, config: WindowsAppConfig) {
        if (config.physicalBindEnabled != true) return
        val bind = resolvePhysicalBindTarget(config) ?: return
        val routes = config.physicalBindRoutes.toSet()
        if (routes.isEmpty()) return
        root.getAsJsonArray("outbounds")?.forEach { element ->
            val outbound = element.asJsonObject
            val tag = outbound.get("tag")?.asString ?: return@forEach
            if (tag !in routes) return@forEach
            if (outbound.get("type")?.asString in listOf("direct", "block", "urltest", "selector", "socks")) return@forEach
            bind.interfaceName?.takeIf { it.isNotBlank() }?.let {
                outbound.addProperty("bind_interface", it)
            }
            bind.ipv4?.takeIf { it.isNotBlank() }?.let {
                outbound.addProperty("inet4_bind_address", it)
            }
        }
    }

    private fun physicalBindSummary(config: WindowsAppConfig): String {
        if (config.physicalBindEnabled != true) return "disabled"
        val bind = resolvePhysicalBindTarget(config)
            ?: return "enabled, but no physical IPv4 adapter found"
        val routes = config.physicalBindRoutes.joinToString(",")
        val iface = bind.interfaceName ?: "auto-interface"
        val ip = bind.ipv4 ?: "auto-ipv4"
        return "$iface/$ip -> [$routes] (${bind.source})"
    }

    private fun resolvePhysicalBindTarget(config: WindowsAppConfig): PhysicalBindTarget? {
        val configuredInterface = config.physicalBindInterface?.takeIf { it.isNotBlank() }
        val configuredIpv4 = config.physicalBindIpv4?.takeIf { it.isNotBlank() }
        if (configuredInterface != null || configuredIpv4 != null) {
            val resolvedIpv4 = configuredIpv4 ?: configuredInterface?.let { ipv4ForInterface(it) }
            return PhysicalBindTarget(configuredInterface, resolvedIpv4, "configured")
        }
        return detectPhysicalBindTargetFromPowerShell()
            ?: detectPhysicalBindTargetFromJava()
    }

    private fun detectPhysicalBindTargetFromPowerShell(): PhysicalBindTarget? {
        val script = """
            ${'$'}ErrorActionPreference = 'SilentlyContinue'
            ${'$'}row = Get-NetIPConfiguration |
              Where-Object {
                ${'$'}_.NetAdapter.Status -eq 'Up' -and
                ${'$'}_.IPv4Address -and
                ${'$'}_.IPv4DefaultGateway -and
                ${'$'}_.InterfaceAlias -notmatch '(?i)neko|tun|tap|wintun|wireguard|tailscale|zerotier|vpn|loopback|docker|hyper-v|vmware|vbox'
              } |
              Sort-Object @{ Expression = { ${'$'}_.NetAdapter.InterfaceMetric }; Ascending = ${'$'}true }, InterfaceAlias |
              Select-Object -First 1
            if (${'$'}row) {
              [pscustomobject]@{
                InterfaceAliasB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]${'$'}row.InterfaceAlias))
                IPv4Address = [string]${'$'}row.IPv4Address[0].IPAddress
              } | ConvertTo-Json -Compress
            }
        """.trimIndent()
        val result = runProcess(
            listOf(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                script,
            ),
            appDir,
            timeoutSeconds = 8,
        )
        if (result.exitCode != 0 || result.output.isBlank()) return null
        return runCatching {
            val obj = JsonParser.parseString(result.output).asJsonObject
            val iface = obj.get("InterfaceAliasB64")?.asString
                ?.let(::decodeWindowsInterfaceAlias)
                ?.takeIf { it.isNotBlank() }
            val ipv4 = obj.get("IPv4Address")?.asString?.takeIf { it.isNotBlank() }
            if (iface == null && ipv4 == null) null else PhysicalBindTarget(iface, ipv4, "auto-powershell")
        }.getOrNull()
    }

    private fun detectPhysicalBindTargetFromJava(): PhysicalBindTarget? {
        return NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
            .filterNot { isExcludedInterfaceName(it.name, it.displayName) }
            .mapNotNull { networkInterface ->
                val ipv4 = networkInterface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    ?.hostAddress
                ipv4?.let {
                    PhysicalBindTarget(networkInterface.displayName ?: networkInterface.name, it, "auto-java")
                }
            }
            .firstOrNull()
    }

    private fun ipv4ForInterface(interfaceName: String): String? {
        return NetworkInterface.getNetworkInterfaces().toList()
            .firstOrNull {
                interfaceName.equals(it.name, ignoreCase = true) ||
                    interfaceName.equals(it.displayName, ignoreCase = true)
            }
            ?.inetAddresses
            ?.toList()
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }

    private fun isExcludedInterfaceName(name: String?, displayName: String?): Boolean {
        val text = listOfNotNull(name, displayName).joinToString(" ").lowercase()
        return listOf(
            "neko",
            "tun",
            "tap",
            "wintun",
            "wireguard",
            "tailscale",
            "zerotier",
            "vpn",
            "loopback",
            "docker",
            "hyper-v",
            "vmware",
            "vbox",
        ).any { it in text }
    }

    private fun httpClient(
        allowRedirects: Boolean = true,
        connectTimeout: Duration = Duration.ofSeconds(12),
    ): HttpClient =
        authHttpClient ?: HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(if (allowRedirects) HttpClient.Redirect.NORMAL else HttpClient.Redirect.NEVER)
            .build()

    private fun fetchJson(url: String, secureAuth: Boolean = false): JsonObject {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val response = httpClient(allowRedirects = !secureAuth).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            error("GET $url failed: HTTP ${response.statusCode()}")
        }
        return JsonParser.parseString(response.body()).asJsonObject
    }

    private fun postForm(url: String, form: Map<String, String>): HttpResponse<String> {
        val body = form.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofSeconds(25))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        return httpClient(allowRedirects = false).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    }

    private fun saveAuthState(
        config: WindowsAppConfig,
        tokenEndpoint: String,
        tokenPayload: JsonObject,
        previous: WindowsAuthState? = null,
    ) {
        val expiresIn = tokenPayload.get("expires_in")?.asLong ?: 300L
        val accessToken = tokenPayload.get("access_token")?.asString.orEmpty()
        require(accessToken.isNotBlank()) { "Authorization token is missing" }
        val subject = windowsTokenSubject(
            tokenPayload.get("id_token")?.asString?.takeIf { it.isNotBlank() } ?: accessToken,
        ).orEmpty()
        val sameSubject = subject.isNotBlank() && subject == previous?.accountSubject
        val state = WindowsAuthState(
            issuer = config.authIssuer,
            clientId = config.authClientId,
            tokenEndpoint = tokenEndpoint,
            accessToken = accessToken,
            refreshToken = tokenPayload.get("refresh_token")?.asString?.takeIf { it.isNotBlank() }
                ?: if (sameSubject) previous?.refreshToken.orEmpty() else "",
            expiresAtEpochSecond = Instant.now().epochSecond + expiresIn,
            accountSubject = subject,
            accountLabel = if (sameSubject) previous?.accountLabel else null,
        )
        authStateStore.write(gson.toJson(state))
    }

    /**
     * Best-effort display label from trusted same-issuer UserInfo.
     * Never blocks access setup, never logs identity, and requires the UserInfo `sub` to match the token.
     */
    private fun refreshAccountLabel(config: WindowsAppConfig, metadata: JsonObject, tokenPayload: JsonObject) {
        try {
            refreshAccountLabelBestEffort(config, metadata, tokenPayload)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The optional display label must never block a successful token exchange.
        }
    }

    private fun refreshAccountLabelBestEffort(config: WindowsAppConfig, metadata: JsonObject, tokenPayload: JsonObject) {
        val endpoint = metadata.get("userinfo_endpoint")?.asString?.takeIf { it.isNotBlank() } ?: return
        val validated = runCatching { validatedWindowsAuthEndpoint(config.authIssuer, endpoint) }.getOrNull() ?: return
        val accessToken = tokenPayload.get("access_token")?.asString?.takeIf { it.isNotBlank() } ?: return
        val subject = windowsTokenSubject(
            tokenPayload.get("id_token")?.asString?.takeIf { it.isNotBlank() } ?: accessToken,
        ) ?: return
        val request = HttpRequest.newBuilder(URI.create(validated))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build()
        val future = try {
            httpClient(allowRedirects = false, connectTimeout = Duration.ofSeconds(3))
                .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        val response = try {
            future.get(3, TimeUnit.SECONDS)
        } catch (cancelled: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            return
        } catch (_: Exception) {
            future.cancel(true)
            return
        }
        if (response.statusCode() !in 200..299) return
        val body = runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrNull() ?: return
        if (body.get("sub")?.asString != subject) return
        val label = windowsAccountLabel(
            body.get("preferred_username")?.asString,
            body.get("name")?.asString,
            body.get("email")?.asString,
        ) ?: return
        authStateStore.transaction {
            val current = readAuthState(config) ?: return@transaction
            if (current.accountSubject == subject && current.accountLabel != label) {
                authStateStore.write(gson.toJson(current.copy(accountLabel = label)))
            }
        }
    }

    private fun readAuthState(config: WindowsAppConfig): WindowsAuthState? = authStateStore.transaction {
        if (!authStateFile.isFile) return@transaction null
        runCatching {
            val stored = authStateStore.read() ?: return@runCatching null
            val state = gson.fromJson(stored.json, WindowsAuthState::class.java)
            if (!windowsAuthIssuerMatches(state.issuer, config.authIssuer)) return@runCatching null
            if (state.clientId.isNotBlank() && state.clientId != config.authClientId) return@runCatching null
            validatedWindowsAuthEndpoint(config.authIssuer, state.tokenEndpoint)
            if (stored.needsMigration) {
                authStateStore.write(stored.json)
            }
            state
        }.getOrNull()
    }

    private fun freshAccessToken(config: WindowsAppConfig): String? = authStateStore.transaction {
        val state = readAuthState(config) ?: return@transaction null
        if (state.requiresReauthentication) return@transaction null
        if (state.accessToken.isNotBlank() && state.expiresAtEpochSecond > Instant.now().epochSecond + 90) {
            return@transaction state.accessToken
        }
        if (state.refreshToken.isBlank() || state.tokenEndpoint.isBlank()) {
            return@transaction state.accessToken.takeIf { it.isNotBlank() && state.expiresAtEpochSecond > Instant.now().epochSecond }
        }
        val response = postForm(
            validatedWindowsAuthEndpoint(config.authIssuer, state.tokenEndpoint),
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to state.refreshToken,
                "client_id" to (state.clientId.ifBlank { config.authClientId }),
            ),
        )
        if (response.statusCode() !in 200..299) {
            val failure = runCatching { JsonParser.parseString(response.body()).asJsonObject.get("error")?.asString }.getOrNull()
            if (failure in setOf("invalid_grant", "invalid_token") || response.statusCode() == 401) {
                requireReauthentication(config)
                return@transaction null
            }
            if (state.accessToken.isNotBlank() && state.expiresAtEpochSecond > Instant.now().epochSecond) return@transaction state.accessToken
            error("Token refresh failed: HTTP ${response.statusCode()}")
        }
        val tokenPayload = JsonParser.parseString(response.body()).asJsonObject
        saveAuthState(config, state.tokenEndpoint, tokenPayload, state)
        tokenPayload.get("access_token")?.asString?.takeIf { it.isNotBlank() }
    }

    private fun requireReauthentication(config: WindowsAppConfig) = authStateStore.transaction {
        readAuthState(config)?.let { authStateStore.write(gson.toJson(it.copy(requiresReauthentication = true))) }
    }

    private fun authSummary(): String {
        val state = readAuthState(readConfig()) ?: return "not signed in"
        val expires = if (state.expiresAtEpochSecond > 0) {
            Instant.ofEpochSecond(state.expiresAtEpochSecond).toString()
        } else {
            "unknown"
        }
        return "signed in; client=${state.clientId.ifBlank { "unknown" }}; expires=$expires"
    }

    private fun appEndpoint(config: WindowsAppConfig, path: String, query: Map<String, String> = emptyMap()): String =
        appEndpointWithBase(config.appApiBaseUrl, path, query)

    private fun appEndpointWithBase(baseUrl: String, path: String, query: Map<String, String> = emptyMap()): String {
        val base = baseUrl.trimEnd('/')
        val encoded = query.entries
            .filter { it.value.isNotBlank() }
            .joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }
        return if (encoded.isBlank()) "$base$path" else "$base$path?$encoded"
    }

    private fun sendAppApiGetWithFallback(
        config: WindowsAppConfig,
        path: String,
        query: Map<String, String> = emptyMap(),
        bearerToken: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 15,
        allowRedirects: Boolean = true,
    ): HttpResponse<String> {
        val bases = windowsCandidateAppApiBases(config.appApiBaseUrl)
        var lastError: Exception? = null
        var lastResponse: HttpResponse<String>? = null
        val client = httpClient(allowRedirects = allowRedirects)
        for (base in bases) {
            val url = appEndpointWithBase(base, path, query)
            val builder = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("X-Yokhan-Client", "GATES-Windows")
                .header("X-Yokhan-App-Version", WINDOWS_APP_VERSION)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
            if (!bearerToken.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $bearerToken")
            }
            extraHeaders.forEach { (k, v) -> builder.header(k, v) }
            val response = try {
                client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                continue
            }
            lastResponse = response
            if (response.statusCode() < 500) {
                return response
            }
        }
        return lastResponse ?: throw (lastError ?: IllegalStateException("No App API endpoint configured"))
    }

    private fun sendAppApiPostWithFallback(
        config: WindowsAppConfig,
        path: String,
        bodyJson: String,
        bearerToken: String? = null,
        timeoutSeconds: Long = 12,
        extraHeaders: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true,
    ): HttpResponse<String> {
        val bases = windowsCandidateAppApiBases(config.appApiBaseUrl)
        var lastError: Exception? = null
        var lastResponse: HttpResponse<String>? = null
        val client = httpClient(allowRedirects = allowRedirects)
        for (base in bases) {
            val url = appEndpointWithBase(base, path)
            val builder = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-Yokhan-Client", "GATES-Windows")
                .header("X-Yokhan-App-Version", WINDOWS_APP_VERSION)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
            extraHeaders.forEach { (key, value) -> builder.header(key, value) }
            if (!bearerToken.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $bearerToken")
            }
            val response = try {
                client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                continue
            }
            lastResponse = response
            if (response.statusCode() < 500) {
                return response
            }
        }
        return lastResponse ?: throw (lastError ?: IllegalStateException("No App API endpoint configured"))
    }

    private fun enableSystemProxy(config: WindowsAppConfig): RuntimeCommandResult {
        ensureDirs()
        if (!systemProxyStateFile.isFile) {
            val before = currentSystemProxySnapshot()
            systemProxyStateFile.writeText(
                gson.toJson(before.copy(capturedAt = Instant.now().toString())),
                StandardCharsets.UTF_8,
            )
        }

        val proxyServer = buildString {
            append("http=${config.listenHost}:${config.httpPort};")
            append("https=${config.listenHost}:${config.httpPort};")
            append("socks=${config.listenHost}:${config.socksPort}")
        }
        val results = listOf(
            regAdd(internetSettingsRegKey, "ProxyEnable", "REG_DWORD", "1"),
            regAdd(internetSettingsRegKey, "ProxyServer", "REG_SZ", proxyServer),
            regAdd(internetSettingsRegKey, "ProxyOverride", "REG_SZ", "<local>"),
        )
        notifySystemProxyChanged()
        val failed = results.firstOrNull { it.exitCode != 0 }
        if (failed != null) {
            return RuntimeCommandResult(failed.exitCode, "Failed to enable Windows system proxy: ${failed.output}")
        }
        return RuntimeCommandResult(
            0,
            "Windows system proxy enabled: $proxyServer",
        )
    }

    private fun disableSystemProxy(): RuntimeCommandResult {
        ensureDirs()
        if (!systemProxyStateFile.isFile) {
            return RuntimeCommandResult(0, "Windows system proxy unchanged: no GATES snapshot.")
        }
        val snapshot = runCatching {
            gson.fromJson(systemProxyStateFile.readText(StandardCharsets.UTF_8), SystemProxySnapshot::class.java)
        }.getOrElse { error ->
            return RuntimeCommandResult(1, "Failed to read system proxy snapshot: ${error.message}")
        }

        val results = mutableListOf<RuntimeCommandResult>()
        if (snapshot.proxyEnablePresent) {
            results += regAdd(internetSettingsRegKey, "ProxyEnable", "REG_DWORD", snapshot.proxyEnable.toString())
        } else {
            results += regDelete(internetSettingsRegKey, "ProxyEnable")
        }
        if (snapshot.proxyServerPresent) {
            results += regAdd(internetSettingsRegKey, "ProxyServer", "REG_SZ", snapshot.proxyServer)
        } else {
            regDelete(internetSettingsRegKey, "ProxyServer")
        }
        if (snapshot.proxyOverridePresent) {
            results += regAdd(internetSettingsRegKey, "ProxyOverride", "REG_SZ", snapshot.proxyOverride)
        } else {
            regDelete(internetSettingsRegKey, "ProxyOverride")
        }
        notifySystemProxyChanged()
        val failed = results.firstOrNull { it.exitCode != 0 }
        if (failed != null) {
            return RuntimeCommandResult(failed.exitCode, "Failed to restore Windows system proxy: ${failed.output}")
        }
        systemProxyStateFile.delete()
        return RuntimeCommandResult(0, "Windows system proxy restored.")
    }

    private fun systemProxySummary(): String {
        val snapshot = runCatching { currentSystemProxySnapshot() }.getOrNull()
            ?: return "unknown; gates_snapshot=${systemProxyStateFile.isFile}"
        val enabled = snapshot.proxyEnable != 0
        val server = snapshot.proxyServer.takeIf { it.isNotBlank() } ?: "-"
        return "enabled=$enabled; server=$server; gates_snapshot=${systemProxyStateFile.isFile}"
    }

    private fun setAutoStart(enabled: Boolean): RuntimeCommandResult {
        ensureDirs()
        val result = if (enabled) {
            val launcher = selfLauncher()
                ?: return RuntimeCommandResult(1, "Packaged GATES launcher was not found.")
            val launcherName = File(launcher).name.lowercase()
            if (launcherName == "java.exe" || launcherName == "javaw.exe") {
                return RuntimeCommandResult(1, "Autostart requires the packaged GATES.exe launcher.")
            }
            regAdd(runRegKey, "GATES", "REG_SZ", "\"$launcher\"")
        } else {
            regDelete(runRegKey, "GATES").let {
                if (it.exitCode == 0) it else RuntimeCommandResult(0, "GATES autostart was already disabled.")
            }
        }
        if (result.exitCode == 0) {
            writeConfig(readConfig().copy(autoStartOnLogin = enabled))
        }
        return RuntimeCommandResult(
            result.exitCode,
            if (enabled) "GATES autostart enabled." else "GATES autostart disabled.",
        )
    }

    private fun currentSystemProxySnapshot(): SystemProxySnapshot {
        val proxyEnable = regQuery(internetSettingsRegKey, "ProxyEnable")
        val proxyServer = regQuery(internetSettingsRegKey, "ProxyServer")
        val proxyOverride = regQuery(internetSettingsRegKey, "ProxyOverride")
        return SystemProxySnapshot(
            proxyEnablePresent = proxyEnable.present,
            proxyEnable = parseDword(proxyEnable.value),
            proxyServerPresent = proxyServer.present,
            proxyServer = proxyServer.value,
            proxyOverridePresent = proxyOverride.present,
            proxyOverride = proxyOverride.value,
        )
    }

    private fun regQuery(key: String, name: String): RegistryValue {
        val result = runProcess(listOf("reg.exe", "query", key, "/v", name), appDir, timeoutSeconds = 10)
        if (result.exitCode != 0) return RegistryValue(false, "")
        val line = result.output
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(name, ignoreCase = true) }
            ?: return RegistryValue(false, "")
        val parts = line.split(Regex("\\s{2,}"))
        return RegistryValue(true, parts.lastOrNull().orEmpty())
    }

    private fun regAdd(key: String, name: String, type: String, value: String): RuntimeCommandResult =
        runProcess(listOf("reg.exe", "add", key, "/v", name, "/t", type, "/d", value, "/f"), appDir, timeoutSeconds = 10)

    private fun regDelete(key: String, name: String): RuntimeCommandResult =
        runProcess(listOf("reg.exe", "delete", key, "/v", name, "/f"), appDir, timeoutSeconds = 10)

    private fun isRunningAsAdministrator(): Boolean {
        val script =
            "\$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent()); " +
                "\$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)"
        val result = powershell(script, timeoutSeconds = 10)
        return result.exitCode == 0 && result.output.lineSequence().any { line ->
            line.trim().equals("true", ignoreCase = true)
        }
    }

    private fun requestElevatedCli(command: String, expectedRunning: Boolean): RuntimeCommandResult {
        val launcher = selfLauncher()
            ?: return RuntimeCommandResult(1, "Cannot request administrator rights: GATES launcher path was not found.")
        val script = listOf(
            "\$ErrorActionPreference = 'Stop';",
            "try {",
            "\$p = Start-Process",
            "-FilePath ${psSingleQuoted(launcher)}",
            "-ArgumentList @('--cli', ${psSingleQuoted(command)})",
            "-Verb RunAs",
            "-PassThru",
            "-Wait",
            "-WindowStyle Hidden;",
            "if (\$null -ne \$p) { Write-Output ('elevated_exit_code=' + \$p.ExitCode) }",
            "else { Write-Output 'elevated_started' }",
            "} catch {",
            "Write-Output ('uac_error=' + \$_.Exception.Message);",
            "exit 1",
            "}",
        ).joinToString(" ")
        val result = powershell(script, timeoutSeconds = ELEVATED_CLI_TIMEOUT_SECONDS)
        val isExpectedState = isManagedProcessRunning(managedProcessId(), managedProcess()) == expectedRunning
        val exitCode = if (result.exitCode == 0 && isExpectedState) 0 else 1
        return RuntimeCommandResult(
            exitCode,
            buildString {
                appendLine("Windows administrator approval requested for GATES $command.")
                if (result.output.isNotBlank()) appendLine(result.output)
                append(
                    if (isExpectedState) {
                        "Runtime state is correct after elevated $command."
                    } else {
                        "Runtime state did not match the expected state after elevated $command."
                    },
                )
            }.trim(),
        )
    }

    private fun notifySystemProxyChanged() {
        val memberDefinition = "[DllImport(\"wininet.dll\", SetLastError=true)] " +
            "public static extern bool InternetSetOption(System.IntPtr hInternet, int dwOption, " +
            "System.IntPtr lpBuffer, int dwBufferLength);"
        powershell(
            "Add-Type -Namespace Gates -Name WinInetNotify -MemberDefinition ${psSingleQuoted(memberDefinition)} " +
                "-ErrorAction SilentlyContinue; " +
                "[Gates.WinInetNotify]::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0) | Out-Null; " +
                "[Gates.WinInetNotify]::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0) | Out-Null",
            timeoutSeconds = 10,
        )
    }

    private fun powershell(script: String, timeoutSeconds: Int): RuntimeCommandResult =
        runProcess(
            listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script),
            appDir,
            timeoutSeconds = timeoutSeconds,
        )

    private fun psSingleQuoted(value: String): String =
        "'${value.replace("'", "''")}'"

    private fun parseDword(value: String): Int {
        val trimmed = value.trim()
        return if (trimmed.startsWith("0x", ignoreCase = true)) {
            trimmed.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: 0
        } else {
            trimmed.toIntOrNull() ?: 0
        }
    }

    private fun newPkcePair(): PkcePair {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return PkcePair(verifier, challenge)
    }

    private fun downloadUpdateAsset(name: String, url: String): File {
        updatesDir.mkdirs()
        val target = File(updatesDir, safeFileName(name))
        val temp = File(updatesDir, "${target.name}.download")
        if (temp.exists()) temp.delete()
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "GATES-Windows/$WINDOWS_APP_VERSION")
            .timeout(Duration.ofMinutes(5))
            .GET()
            .build()
        val response = httpClient().send(request, HttpResponse.BodyHandlers.ofFile(temp.toPath()))
        if (response.statusCode() !in 200..299) {
            temp.delete()
            error("Download failed: HTTP ${response.statusCode()}")
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    private fun verifyWindowsInstallerSignature(file: File): RuntimeCommandResult {
        val expectedThumbprint = System.getProperty(WINDOWS_UPDATE_SIGNER_PROPERTY).orEmpty()
            .replace(" ", "")
            .uppercase()
        if (!WINDOWS_SIGNER_PATTERN.matches(expectedThumbprint)) {
            return RuntimeCommandResult(1, "Windows update signer is not configured; installer was not opened.")
        }
        val script = "${'$'}s=Get-AuthenticodeSignature -LiteralPath ${psSingleQuoted(file.absolutePath)}; " +
            "if(${'$'}s.Status -ne 'Valid' -or ${'$'}null -eq ${'$'}s.SignerCertificate){exit 2}; " +
            "Write-Output ${'$'}s.SignerCertificate.Thumbprint"
        val signature = runProcess(
            listOf("powershell.exe", "-NoProfile", "-Command", script),
            updatesDir,
            timeoutSeconds = 30,
        )
        val actualThumbprint = signature.output.replace(" ", "").trim().uppercase()
        return if (signature.exitCode == 0 && actualThumbprint == expectedThumbprint) {
            RuntimeCommandResult(0, "Installer signature verified.")
        } else {
            RuntimeCommandResult(1, "Downloaded installer signature or publisher is invalid; installer was deleted.")
        }
    }

    private fun openUri(url: String): Boolean {
        authorizationBrowser?.let { return it(url) }
        val desktopOpened = runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url))
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (desktopOpened) return true
        return runCatching {
            ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler", url).start()
            true
        }.getOrDefault(false)
    }

    private fun openFile(file: File): Boolean =
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file)
                true
            } else {
                ProcessBuilder("cmd.exe", "/c", "start", "", file.absolutePath).start()
                true
            }
        }.getOrDefault(false)

    private fun safeFileName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "GATES-update.bin" }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun curl(
        config: WindowsAppConfig,
        url: String,
        timeoutSeconds: Int,
        compact: Boolean = false,
    ): RuntimeCommandResult {
        val format = if (compact) {
            "%{http_code} tls=%{time_appconnect}s ttfb=%{time_starttransfer}s total=%{time_total}s bytes=%{size_download} speed=%{speed_download}"
        } else {
            "%{http_code} dns=%{time_namelookup}s tcp=%{time_connect}s tls=%{time_appconnect}s ttfb=%{time_starttransfer}s total=%{time_total}s bytes=%{size_download} speed=%{speed_download}"
        }
        return runProcess(
            listOf(
                "curl.exe",
                "-L",
                "-sS",
                "-o",
                "NUL",
                "--range",
                "0-262143",
                "--proxy",
                "socks5h://${config.listenHost}:${config.socksPort}",
                "--connect-timeout",
                "8",
                "--max-time",
                timeoutSeconds.toString(),
                "-w",
                format,
                url,
            ),
            appDir,
            timeoutSeconds = timeoutSeconds + 5,
        )
    }

    private fun curlBody(config: WindowsAppConfig, url: String, timeoutSeconds: Int): RuntimeCommandResult {
        return runProcess(
            listOf(
                "curl.exe",
                "-L",
                "-sS",
                "--proxy",
                "socks5h://${config.listenHost}:${config.socksPort}",
                "--connect-timeout",
                "8",
                "--max-time",
                timeoutSeconds.toString(),
                url,
            ),
            appDir,
            timeoutSeconds = timeoutSeconds + 5,
        )
    }

    private fun runProcess(args: List<String>, directory: File, timeoutSeconds: Int): RuntimeCommandResult {
        val process = ProcessBuilder(args)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return RuntimeCommandResult(124, "Timed out: ${args.joinToString(" ")}")
        }
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trim()
        return RuntimeCommandResult(process.exitValue(), output)
    }

    private fun managedProcess(): ProcessHandle? {
        val pid = managedProcessId() ?: return null
        return ProcessHandle.of(pid).orElse(null)
    }

    private fun managedProcessId(): Long? {
        val pidFileText = if (pidFile.isFile) {
            pidFile.readText(StandardCharsets.US_ASCII)
        } else {
            null
        }
        return managedRuntimePid(pidFileText, readProcessState())
    }

    private fun isManagedProcessRunning(processId: Long?, process: ProcessHandle?): Boolean {
        if (process?.isAlive == true) return true
        return isWindowsProcessRunning(processId)
    }

    private fun isWindowsProcessRunning(processId: Long?): Boolean {
        if (processId == null) return false
        val script = "if (Get-Process -Id $processId -ErrorAction SilentlyContinue) { 'true' } else { 'false' }"
        val result = powershell(script, timeoutSeconds = 5)
        return result.exitCode == 0 && result.output.lineSequence().any { line ->
            line.trim().equals("true", ignoreCase = true)
        }
    }

    private fun stopWindowsProcess(processId: Long): RuntimeCommandResult =
        powershell("Stop-Process -Id $processId -Force", timeoutSeconds = 10)

    private fun clearRuntimeProcessState() {
        pidFile.delete()
        processStateFile.delete()
    }

    private fun managedRoomNetProcess(): ProcessHandle? {
        if (!roomNetPidFile.isFile) return null
        val pid = roomNetPidFile.readText(StandardCharsets.US_ASCII).trim().toLongOrNull() ?: return null
        return ProcessHandle.of(pid).orElse(null)
    }

    private fun selfLauncher(): String? {
        val command = ProcessHandle.current().info().command().orElse("")
        return command.takeIf { it.isNotBlank() && File(it).isFile }
    }

    private fun readProcessState(): Map<String, String> {
        if (!processStateFile.isFile) return emptyMap()
        return runCatching {
            val obj = JsonParser.parseString(processStateFile.readText(StandardCharsets.UTF_8)).asJsonObject
            obj.entrySet().associate { it.key to it.value.asString }
        }.getOrDefault(emptyMap())
    }

    private fun commandOutput(name: String, result: RuntimeCommandResult): String =
        buildString {
            appendLine("$name exit=${result.exitCode}")
            append(result.output.ifBlank { "(no output)" })
        }

    private fun redactedAppConfig(): String {
        val config = readConfig()
        return gson.toJson(config.copy(subscriptionUrl = redactSubscriptionUrl(config.subscriptionUrl)))
    }

    private fun generatedConfigShape(): String {
        if (!generatedConfigFile.isFile) {
            return "Generated config is missing. Run Import and Generate first."
        }
        val root = JsonParser.parseString(generatedConfigFile.readText(StandardCharsets.UTF_8)).asJsonObject
        val shape = JsonObject()
        shape.addProperty("generated_config_exists", true)
        shape.addProperty("route_final", root.getAsJsonObject("route")?.get("final")?.asString ?: "n/a")
        val outbounds = JsonArray()
        root.getAsJsonArray("outbounds")?.forEach { element ->
            val outbound = element.asJsonObject
            val item = JsonObject()
            listOf("tag", "type", "server", "server_port", "flow", "packet_encoding").forEach { key ->
                copyPrimitive(outbound, item, key)
            }
            listOf("bind_interface", "inet4_bind_address").forEach { key ->
                copyPrimitive(outbound, item, key)
            }
            outbound.getAsJsonArray("outbounds")?.let { routeTags ->
                item.add("outbounds", routeTags.deepCopy())
            }
            outbound.getAsJsonObject("tls")?.let { tls ->
                val tlsShape = JsonObject()
                listOf("enabled", "server_name").forEach { key -> copyPrimitive(tls, tlsShape, key) }
                tls.getAsJsonObject("utls")?.let { utls ->
                    val utlsShape = JsonObject()
                    copyPrimitive(utls, utlsShape, "fingerprint")
                    tlsShape.add("utls", utlsShape)
                }
                tls.getAsJsonObject("reality")?.let { reality ->
                    val realityShape = JsonObject()
                    copyPrimitive(reality, realityShape, "enabled")
                    tlsShape.add("reality", realityShape)
                }
                item.add("tls", tlsShape)
            }
            outbound.getAsJsonObject("multiplex")?.let { multiplex ->
                val multiplexShape = JsonObject()
                listOf("enabled", "protocol", "max_connections", "min_streams").forEach { key ->
                    copyPrimitive(multiplex, multiplexShape, key)
                }
                item.add("multiplex", multiplexShape)
            }
            outbound.getAsJsonObject("transport")?.let { transport ->
                val transportShape = JsonObject()
                copyPrimitive(transport, transportShape, "type")
                item.add("transport", transportShape)
            }
            outbounds.add(item)
        }
        shape.add("outbounds", outbounds)
        if (xhttpSpecFile.isFile) {
            runCatching { JsonParser.parseString(xhttpSpecFile.readText(StandardCharsets.UTF_8)).asJsonObject }
                .getOrNull()
                ?.let { spec ->
                    shape.add("xhttp_sidecar", JsonObject().apply {
                        addProperty("installed", true)
                        copyPrimitive(spec, this, "outbound_tag")
                        copyPrimitive(spec, this, "listen_port")
                        copyPrimitive(spec, this, "required")
                    })
                }
        }
        return gson.toJson(shape)
    }

    private fun runtimeInventory(): String {
        val files = listOf(
            appConfigFile,
            rawConfigFile,
            generatedConfigFile,
            pidFile,
            processStateFile,
            roomNetPidFile,
            roomNetStateFile,
            singBox(),
            File(binDir, "libcronet.dll"),
            File(binDir, "LICENSE"),
        )
        return buildString {
            files.forEach { file ->
                appendLine(
                    "${file.name}: exists=${file.isFile} size=${if (file.isFile) file.length() else 0} " +
                        "modified=${if (file.isFile) Instant.ofEpochMilli(file.lastModified()) else "n/a"} " +
                        "path=${file.absolutePath}",
                )
            }
        }.trim()
    }

    private fun recentRuntimeLogs(): String {
        val files = logsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(8)
            ?: emptyList()
        if (files.isEmpty()) return "No runtime log files found in ${logsDir.absolutePath}"
        return buildString {
            files.forEach { file ->
                appendLine("===== ${file.name} size=${file.length()} modified=${Instant.ofEpochMilli(file.lastModified())} =====")
                appendLine(
                    runCatching { file.readText(StandardCharsets.UTF_8).takeLast(8000) }
                        .getOrElse { "Failed to read log: ${it.message}" },
                )
            }
        }.trim()
    }

    private fun copyPrimitive(source: JsonObject, target: JsonObject, key: String) {
        val value = source.get(key) ?: return
        if (value.isJsonPrimitive || value.isJsonNull) {
            target.add(key, value.deepCopy())
        }
    }

    private fun redactSubscriptionUrl(url: String): String =
        redactedSubscriptionUrl(url)

    private fun redactSecrets(text: String): String {
        var redacted = text
        redacted = Regex("(?i)(/api/sub/)[^\\s/?#\"']+").replace(redacted) {
            it.groupValues[1] + "<redacted>"
        }
        redacted = Regex("(?i)(token=)[^\\s&#\"']+").replace(redacted) {
            it.groupValues[1] + "<redacted>"
        }
        redacted = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        ).replace(redacted, "<uuid:redacted>")
        redacted = Regex(
            "(?i)(Bearer\\s+)[^\\s\"'`,;]+",
        ).replace(redacted) {
            it.groupValues[1] + "<redacted>"
        }
        redacted = Regex(
            "(?i)(\"(?:uuid|password|token|access_token|refresh_token|private_key|public_key|short_id|authorization|auth)\"\\s*:\\s*\")[^\"]+(\")",
        ).replace(redacted) {
            it.groupValues[1] + "<redacted>" + it.groupValues[2]
        }
        return redacted
    }

    private fun ensureDirs() {
        listOf(appDir, binDir, runtimeDir, logsDir, supportDir, updatesDir, reportsDir).forEach { it.mkdirs() }
    }

    private fun extractRuntime() {
        copyResource("sing-box.exe")
        copyResource("libcronet.dll")
        copyResource("LICENSE")
    }

    private fun copyResource(name: String) {
        val target = File(binDir, name)
        if (target.isFile && target.length() > 0) return
        val resourcePath = "yokhan-runtime/windows/$name"
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: fallbackRuntimeFile(name)?.inputStream()
            ?: error("Bundled runtime resource not found: $resourcePath")
        stream.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun fallbackRuntimeFile(name: String): File? {
        val envDir = System.getenv("YOKHAN_SING_BOX_DIR")
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
        val candidates = mutableListOf<File>()
        if (envDir != null) candidates += File(envDir, name)
        val userHome = File(System.getProperty("user.home"))
        candidates += File(userHome, "Documents/YokhanIndependentVPN/.tmp/sing-box/sing-box-1.13.12-windows-amd64/$name")
        return candidates.firstOrNull { it.isFile }
    }

    private fun singBox(): File {
        ensureDirs()
        extractRuntime()
        return File(binDir, "sing-box.exe")
    }

    internal fun recoveryCoreVersion(): String =
        if (System.getenv("YOKHAN_SING_BOX_DIR").isNullOrBlank()) "1.13.12" else "unknown"

}
