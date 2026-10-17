package com.yokhan.gates.app.roomnet

import android.net.VpnService
import android.annotation.SuppressLint
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.yokhan.gates.app.auth.YokhanKeycloakAuth
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class RoomNetState { IDLE, FETCHING, CONNECTING, CONNECTED, STOPPED, ERROR }

data class RoomNetPeerStatus(
    val id: String,
    val username: String,
    val device: String,
    val address: String,
    val ports: List<Int>,
    val online: Boolean,
)

data class RoomNetStatus(
    val state: RoomNetState = RoomNetState.IDLE,
    val room: String = "",
    val network: String = "",
    val self: RoomNetPeerStatus? = null,
    val peers: List<RoomNetPeerStatus> = emptyList(),
    val connectedPeerIds: List<String> = emptyList(),
    val lastSanitizedError: String? = null,
)

object RoomNetClient {
    private val mutableStatus = MutableStateFlow(RoomNetStatus())
    val status: StateFlow<RoomNetStatus> = mutableStatus.asStateFlow()
    private val lock = Any()
    private val generations = RoomNetSessionGeneration()
    // Owned by the active VPN lifecycle; stop() clears it before service teardown.
    @SuppressLint("StaticFieldLeak")
    @Volatile private var session: RoomNetSession? = null

    fun start(service: VpnService) {
        if (DataStore.serviceMode != Key.MODE_VPN ||
            !DataStore.serviceState.connected ||
            DataStore.vpnService !== service
        ) return
        synchronized(lock) {
            if (session?.isFor(service) == true) return
            session?.close()
            session = null
            val generation = generations.next()
            if (!YokhanKeycloakAuth.isConfigured()) {
                mutableStatus.value = RoomNetStatus(
                    state = RoomNetState.STOPPED,
                    lastSanitizedError = "auth_not_configured",
                )
                return
            }
            val next = RoomNetSession(service) { nextStatus ->
                synchronized(lock) {
                    if (generations.isCurrent(generation)) mutableStatus.value = nextStatus
                }
            }
            session = next
            next.start()
        }
    }

    fun stop(service: VpnService) {
        synchronized(lock) {
            if (session?.isFor(service) != true) return
            generations.next()
            session?.close()
            session = null
            mutableStatus.value = RoomNetStatus(RoomNetState.STOPPED)
        }
    }
}

private data class RoomNetFlowTarget(
    val address: InetSocketAddress,
    val peerId: String,
    val port: Int,
    val lastActivityMillis: Long,
)

private data class RoomNetInboundFlow(
    val socket: DatagramSocket,
    val peerId: String,
    val port: Int,
    val flow: String,
    val lastActivityMillis: Long,
)

private data class RoomNetConnectionEnd(
    val statusCode: Int?,
    val reason: String = "",
    val error: Throwable? = null,
)

