package com.yokhan.gates.windows

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.awt.Desktop
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class RuntimeCommandResult(
    val exitCode: Int,
    val output: String,
)

data class WindowsAppConfig(
    val subscriptionUrl: String = "https://cloak.yokhanfitness.ru/api/sub/REPLACE_WITH_PERSONAL_TOKEN",
    val appApiBaseUrl: String = "https://cloak.yokhanfitness.ru",
    val authIssuer: String = "https://auth.yokhanfitness.ru/realms/yokhan",
    val authClientId: String = "gates-windows",
    val authDevice: String = "main",
    val authRoom: String = "",
    val downloadPageUrl: String = "https://yokhanfitness.ru/hub/gates",
    val autoCheckUpdates: Boolean = true,
    val reportUrls: List<String> = listOf(
        "https://cloak.yokhanfitness.ru/api/app/bug-report",
        "https://yokhanfitness.ru/api/app/bug-report",
    ),
    val systemProxyEnabled: Boolean = true,
    val restoreSystemProxyOnStop: Boolean = true,
    val autoStartOnLogin: Boolean = false,
    val listenHost: String = "127.0.0.1",
    val socksPort: Int = 2088,
    val httpPort: Int = 2089,
    val roomNetEnabled: Boolean = true,
    val roomNetLocalGameHost: String = "127.0.0.1",
    val activeRoute: String = "auto",
    val disableBridgeMultiplex: Boolean? = true,
    val physicalBindEnabled: Boolean? = true,
    val physicalBindInterface: String? = "",
    val physicalBindIpv4: String? = "",
    val physicalBindRoutes: List<String> = listOf("bridge", "relay"),
    val routeOrder: List<String> = listOf("bridge", "relay", "naive", "hy2"),
    val healthcheckUrl: String = "https://www.gstatic.com/generate_204",
    val testUrls: List<String> = listOf(
        "https://www.google.com/generate_204",
        "https://www.youtube.com/generate_204",
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
)

private class RoomNetRelay(
    private val gson: Gson,
    private val config: WindowsAppConfig,
    private val info: RoomNetInfo,
    private val bearerToken: String,
    private val wsUrl: String,
) {
    private val running = AtomicBoolean(true)
    private val webSocket = AtomicReference<WebSocket?>()
    private val peerSockets = ConcurrentHashMap<String, DatagramSocket>()
    private val outboundFlows = ConcurrentHashMap<String, RoomNetFlowTarget>()
    private val inboundFlows = ConcurrentHashMap<String, DatagramSocket>()
    private val done = CountDownLatch(1)

    fun run(): RuntimeCommandResult {
        val self = info.self ?: return RuntimeCommandResult(1, "RoomNet self peer is missing.")
        if (info.peers.isEmpty()) {
            return RuntimeCommandResult(1, "RoomNet has no peers.")
        }
        println("RoomNet worker starting room=${info.room} self=${self.id} address=${self.address}")
        startPeerListeners()
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .build()
        val listener = Listener()
        val ws = client.newWebSocketBuilder()
            .header("Authorization", "Bearer $bearerToken")
            .header("X-Yokhan-Device", config.authDevice)
            .header("X-Yokhan-Client", "GATES-Windows-RoomNet")
            .buildAsync(URI.create(wsUrl), listener)
            .join()
        webSocket.set(ws)
        println("RoomNet websocket connected: $wsUrl")
        Runtime.getRuntime().addShutdownHook(Thread { close() })
        done.await()
        close()
        return RuntimeCommandResult(0, "RoomNet worker stopped.")
    }

    private fun startPeerListeners() {
        for (peer in info.peers) {
            for (port in peer.ports.ifEmpty { info.ports }) {
                val key = socketKey(peer.id, port)
                val socket = runCatching {
                    DatagramSocket(null).also {
                        it.reuseAddress = true
                        it.bind(InetSocketAddress(InetAddress.getByName(peer.address), port))
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
            val source = InetSocketAddress(packet.address, packet.port)
            val flow = flowId(peer.id, port, source)
            outboundFlows[flow] = RoomNetFlowTarget(source, peer.id, port)
            sendUdpToRoom(peer.id, port, flow, packet.data.copyOf(packet.length))
        }
    }

    private fun handleRemoteUdp(message: JsonObject) {
        val from = message.get("from")?.asString.orEmpty()
        val dstPort = message.get("dst_port")?.asInt ?: return
        val flow = message.get("flow")?.asString.orEmpty()
        val payloadText = message.get("payload")?.asString.orEmpty()
        val payload = runCatching { Base64.getDecoder().decode(payloadText) }.getOrNull() ?: return

        val target = outboundFlows[flow]
        if (target != null) {
            val socket = peerSockets[socketKey(from, target.port)]
            if (socket != null && !socket.isClosed) {
                socket.send(DatagramPacket(payload, payload.size, target.address))
            }
            return
        }

        val inboundKey = "$from|$flow|$dstPort"
        val socket = inboundFlows.computeIfAbsent(inboundKey) {
            createInboundFlowSocket(from, flow, dstPort)
        }
        val localGameAddress = InetSocketAddress(InetAddress.getByName(config.roomNetLocalGameHost), dstPort)
        socket.send(DatagramPacket(payload, payload.size, localGameAddress))
    }

    private fun createInboundFlowSocket(peerId: String, flow: String, dstPort: Int): DatagramSocket {
        val socket = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        Thread({
            val buffer = ByteArray(65507)
            while (running.get() && !socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: Exception) {
                    if (running.get()) Thread.sleep(100)
                    continue
                }
                sendUdpToRoom(peerId, dstPort, flow, packet.data.copyOf(packet.length))
            }
        }, "GATES-RoomNet-inbound-$peerId-$dstPort").apply {
            isDaemon = true
            start()
        }
        return socket
    }

    private fun sendUdpToRoom(peerId: String, dstPort: Int, flow: String, payload: ByteArray) {
        val packet = JsonObject().apply {
            addProperty("type", "udp")
            addProperty("to", peerId)
            addProperty("dst_port", dstPort)
            addProperty("flow", flow)
            addProperty("payload", Base64.getEncoder().encodeToString(payload))
        }
        webSocket.get()?.sendText(gson.toJson(packet), true)
    }

    private fun close() {
        if (!running.getAndSet(false)) return
        webSocket.getAndSet(null)?.sendClose(WebSocket.NORMAL_CLOSURE, "RoomNet stop")
        peerSockets.values.forEach { runCatching { it.close() } }
        inboundFlows.values.forEach { runCatching { it.close() } }
        done.countDown()
    }

    private fun socketKey(peerId: String, port: Int): String = "$peerId:$port"

    private fun flowId(peerId: String, port: Int, source: InetSocketAddress): String {
        val raw = "$peerId|$port|${source.address.hostAddress}|${source.port}"
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    private inner class Listener : WebSocket.Listener {
        private val partial = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            partial.append(data)
            if (last) {
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
            close()
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
            println("RoomNet websocket closed: $statusCode $reason")
            close()
            return CompletableFuture.completedFuture(null)
        }
    }
}

private const val WINDOWS_APP_VERSION = "1.6.42"
private const val WINDOWS_PLATFORM = "windows"

class WindowsRuntimeController {
    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    private val appDir: File = resolveAppDir()
    private val binDir = File(appDir, "bin")
    private val runtimeDir = File(appDir, "runtime")
    private val logsDir = File(appDir, "logs")
    private val appConfigFile = File(appDir, "app-config.json")
    private val authStateFile = File(appDir, "auth-state.json")
    private val rawConfigFile = File(runtimeDir, "subscription.singbox.json")
    private val generatedConfigFile = File(runtimeDir, "config.json")
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
        val json = appConfigFile.readText(StandardCharsets.UTF_8)
        val source = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
        val normalized = normalizeConfig(gson.fromJson(json, WindowsAppConfig::class.java))
        return normalized.copy(
            systemProxyEnabled = if (source?.has("systemProxyEnabled") == true) {
                normalized.systemProxyEnabled
            } else {
                WindowsAppConfig().systemProxyEnabled
            },
            restoreSystemProxyOnStop = if (source?.has("restoreSystemProxyOnStop") == true) {
                normalized.restoreSystemProxyOnStop
            } else {
                WindowsAppConfig().restoreSystemProxyOnStop
            },
        )
    }

    fun writeConfig(config: WindowsAppConfig) {
        ensureDirs()
        appConfigFile.writeText(gson.toJson(config), StandardCharsets.UTF_8)
    }

    fun writeCliTranscript(command: String, args: List<String>, result: RuntimeCommandResult): File {
        ensureDirs()
        val file = File(logsDir, "cli-last.txt")
        file.writeText(
            buildString {
                appendLine("captured_at=${Instant.now()}")
                appendLine("command=$command")
                appendLine("args=${args.joinToString(" ")}")
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
                .filter { it in listOf("bridge", "relay", "naive", "hy2") }
        }
            .getOrDefault(defaults.physicalBindRoutes)
            .ifEmpty { defaults.physicalBindRoutes }
        val testUrls = runCatching { config.testUrls.filter { it.startsWith("https://") } }
            .getOrDefault(defaults.testUrls)
            .ifEmpty { defaults.testUrls }
        val reportUrls = runCatching { config.reportUrls.filter { it.startsWith("https://") } }
            .getOrDefault(defaults.reportUrls)
            .ifEmpty { defaults.reportUrls }
        return WindowsAppConfig(
            subscriptionUrl = runCatching { config.subscriptionUrl }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: defaults.subscriptionUrl,
            appApiBaseUrl = runCatching { config.appApiBaseUrl }.getOrNull()
                ?.trimEnd('/')
                ?.takeIf { it.startsWith("https://") }
                ?: defaults.appApiBaseUrl,
            authIssuer = runCatching { config.authIssuer }.getOrNull()
                ?.trimEnd('/')
                ?.takeIf { it.startsWith("https://") }
                ?: defaults.authIssuer,
            authClientId = runCatching { config.authClientId }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: defaults.authClientId,
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
            reportUrls = reportUrls,
            systemProxyEnabled = runCatching { config.systemProxyEnabled }.getOrDefault(defaults.systemProxyEnabled),
            restoreSystemProxyOnStop = runCatching { config.restoreSystemProxyOnStop }
                .getOrDefault(defaults.restoreSystemProxyOnStop),
            autoStartOnLogin = runCatching { config.autoStartOnLogin }.getOrDefault(defaults.autoStartOnLogin),
            listenHost = runCatching { config.listenHost }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: defaults.listenHost,
            socksPort = runCatching { config.socksPort }.getOrDefault(defaults.socksPort)
                .takeIf { it in 1..65535 }
                ?: defaults.socksPort,
            httpPort = runCatching { config.httpPort }.getOrDefault(defaults.httpPort)
                .takeIf { it in 1..65535 }
                ?: defaults.httpPort,
            roomNetEnabled = runCatching { config.roomNetEnabled }.getOrDefault(defaults.roomNetEnabled),
            roomNetLocalGameHost = runCatching { config.roomNetLocalGameHost }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: defaults.roomNetLocalGameHost,
            activeRoute = runCatching { config.activeRoute }.getOrNull()
                ?.takeIf { it in listOf("auto", "bridge", "relay", "naive", "hy2") }
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
            healthcheckUrl = runCatching { config.healthcheckUrl }.getOrNull()
                ?.takeIf { it.startsWith("https://") }
                ?: defaults.healthcheckUrl,
            testUrls = testUrls,
        )
    }

    suspend fun run(command: String, route: String? = null): RuntimeCommandResult {
        return runCatching {
            when (command) {
                "init" -> initialize()
                "login" -> login()
                "import-app" -> importAuthenticatedAppConfig()
                "import-sub" -> importSubscription()
                "generate" -> generateConfig()
                "check" -> checkConfig()
                "connect" -> connect()
                "start-proxy" -> startProxy()
                "stop" -> stop()
                "status" -> status()
                "test" -> test()
                "probe-routes" -> probeRoutes()
                "support-bundle" -> supportBundle()
                "check-update" -> checkUpdate(download = false)
                "update" -> checkUpdate(download = true)
                "report-bug" -> reportBug(route.orEmpty())
                "set-route" -> setRoute(route ?: "auto")
                "enable-system-proxy" -> enableSystemProxy(readConfig())
                "disable-system-proxy" -> disableSystemProxy()
                "enable-autostart" -> setAutoStart(enabled = true)
                "disable-autostart" -> setAutoStart(enabled = false)
                "roomnet-info" -> roomNetInfo()
                "roomnet-start" -> startRoomNet()
                "roomnet-stop" -> stopRoomNet()
                "roomnet-worker" -> roomNetWorker()
                "adopt-state" -> adoptStateFrom(File(route ?: error("Missing source runtime directory")))
                "logs" -> logs()
                else -> RuntimeCommandResult(2, "Unknown command: $command")
            }
        }.getOrElse { error ->
            RuntimeCommandResult(1, error.message ?: error.toString())
        }
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

    private fun login(): RuntimeCommandResult {
        ensureDirs()
        val config = readConfig()
        val metadata = fetchJson("${config.authIssuer}/.well-known/openid-configuration")
        val deviceEndpoint = metadata.get("device_authorization_endpoint")?.asString
            ?.takeIf { it.isNotBlank() }
            ?: "${config.authIssuer}/protocol/openid-connect/auth/device"
        val tokenEndpoint = metadata.get("token_endpoint")?.asString
            ?.takeIf { it.isNotBlank() }
            ?: error("Keycloak metadata has no token endpoint")
        val pkce = newPkcePair()
        val deviceResponse = postForm(
            deviceEndpoint,
            mapOf(
                "client_id" to config.authClientId,
                "scope" to "openid profile email",
                "code_challenge" to pkce.challenge,
                "code_challenge_method" to "S256",
            ),
        )
        if (deviceResponse.statusCode() !in 200..299) {
            return RuntimeCommandResult(
                deviceResponse.statusCode(),
                "Login start failed: HTTP ${deviceResponse.statusCode()} ${deviceResponse.body()}",
            )
        }

        val body = JsonParser.parseString(deviceResponse.body()).asJsonObject
        val deviceCode = body.get("device_code")?.asString ?: error("device_code is missing")
        val userCode = body.get("user_code")?.asString.orEmpty()
        val verificationUri = body.get("verification_uri_complete")?.asString
            ?: body.get("verification_uri")?.asString
            ?: error("verification_uri is missing")
        val expiresIn = body.get("expires_in")?.asLong ?: 600L
        var intervalSeconds = body.get("interval")?.asLong ?: 5L

        val opened = openUri(verificationUri)
        val startedAt = Instant.now().epochSecond
        val deadline = startedAt + expiresIn
        while (Instant.now().epochSecond < deadline) {
            Thread.sleep(intervalSeconds.coerceAtLeast(2L) * 1000L)
            val tokenResponse = postForm(
                tokenEndpoint,
                mapOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code" to deviceCode,
                    "client_id" to config.authClientId,
                    "code_verifier" to pkce.verifier,
                ),
            )
            if (tokenResponse.statusCode() in 200..299) {
                saveAuthState(config, tokenEndpoint, JsonParser.parseString(tokenResponse.body()).asJsonObject)
                val imported = importAuthenticatedAppConfig()
                return RuntimeCommandResult(
                    imported.exitCode,
                    buildString {
                        appendLine("Signed in with Keycloak.")
                        if (opened) {
                            appendLine("Browser was opened for device authorization.")
                        }
                        if (userCode.isNotBlank()) {
                            appendLine("User code: $userCode")
                        }
                        append(imported.output)
                    }.trim(),
                )
            }

            val error = runCatching {
                JsonParser.parseString(tokenResponse.body()).asJsonObject.get("error")?.asString
            }.getOrNull().orEmpty()
            when (error) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalSeconds += 5
                "access_denied" -> return RuntimeCommandResult(1, "Login was denied.")
                "expired_token" -> return RuntimeCommandResult(1, "Login expired. Start sign in again.")
                else -> return RuntimeCommandResult(
                    tokenResponse.statusCode(),
                    "Login token exchange failed: HTTP ${tokenResponse.statusCode()} ${tokenResponse.body()}",
                )
            }
        }

        return RuntimeCommandResult(
            1,
            buildString {
                append("Login timed out.")
                if (!opened) append(" Open this URL manually: $verificationUri")
                if (userCode.isNotBlank()) append(" Code: $userCode")
            },
        )
    }

    private fun importAuthenticatedAppConfig(): RuntimeCommandResult {
        ensureDirs()
        val config = readConfig()
        val token = freshAccessToken(config)
            ?: return RuntimeCommandResult(1, "Sign in first.")
        val query = mutableMapOf(
            "platform" to WINDOWS_PLATFORM,
            "device" to config.authDevice,
        )
        if (config.authRoom.isNotBlank()) {
            query["room"] = config.authRoom
        }
        val url = appEndpoint(config, "/api/app/singbox", query)
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .header("X-Yokhan-Device", config.authDevice)
            .header("X-Yokhan-Client", "GATES-Windows")
            .header("X-Yokhan-App-Version", WINDOWS_APP_VERSION)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            return RuntimeCommandResult(response.statusCode(), "App config fetch failed: HTTP ${response.statusCode()} ${response.body()}")
        }
        JsonParser.parseString(response.body()).asJsonObject
        rawConfigFile.writeText(response.body(), StandardCharsets.UTF_8)
        return RuntimeCommandResult(0, "Imported authenticated app config to ${rawConfigFile.absolutePath}")
    }

    private fun importSubscription(): RuntimeCommandResult {
        ensureDirs()
        val config = readConfig()
        val url = singBoxSubscriptionUrl(config.subscriptionUrl)
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .timeout(java.time.Duration.ofSeconds(30))
            .GET()
            .build()
        val response = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            return RuntimeCommandResult(response.statusCode(), "Subscription fetch failed: HTTP ${response.statusCode()}")
        }
        JsonParser.parseString(response.body()).asJsonObject
        rawConfigFile.writeText(response.body(), StandardCharsets.UTF_8)
        return RuntimeCommandResult(0, "Imported subscription sing-box JSON to ${rawConfigFile.absolutePath}")
    }

    private fun generateConfig(): RuntimeCommandResult {
        ensureDirs()
        if (!rawConfigFile.isFile) {
            return RuntimeCommandResult(1, "Missing imported config. Run Import first.")
        }
        val appConfig = readConfig()
        val root = JsonParser.parseString(rawConfigFile.readText(StandardCharsets.UTF_8)).asJsonObject
        root.add("inbounds", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "mixed")
                addProperty("tag", "mixed-in")
                addProperty("listen", appConfig.listenHost)
                addProperty("listen_port", appConfig.socksPort)
            })
            add(JsonObject().apply {
                addProperty("type", "http")
                addProperty("tag", "http-in")
                addProperty("listen", appConfig.listenHost)
                addProperty("listen_port", appConfig.httpPort)
            })
        })
        normalizeLog(root)
        normalizeDns(root, appConfig)
        normalizeRoute(root, appConfig)
        applyPhysicalBind(root, appConfig)
        generatedConfigFile.writeText(gson.toJson(root), StandardCharsets.UTF_8)
        return checkConfig().let { check ->
            if (check.exitCode == 0) {
                RuntimeCommandResult(
                    0,
                    buildString {
                        appendLine("Generated proxy-mode config: ${generatedConfigFile.absolutePath}")
                        append("Physical bind: ${physicalBindSummary(appConfig)}")
                    }.trim(),
                )
            } else {
                check
            }
        }
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

    private fun connect(): RuntimeCommandResult {
        ensureDirs()
        extractRuntime()
        val config = readConfig()
        val steps = mutableListOf<String>()
        if (
            !authStateFile.isFile &&
            !rawConfigFile.isFile &&
            config.subscriptionUrl.contains("REPLACE_WITH_PERSONAL_TOKEN")
        ) {
            val signedIn = login()
            steps += "login exit=${signedIn.exitCode}"
            if (signedIn.exitCode != 0) {
                return RuntimeCommandResult(signedIn.exitCode, steps.joinToString("\n") + "\n" + signedIn.output)
            }
        }
        if (authStateFile.isFile) {
            val imported = importAuthenticatedAppConfig()
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
        val generated = generateConfig()
        steps += "generate exit=${generated.exitCode}"
        if (generated.exitCode != 0) {
            return RuntimeCommandResult(generated.exitCode, steps.joinToString("\n") + "\n" + generated.output)
        }
        val started = startProxy()
        steps += "start-proxy exit=${started.exitCode}"
        val systemProxy = if (started.exitCode == 0 && config.systemProxyEnabled) {
            enableSystemProxy(config).also {
                steps += "system-proxy exit=${it.exitCode}"
            }
        } else {
            RuntimeCommandResult(0, "")
        }
        val roomNet = startRoomNet()
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
        )
    }

    private fun startProxy(): RuntimeCommandResult {
        ensureDirs()
        val existing = managedProcess()
        if (existing != null && existing.isAlive) {
            return RuntimeCommandResult(0, "Already running. PID ${existing.pid()}")
        }
        val check = checkConfig()
        if (check.exitCode != 0) {
            return check
        }

        val stamp = Instant.now().toString().replace(":", "-")
        val outLog = File(logsDir, "sing-box-$stamp.out.log")
        val errLog = File(logsDir, "sing-box-$stamp.err.log")
        val process = ProcessBuilder(singBox().absolutePath, "run", "-c", generatedConfigFile.absolutePath)
            .directory(binDir)
            .redirectOutput(outLog)
            .redirectError(errLog)
            .start()
        pidFile.writeText(process.pid().toString(), StandardCharsets.US_ASCII)
        processStateFile.writeText(
            gson.toJson(
                mapOf(
                    "pid" to process.pid(),
                    "out_log" to outLog.absolutePath,
                    "err_log" to errLog.absolutePath,
                    "started_at" to Instant.now().toString(),
                ),
            ),
            StandardCharsets.UTF_8,
        )
        Thread.sleep(1800)
        if (!process.isAlive) {
            return RuntimeCommandResult(
                1,
                buildString {
                    appendLine("sing-box exited early")
                    if (errLog.isFile) append(errLog.readText(StandardCharsets.UTF_8).takeLast(6000))
                }.trim(),
            )
        }
        return RuntimeCommandResult(0, "Started proxy-mode runtime. PID ${process.pid()}")
    }

    private fun stop(): RuntimeCommandResult {
        val config = readConfig()
        val roomNetStop = stopRoomNet()
        val process = managedProcess()
        if (process == null || !process.isAlive) {
            pidFile.delete()
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
        process.destroy()
        runCatching { process.onExit().get(5, TimeUnit.SECONDS) }
        if (process.isAlive) {
            process.destroyForcibly()
        }
        pidFile.delete()
        val proxyRestore = if (config.restoreSystemProxyOnStop) disableSystemProxy() else RuntimeCommandResult(0, "")
        return RuntimeCommandResult(
            if (proxyRestore.exitCode != 0) proxyRestore.exitCode else 0,
            buildString {
                appendLine("Stopped runtime. PID ${process.pid()}")
                appendLine(roomNetStop.output)
                if (proxyRestore.output.isNotBlank()) append(proxyRestore.output)
            }.trim(),
        )
    }

    private fun status(): RuntimeCommandResult {
        val config = readConfig()
        val process = managedProcess()
        val exit = if (process?.isAlive == true) {
            curlBody(config, "https://api.ipify.org", timeoutSeconds = 15).let { result ->
                if (result.exitCode == 0) result.output.trim() else "unknown: ${result.output}"
            }
        } else {
            "not running"
        }
        return RuntimeCommandResult(
            0,
            buildString {
                appendLine("Process: ${if (process?.isAlive == true) "running PID ${process.pid()}" else "stopped"}")
                appendLine("Mode: local proxy with optional Windows system proxy")
                appendLine("SOCKS: ${config.listenHost}:${config.socksPort}")
                appendLine("HTTP: ${config.listenHost}:${config.httpPort}")
                appendLine("System proxy: ${systemProxySummary()}")
                appendLine("Active route: ${config.activeRoute}")
                appendLine("Auth: ${authSummary()}")
                appendLine("RoomNet: ${roomNetSummary(config)}")
                appendLine("Physical bind: ${physicalBindSummary(config)}")
                appendLine("Exit IP via local proxy: $exit")
                appendLine("AppData: ${appDir.absolutePath}")
            }.trim(),
        )
    }

    private fun test(): RuntimeCommandResult {
        val config = readConfig()
        if (managedProcess()?.isAlive != true) {
            return RuntimeCommandResult(1, "Runtime is not running. Start proxy first.")
        }
        var failed = 0
        val output = buildString {
            for (url in config.testUrls) {
                val result = curl(config, url, timeoutSeconds = 25)
                if (result.exitCode == 0) {
                    appendLine("OK   $url :: ${result.output}")
                } else {
                    failed += 1
                    appendLine("FAIL $url :: ${result.output}")
                }
            }
        }.trim()
        return RuntimeCommandResult(if (failed == 0) 0 else 1, output)
    }

    private fun setRoute(route: String): RuntimeCommandResult {
        val normalized = route.lowercase()
        if (normalized !in listOf("auto", "bridge", "relay", "naive", "hy2")) {
            return RuntimeCommandResult(1, "Unsupported route: $route")
        }
        val config = readConfig()
        writeConfig(config.copy(activeRoute = normalized))
        val generated = if (rawConfigFile.isFile) generateConfig() else RuntimeCommandResult(0, "Config will be generated after import")
        return RuntimeCommandResult(generated.exitCode, "Active route set to $normalized\n${generated.output}".trim())
    }

    private fun probeRoutes(): RuntimeCommandResult {
        val original = readConfig()
        val wasRunning = managedProcess()?.isAlive == true
        val rows = mutableListOf<String>()
        try {
            for (route in original.routeOrder) {
                writeConfig(readConfig().copy(activeRoute = route))
                val generated = generateConfig()
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
        copyIfPresent("subscription.singbox.json", rawConfigFile, "subscription.singbox.json")
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

    private fun logs(): RuntimeCommandResult {
        val state = readProcessState()
        val outLog = state["out_log"]?.let { File(it) }
        val errLog = state["err_log"]?.let { File(it) }
        return RuntimeCommandResult(
            0,
            buildString {
                appendLine("stdout: ${outLog?.absolutePath ?: "n/a"}")
                if (outLog?.isFile == true) appendLine(outLog.readText(StandardCharsets.UTF_8).takeLast(8000))
                appendLine("stderr: ${errLog?.absolutePath ?: "n/a"}")
                if (errLog?.isFile == true) appendLine(errLog.readText(StandardCharsets.UTF_8).takeLast(8000))
            }.trim(),
        )
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
            bearerToken = token,
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
        val base = info.relay.url.ifBlank {
            config.appApiBaseUrl.trimEnd('/').replaceFirst("https://", "wss://") + info.relay.path
        }
        val query = mutableMapOf("device" to config.authDevice)
        if (config.authRoom.isNotBlank()) {
            query["room"] = config.authRoom
        }
        val encoded = query.entries.joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }
        return if ("?" in base) "$base&$encoded" else "$base?$encoded"
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
                appendLine("Raw subscription JSON and full generated sing-box config are intentionally excluded.")
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
        val url = appEndpoint(
            config,
            "/api/app/release",
            mapOf("platform" to WINDOWS_PLATFORM, "version" to WINDOWS_APP_VERSION),
        )
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("X-Yokhan-Client", "GATES-Windows")
            .header("X-Yokhan-App-Version", WINDOWS_APP_VERSION)
            .timeout(Duration.ofSeconds(25))
            .GET()
            .build()
        val response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
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
        if (assetName.isBlank() || assetUrl.isBlank()) {
            openUri(releaseUrl)
            return RuntimeCommandResult(0, "Update found, but no Windows asset was selected. Opened download page: $releaseUrl")
        }

        val target = downloadUpdateAsset(assetName, assetUrl)
        val opened = openFile(target)
        return RuntimeCommandResult(
            0,
            buildString {
                appendLine("Downloaded update: ${target.absolutePath}")
                appendLine("Latest version: ${latest.ifBlank { "unknown" }}")
                append(if (opened) "Installer opened." else "Open the downloaded file manually.")
            }.trim(),
        )
    }

    private fun reportBug(message: String): RuntimeCommandResult {
        ensureDirs()
        val config = readConfig()
        val statusResult = status()
        val logsResult = logs()
        val payload = JsonObject().apply {
            addProperty("platform", WINDOWS_PLATFORM)
            addProperty("app_version", WINDOWS_APP_VERSION)
            addProperty("captured_at", Instant.now().toString())
            addProperty("message", message.ifBlank { "User sent a bug report from Windows app." }.take(2000))
            addProperty("auth", authSummary())
            addProperty("active_route", config.activeRoute)
            addProperty("runtime_status", statusResult.output.take(6000))
            addProperty("runtime_logs", logsResult.output.takeLast(9000))
            addProperty("generated_config_shape", generatedConfigShape().take(14000))
            addProperty("app_config", redactedAppConfig().take(8000))
        }
        val localFile = File(reportsDir, "bug-report-${Instant.now().toString().replace(":", "-")}.json")
        localFile.writeText(gson.toJson(payload), StandardCharsets.UTF_8)

        val bearer = runCatching { freshAccessToken(config) }.getOrNull()
        val failures = mutableListOf<String>()
        for (url in config.reportUrls) {
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

    private fun normalizeLog(root: JsonObject) {
        val log = root.getAsJsonObject("log") ?: JsonObject().also { root.add("log", it) }
        log.addProperty("level", "info")
        log.addProperty("timestamp", true)
    }

    private fun normalizeDns(root: JsonObject, config: WindowsAppConfig) {
        val dns = root.getAsJsonObject("dns") ?: return
        val finalRoute = finalRoute(config)
        dns.getAsJsonArray("servers")?.forEach { element ->
            val server = element.asJsonObject
            when (server.get("tag")?.asString) {
                "direct-dns" -> server.remove("detour")
                "proxy-dns" -> server.addProperty("detour", finalRoute)
            }
        }
    }

    private fun normalizeRoute(root: JsonObject, config: WindowsAppConfig) {
        val route = root.getAsJsonObject("route") ?: JsonObject().also { root.add("route", it) }
        val outbounds = root.getAsJsonArray("outbounds") ?: JsonArray().also { root.add("outbounds", it) }
        val tags = outbounds.mapNotNull { it.asJsonObject.get("tag")?.asString }.toSet()
        if (config.disableBridgeMultiplex == true) {
            disableBridgeMultiplex(outbounds)
        }
        if (config.activeRoute == "auto" && "auto" !in tags) {
            val available = JsonArray()
            config.routeOrder.filter { it in tags }.forEach { available.add(it) }
            val normalizedOutbounds = JsonArray()
            normalizedOutbounds.add(
                JsonObject().apply {
                    addProperty("type", "urltest")
                    addProperty("tag", "auto")
                    add("outbounds", available)
                    addProperty("url", config.healthcheckUrl)
                    addProperty("interval", "30s")
                    addProperty("tolerance", 200)
                },
            )
            outbounds.forEach { normalizedOutbounds.add(it) }
            root.add("outbounds", normalizedOutbounds)
        }
        route.addProperty("final", finalRoute(config))
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

    private fun finalRoute(config: WindowsAppConfig): String =
        if (config.activeRoute == "auto") "auto" else config.activeRoute

    private fun applyPhysicalBind(root: JsonObject, config: WindowsAppConfig) {
        if (config.physicalBindEnabled != true) return
        val bind = resolvePhysicalBindTarget(config) ?: return
        val routes = config.physicalBindRoutes.toSet()
        if (routes.isEmpty()) return
        root.getAsJsonArray("outbounds")?.forEach { element ->
            val outbound = element.asJsonObject
            val tag = outbound.get("tag")?.asString ?: return@forEach
            if (tag !in routes) return@forEach
            if (outbound.get("type")?.asString in listOf("direct", "block", "urltest", "selector")) return@forEach
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
            Get-NetIPConfiguration |
              Where-Object {
                ${'$'}_.NetAdapter.Status -eq 'Up' -and
                ${'$'}_.IPv4Address -and
                ${'$'}_.IPv4DefaultGateway -and
                ${'$'}_.InterfaceAlias -notmatch '(?i)neko|tun|tap|wintun|wireguard|tailscale|zerotier|vpn|loopback|docker|hyper-v|vmware|vbox'
              } |
              Sort-Object @{ Expression = { ${'$'}_.NetAdapter.InterfaceMetric }; Ascending = ${'$'}true }, InterfaceAlias |
              Select-Object -First 1 @{Name='InterfaceAlias';Expression={${'$'}_.InterfaceAlias}}, @{Name='IPv4Address';Expression={${'$'}_.IPv4Address[0].IPAddress}} |
              ConvertTo-Json -Compress
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
            val iface = obj.get("InterfaceAlias")?.asString?.takeIf { it.isNotBlank() }
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

    private fun httpClient(): HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    private fun fetchJson(url: String): JsonObject {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
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
        return httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    }

    private fun saveAuthState(config: WindowsAppConfig, tokenEndpoint: String, tokenPayload: JsonObject) {
        val expiresIn = tokenPayload.get("expires_in")?.asLong ?: 300L
        val state = WindowsAuthState(
            issuer = config.authIssuer,
            clientId = config.authClientId,
            tokenEndpoint = tokenEndpoint,
            accessToken = tokenPayload.get("access_token")?.asString.orEmpty(),
            refreshToken = tokenPayload.get("refresh_token")?.asString.orEmpty(),
            expiresAtEpochSecond = Instant.now().epochSecond + expiresIn,
        )
        authStateFile.writeText(gson.toJson(state), StandardCharsets.UTF_8)
    }

    private fun readAuthState(): WindowsAuthState? {
        if (!authStateFile.isFile) return null
        return runCatching {
            gson.fromJson(authStateFile.readText(StandardCharsets.UTF_8), WindowsAuthState::class.java)
        }.getOrNull()
    }

    private fun freshAccessToken(config: WindowsAppConfig): String? {
        val state = readAuthState() ?: return null
        if (state.accessToken.isNotBlank() && state.expiresAtEpochSecond > Instant.now().epochSecond + 90) {
            return state.accessToken
        }
        if (state.refreshToken.isBlank() || state.tokenEndpoint.isBlank()) {
            return state.accessToken.takeIf { it.isNotBlank() }
        }
        val response = postForm(
            state.tokenEndpoint,
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to state.refreshToken,
                "client_id" to (state.clientId.ifBlank { config.authClientId }),
            ),
        )
        if (response.statusCode() !in 200..299) {
            if (state.accessToken.isNotBlank()) return state.accessToken
            error("Token refresh failed: HTTP ${response.statusCode()}")
        }
        val tokenPayload = JsonParser.parseString(response.body()).asJsonObject
        saveAuthState(config, state.tokenEndpoint, tokenPayload)
        return tokenPayload.get("access_token")?.asString?.takeIf { it.isNotBlank() }
    }

    private fun authSummary(): String {
        val state = readAuthState() ?: return "not signed in"
        val expires = if (state.expiresAtEpochSecond > 0) {
            Instant.ofEpochSecond(state.expiresAtEpochSecond).toString()
        } else {
            "unknown"
        }
        return "signed in; client=${state.clientId.ifBlank { "unknown" }}; expires=$expires"
    }

    private fun appEndpoint(config: WindowsAppConfig, path: String, query: Map<String, String> = emptyMap()): String {
        val base = config.appApiBaseUrl.trimEnd('/')
        val encoded = query.entries
            .filter { it.value.isNotBlank() }
            .joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }
        return if (encoded.isBlank()) "$base$path" else "$base$path?$encoded"
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

    private fun openUri(url: String): Boolean =
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url))
                true
            } else {
                ProcessBuilder("cmd.exe", "/c", "start", "", url).start()
                true
            }
        }.getOrDefault(false)

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
            "%{http_code} tls=%{time_appconnect}s ttfb=%{time_starttransfer}s total=%{time_total}s"
        } else {
            "%{http_code} dns=%{time_namelookup}s tcp=%{time_connect}s tls=%{time_appconnect}s ttfb=%{time_starttransfer}s total=%{time_total}s"
        }
        return runProcess(
            listOf(
                "curl.exe",
                "-L",
                "-sS",
                "-o",
                "NUL",
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

    private fun singBoxSubscriptionUrl(url: String): String {
        val trimmed = url.trim()
        require(trimmed.isNotBlank() && !trimmed.contains("REPLACE_WITH_PERSONAL_TOKEN")) {
            "Set subscription URL first"
        }
        return if (trimmed.contains("/singbox")) trimmed else trimmed.trimEnd('/') + "/singbox"
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
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        return RuntimeCommandResult(process.exitValue(), output)
    }

    private fun managedProcess(): ProcessHandle? {
        if (!pidFile.isFile) return null
        val pid = pidFile.readText(StandardCharsets.US_ASCII).trim().toLongOrNull() ?: return null
        return ProcessHandle.of(pid).orElse(null)
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
        redactSecrets(url)

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

    private fun resolveAppDir(): File {
        val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(System.getProperty("user.home"), "AppData/Roaming")
        return File(appData, "GATES")
    }
}