private class RoomNetSession(
    private val service: VpnService,
    private val publishStatus: (RoomNetStatus) -> Unit,
) {
    private val appContext = service.applicationContext
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
    private val webSocket = java.util.concurrent.atomic.AtomicReference<WebSocket?>()
    private val httpCall = java.util.concurrent.atomic.AtomicReference<Call?>()
    private val peerSockets = ConcurrentHashMap<String, DatagramSocket>()
    private val outboundFlows = LinkedHashMap<String, RoomNetFlowTarget>()
    private val outboundLock = Any()
    private val inboundFlows = LinkedHashMap<String, RoomNetInboundFlow>()
    private val inboundLock = Any()
    private val statusLock = Any()
    private var currentStatus = RoomNetStatus()
    private lateinit var roomInfo: RoomNetInfo
    private lateinit var deviceId: String
    private lateinit var wsUrl: String
    private lateinit var knownPeers: Set<String>
    private lateinit var allowedPortsByPeer: Map<String, Set<Int>>

    fun isFor(candidate: VpnService): Boolean = service === candidate

    fun start() {
        if (job == null) job = scope.launch { run() }
    }

    fun close() {
        if (!stopped.compareAndSet(false, true)) return
        webSocket.getAndSet(null)?.let { runCatching { it.close(1000, "RoomNet stopped") } }
        httpCall.getAndSet(null)?.cancel()
        peerSockets.values.forEach { runCatching { it.close() } }
        peerSockets.clear()
        synchronized(inboundLock) {
            inboundFlows.values.forEach { runCatching { it.socket.close() } }
            inboundFlows.clear()
        }
        synchronized(outboundLock) { outboundFlows.clear() }
        job?.cancel()
        scope.cancel()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private suspend fun run() {
        try {
            emit(RoomNetStatus(RoomNetState.FETCHING))
            val token = YokhanKeycloakAuth.freshAccessToken(appContext)
                ?: return stopWith("auth_session_missing")
            roomInfo = fetchRoomInfo(token)
            if (!roomInfo.enabled) return stopWith(null)
            val self = roomInfo.self ?: error("RoomNet response is missing the self peer")
            if (roomInfo.room.isBlank() || self.id.isBlank()) error("RoomNet room identity is invalid")
            if (roomInfo.peers.isEmpty()) return stopWith(null)
            val peerIds = roomInfo.peers.map { it.id }
            if (peerIds.any { it.isBlank() || it == self.id } || peerIds.distinct().size != peerIds.size) {
                error("RoomNet peer identities are invalid")
            }
            if (!areRoomNetAddressesDistinct(self.address, roomInfo.peers.map { it.address })) {
                error("RoomNet peer addresses are invalid or duplicated")
            }

            deviceId = deviceIdForRequest()
            wsUrl = canonicalRoomNetWebSocketUrl(
                BuildConfig.YOKHAN_APP_API_BASE_URL,
                roomInfo.relay.url,
                deviceId,
                roomInfo.room,
            )
            knownPeers = peerIds.toSet()
            allowedPortsByPeer = roomInfo.peers.associate { peer ->
                peer.id to roomNetAllowedPorts(roomInfo.ports, peer.ports)
            }
            validateListenerLimits()
            startPeerListeners()
            if (peerSockets.isEmpty()) error("RoomNet could not open a loopback UDP listener")
            publish(RoomNetState.CONNECTING)
            reconnectLoop()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(LOG_TAG, "RoomNet stopped: ${error.javaClass.simpleName}: ${error.message}")
            updateStatus { it.copy(state = RoomNetState.ERROR, lastSanitizedError = sanitizeError(error)) }
        } finally {
            webSocket.getAndSet(null)?.let { runCatching { it.close(1000, "RoomNet session ended") } }
            closeSockets()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }

    private suspend fun fetchRoomInfo(initialToken: String): RoomNetInfo = withContext(Dispatchers.IO) {
        var token = initialToken
        repeat(2) { attempt ->
            val endpoint = roomNetApiUrl(BuildConfig.YOKHAN_APP_API_BASE_URL) +
                "?device=${encodeRoomNetQueryValue(deviceIdForRequest())}"
            val request = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $token")
                .header("X-Yokhan-Device", deviceIdForRequest())
                .header("X-Yokhan-Client", "GATES-Android")
                .get()
                .build()
            val call = client.newCall(request)
            httpCall.set(call)
            try {
                call.execute().use { response ->
                    if (response.code == 401 && attempt == 0) {
                        val refreshed = YokhanKeycloakAuth.freshAccessToken(appContext)
                        if (refreshed.isNullOrBlank() || refreshed == token) error("RoomNet authorization was rejected")
                        token = refreshed
                        return@use
                    }
                    if (!response.isSuccessful) error("RoomNet fetch failed: HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    if (body.length > MAX_ROOMNET_RESPONSE_CHARS) error("RoomNet response is too large")
                    return@withContext gson.fromJson(body, RoomNetInfo::class.java)
                        ?: error("RoomNet response is empty")
                }
            } finally {
                httpCall.compareAndSet(call, null)
            }
        }
        error("RoomNet authorization was rejected")
    }

    // Preserve the existing room peer identity; never include it in automatic reports.
    @SuppressLint("HardwareIds")
    private fun deviceIdForRequest(): String {
        if (!::deviceId.isInitialized) {
            deviceId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() }
                ?: error("Android device identity is unavailable")
        }
        return deviceId
    }

    private fun validateListenerLimits() {
        if (allowedPortsByPeer.values.any { it.size > ROOMNET_MAX_LISTENERS_PER_PEER } ||
            allowedPortsByPeer.values.sumOf { it.size } > ROOMNET_MAX_LISTENERS
        ) error("RoomNet listener limits were exceeded")
        if (allowedPortsByPeer.values.none { it.isNotEmpty() }) error("RoomNet has no permitted peer ports")
    }

    private suspend fun reconnectLoop() {
        var retryToken: String? = null
        var failureCount = 0
        var authRetryCount = 0
        while (!stopped.get()) {
            val token = retryToken ?: YokhanKeycloakAuth.freshAccessToken(appContext)
                ?: return stopWith("auth_session_missing")
            retryToken = null
            val connectedAt = System.currentTimeMillis()
            val end = awaitWebSocket(token)
            if (stopped.get()) return
            if (System.currentTimeMillis() - connectedAt >= ROOMNET_AUTH_RETRY_RESET_MILLIS) authRetryCount = 0

            val refreshForAuth = end.statusCode == 401 ||
                (end.statusCode == 1008 && end.reason.trim().equals("session expired", ignoreCase = true))
            val refreshed = if (refreshForAuth) YokhanKeycloakAuth.freshAccessToken(appContext) else token
            if (decideRoomNetRetry(
                    end.statusCode,
                    end.reason,
                    token,
                    refreshed,
                    stopped.get(),
                    authRetryCount,
                ) == RoomNetRetryDecision.STOP
            ) {
                val failure = when {
                    end.statusCode == 403 || end.statusCode == 1008 -> "room_policy_rejected"
                    end.statusCode == 401 -> "authentication_rejected"
                    else -> "roomnet_unavailable"
                }
                return stopWith(failure)
            }
            if (refreshForAuth) {
                retryToken = refreshed
                authRetryCount++
            }
            failureCount++
            publish(RoomNetState.CONNECTING)
            delay(roomNetReconnectDelayMillis(failureCount))
        }
    }

    private suspend fun awaitWebSocket(token: String): RoomNetConnectionEnd {
        val ended = CompletableDeferred<RoomNetConnectionEnd>()
        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $token")
            .header("X-Yokhan-Device", deviceId)
            .header("X-Yokhan-Client", "GATES-Android-RoomNet")
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@RoomNetSession.webSocket.set(webSocket)
                publish(RoomNetState.CONNECTED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length <= MAX_ROOMNET_MESSAGE_CHARS) handleMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                this@RoomNetSession.webSocket.compareAndSet(webSocket, null)
                ended.complete(RoomNetConnectionEnd(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                this@RoomNetSession.webSocket.compareAndSet(webSocket, null)
                ended.complete(RoomNetConnectionEnd(response?.code, error = t))
            }
        }
        val socket = client.newWebSocket(request, listener)
        webSocket.set(socket)
        return try {
            ended.await()
        } finally {
            webSocket.compareAndSet(socket, null)
        }
    }

    private fun handleMessage(text: String) {
        val message = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
        when (message.get("type")?.asString.orEmpty()) {
            "udp" -> handleRemoteDatagram(message)
            "hello" -> Unit
            "error" -> {
                val reason = message.get("error")?.asString.orEmpty()
                if (reason.equals("forbidden", ignoreCase = true) || reason.equals("policy rejected", ignoreCase = true)) {
                    webSocket.get()?.close(1008, "policy violation")
                }
            }
        }
    }

    private fun startPeerListeners() {
        roomInfo.peers.forEach { peer ->
            allowedPortsByPeer[peer.id].orEmpty().forEach portLoop@{ port ->
                val bindAddress = roomNetPeerSocketAddress(peer.address, port) ?: return@portLoop
                val socket = createProtectedSocket(bindAddress) ?: return@portLoop
                peerSockets[peerSocketKey(peer.id, port)] = socket
                scope.launch { receiveLocalPeerLoop(peer, port, socket) }
            }
        }
    }

    private fun createProtectedSocket(bindAddress: SocketAddress): DatagramSocket? = runCatching {
        val socket = DatagramSocket(null as SocketAddress?)
        try {
            socket.reuseAddress = true
            check(service.protect(socket)) { "VpnService.protect rejected RoomNet socket" }
            socket.soTimeout = UDP_SOCKET_TIMEOUT_MILLIS
            socket.bind(bindAddress)
            socket
        } catch (error: Throwable) {
            socket.close()
            throw error
        }
    }.getOrElse {
        Log.w(LOG_TAG, "RoomNet UDP socket unavailable: ${it.javaClass.simpleName}")
        null
    }

    private suspend fun receiveLocalPeerLoop(peer: RoomNetPeer, port: Int, socket: DatagramSocket) {
        val buffer = ByteArray(ROOMNET_MAX_DATAGRAM_BYTES)
        while (!stopped.get() && !socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                expireFlows()
                continue
            } catch (_: Exception) {
                if (!stopped.get()) delay(100)
                continue
            }
            if (!packet.address.isLoopbackAddress || packet.length > ROOMNET_MAX_DATAGRAM_BYTES) continue
            val source = InetSocketAddress(packet.address, packet.port)
            val flow = flowId(peer.id, port, source) ?: continue
            val now = System.currentTimeMillis()
            val accepted = synchronized(outboundLock) {
                expireOutboundFlows(now)
                val old = outboundFlows[flow]
                if (old == null) {
                    val peerCount = outboundFlows.values.count { it.peerId == peer.id }
                    if (!canCreateRoomNetFlow(peer.id, peerCount, outboundFlows.size)) return@synchronized false
                }
                outboundFlows[flow] = RoomNetFlowTarget(source, peer.id, port, now)
                true
            }
            if (!accepted) continue
            sendUdpToRoom(peer.id, port, flow, packet.data.copyOf(packet.length))
        }
    }

    private fun handleRemoteDatagram(message: com.google.gson.JsonObject) {
        val destinationPort = runCatching { message.get("dst_port")?.asInt ?: return }.getOrNull() ?: return
        val datagram = runCatching {
            RoomNetDatagram(
                room = message.get("room")?.asString.orEmpty(),
                to = message.get("to")?.asString.orEmpty(),
                from = message.get("from")?.asString.orEmpty(),
                destinationPort = destinationPort,
                flow = message.get("flow")?.asString.orEmpty(),
                encodedPayload = message.get("payload")?.asString.orEmpty(),
            )
        }.getOrNull() ?: return
        val payload = validateRoomNetDatagram(
            datagram,
            roomInfo.room,
            roomInfo.self?.id.orEmpty(),
            knownPeers,
            allowedPortsByPeer[datagram.from].orEmpty(),
        ) ?: return
        val now = System.currentTimeMillis()
        val (hadFlowTarget, flowTarget) = synchronized(outboundLock) {
            expireOutboundFlows(now)
            val old = outboundFlows[datagram.flow] ?: return@synchronized (false to null)
            if (!roomNetResponseFlowMatches(old.peerId, old.port, datagram.from, destinationPort)) {
                return@synchronized (true to null)
            }
            (true to old.copy(lastActivityMillis = now).also { outboundFlows[datagram.flow] = it })
        }
        if (hadFlowTarget && flowTarget == null) return
        if (flowTarget != null) {
            peerSockets[peerSocketKey(datagram.from, flowTarget.port)]?.takeUnless { it.isClosed }
                ?.send(DatagramPacket(payload, payload.size, flowTarget.address))
            return
        }

        val key = inboundFlowKey(datagram.from, datagram.flow, destinationPort)
        val inbound = synchronized(inboundLock) {
            expireInboundFlows(now)
            val existing = inboundFlows[key]
            if (existing != null) {
                existing.copy(lastActivityMillis = now).also { inboundFlows[key] = it }
            } else {
                val peerFlowCount = inboundFlows.values.count { it.peerId == datagram.from }
                if (!canCreateRoomNetFlow(datagram.from, peerFlowCount, inboundFlows.size)) return
                val socket = createProtectedSocket(InetSocketAddress(InetAddress.getByName(ROOMNET_LOCAL_GAME_HOST), 0))
                    ?: return
                RoomNetInboundFlow(socket, datagram.from, destinationPort, datagram.flow, now)
                    .also { inboundFlows[key] = it }
                    .also { flow -> scope.launch { receiveLocalGameReplies(key, flow) } }
            }
        }
        inbound.socket.send(
            DatagramPacket(payload, payload.size, InetSocketAddress(InetAddress.getByName(ROOMNET_LOCAL_GAME_HOST), destinationPort)),
        )
    }

    private suspend fun receiveLocalGameReplies(key: String, initialFlow: RoomNetInboundFlow) {
        val socket = initialFlow.socket
        val buffer = ByteArray(ROOMNET_MAX_DATAGRAM_BYTES)
        while (!stopped.get() && !socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                if (!expireInboundFlow(key, socket)) continue
                break
            } catch (_: Exception) {
                if (!stopped.get()) delay(100)
                continue
            }
            if (packet.address.hostAddress != ROOMNET_LOCAL_GAME_HOST) continue
            val now = System.currentTimeMillis()
            val flow = synchronized(inboundLock) {
                val current = inboundFlows[key] ?: return@synchronized null
                if (current.socket !== socket || isRoomNetFlowExpired(current.lastActivityMillis, now)) {
                    inboundFlows.remove(key)
                    socket.close()
                    null
                } else current.copy(lastActivityMillis = now).also { inboundFlows[key] = it }
            } ?: continue
            sendUdpToRoom(flow.peerId, flow.port, flow.flow, packet.data.copyOf(packet.length))
        }
    }

    private fun expireInboundFlow(key: String, socket: DatagramSocket): Boolean = synchronized(inboundLock) {
        val flow = inboundFlows[key] ?: return@synchronized true
        if (flow.socket !== socket) return@synchronized true
        if (!isRoomNetFlowExpired(flow.lastActivityMillis, System.currentTimeMillis())) return@synchronized false
        inboundFlows.remove(key)
        socket.close()
        true
    }

    private fun expireFlows() {
        val now = System.currentTimeMillis()
        synchronized(outboundLock) { expireOutboundFlows(now) }
        synchronized(inboundLock) { expireInboundFlows(now) }
    }

    private fun expireOutboundFlows(now: Long) {
        outboundFlows.entries.removeIf { isRoomNetFlowExpired(it.value.lastActivityMillis, now) }
    }

    private fun expireInboundFlows(now: Long) {
        val expired = inboundFlows.filterValues { isRoomNetFlowExpired(it.lastActivityMillis, now) }.keys
        expired.forEach { key -> inboundFlows.remove(key)?.socket?.close() }
    }

    private fun sendUdpToRoom(peerId: String, port: Int, flow: String, payload: ByteArray) {
        val socket = webSocket.get() ?: return
        if (payload.size > ROOMNET_MAX_DATAGRAM_BYTES) return
        val message = mapOf(
            "type" to "udp",
            "room" to roomInfo.room,
            "to" to peerId,
            "dst_port" to port,
            "flow" to flow,
            "payload" to payload.toByteString().base64(),
        )
        socket.send(gson.toJson(message))
    }

    private fun flowId(peerId: String, port: Int, source: InetSocketAddress): String? {
        val raw = "$peerId|$port|${source.address.hostAddress}|${source.port}"
        return raw.toByteArray(Charsets.UTF_8).toByteString().base64Url().trimEnd('=')
            .takeIf { it.length <= 256 }
    }

    private fun peerSocketKey(peerId: String, port: Int): String = "$peerId:$port"
    private fun inboundFlowKey(peerId: String, flow: String, port: Int): String = "$peerId|$flow|$port"

    private fun stopWith(lastSanitizedError: String?) {
        publish(RoomNetState.STOPPED, lastSanitizedError)
    }

    private fun publish(state: RoomNetState, lastSanitizedError: String? = null) {
        val self = roomInfo.self?.toStatus()
        emit(RoomNetStatus(
            state = state,
            room = roomInfo.room,
            network = roomInfo.network,
            self = self,
            peers = roomInfo.peers.map { it.toStatus() },
            connectedPeerIds = roomInfo.connected,
            lastSanitizedError = lastSanitizedError,
        ))
    }

    private fun emit(next: RoomNetStatus) {
        updateStatus { next }
    }

    private fun updateStatus(update: (RoomNetStatus) -> RoomNetStatus) {
        val next = synchronized(statusLock) {
            update(currentStatus).also { currentStatus = it }
        }
        publishStatus(next)
    }

    private fun sanitizeError(error: Exception): String = when {
        error.message?.contains("HTTP 401", ignoreCase = true) == true -> "authentication_rejected"
        error.message?.contains("HTTP 403", ignoreCase = true) == true -> "room_policy_rejected"
        error.message?.contains("listener limits", ignoreCase = true) == true -> "listener_limit_exceeded"
        error.message?.contains("addresses", ignoreCase = true) == true -> "invalid_peer_addresses"
        error.message?.contains("identity", ignoreCase = true) == true -> "invalid_room_identity"
        error.message?.contains("permitted peer ports", ignoreCase = true) == true -> "no_permitted_ports"
        else -> "roomnet_unavailable"
    }

    private fun closeSockets() {
        peerSockets.values.forEach { runCatching { it.close() } }
        peerSockets.clear()
        synchronized(inboundLock) {
            inboundFlows.values.forEach { runCatching { it.socket.close() } }
            inboundFlows.clear()
        }
        synchronized(outboundLock) { outboundFlows.clear() }
    }

    private fun RoomNetPeer.toStatus() = RoomNetPeerStatus(id, username, device, address, ports.toList(), online)

    private companion object {
        const val LOG_TAG = "GatesRoomNet"
        const val UDP_SOCKET_TIMEOUT_MILLIS = 1_000
        const val MAX_ROOMNET_RESPONSE_CHARS = 1_000_000
        const val MAX_ROOMNET_MESSAGE_CHARS = 100_000
    }
}
