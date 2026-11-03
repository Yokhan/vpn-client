package com.yokhan.gates.windows

import com.yokhan.gates.windows.service.CONTROL_PORT
import com.yokhan.gates.windows.service.CURRENT_PROTOCOL_VERSION
import com.yokhan.gates.windows.service.ControlAuthentication
import com.yokhan.gates.windows.service.ServiceRequest
import com.yokhan.gates.windows.service.ServiceProtocolParser
import com.yokhan.gates.windows.service.StartTunnelRequest
import com.yokhan.gates.windows.service.StatusRequest
import com.yokhan.gates.windows.service.StopRequest
import com.yokhan.gates.windows.service.ConfigDigestRequest
import com.yokhan.gates.windows.service.ProtectionStatusRequest
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.io.File
import java.security.MessageDigest

private const val RUNTIME_SERVICE_UNAVAILABLE =
    "The per-machine GATES Runtime service is not installed or is incompatible."
private const val LOOPBACK_ADDRESS = "127.0.0.1"
private const val CONNECT_TIMEOUT_MILLIS = 2_000
private const val READ_TIMEOUT_MILLIS = 30_000
private const val RESPONSE_PART_COUNT = 3

interface WindowsRuntimeControl {
    fun health(): RuntimeCommandResult
    fun startTunnel(configJson: String): RuntimeCommandResult
    fun stop(): RuntimeCommandResult
    fun configurationDigest(): RuntimeCommandResult = RuntimeCommandResult(1, "Runtime configuration is unverified")
    fun protectionStatus(): RuntimeCommandResult = RuntimeCommandResult(1, "UNAVAILABLE")
}

class WindowsRuntimeServiceClient(
    private val controlTokenProvider: () -> String? = ::readRuntimeControlToken,
) : WindowsRuntimeControl {
    override fun health(): RuntimeCommandResult = withControlToken { token ->
        exchange(token) { proof -> StatusRequest(CURRENT_PROTOCOL_VERSION, proof) }
    }

    override fun startTunnel(configJson: String): RuntimeCommandResult {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(configJson.toByteArray(StandardCharsets.UTF_8))
        return withControlToken { token ->
            exchange(token) { proof -> StartTunnelRequest(CURRENT_PROTOCOL_VERSION, proof, encoded) }
        }
    }

    override fun stop(): RuntimeCommandResult = withControlToken { token ->
        exchange(token) { proof -> StopRequest(CURRENT_PROTOCOL_VERSION, proof) }
    }

    override fun configurationDigest(): RuntimeCommandResult = withControlToken { token ->
        exchange(token) { proof -> ConfigDigestRequest(CURRENT_PROTOCOL_VERSION, proof) }
    }

    override fun protectionStatus(): RuntimeCommandResult = withControlToken { token ->
        exchange(token) { proof -> ProtectionStatusRequest(CURRENT_PROTOCOL_VERSION, proof) }
    }

    private fun withControlToken(action: (String) -> RuntimeCommandResult): RuntimeCommandResult {
        val token = controlTokenProvider()?.trim()?.takeIf { CONTROL_TOKEN_PATTERN.matches(it) }
            ?: return RuntimeCommandResult(1, RUNTIME_SERVICE_UNAVAILABLE)
        return action(token)
    }

    private fun exchange(
        token: String,
        request: (String) -> ServiceRequest,
    ): RuntimeCommandResult = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(LOOPBACK_ADDRESS, CONTROL_PORT), CONNECT_TIMEOUT_MILLIS)
            socket.soTimeout = READ_TIMEOUT_MILLIS
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            val clientNonce = ControlAuthentication.newNonce()
            writeFrame(output, ControlAuthentication.hello(clientNonce))
            val (serverNonce, serverProof) = ControlAuthentication.parseChallenge(readFrame(input))
                ?: error("Invalid GATES Runtime challenge")
            val expectedServerProof = ControlAuthentication.serverProof(token, clientNonce, serverNonce)
            require(ControlAuthentication.matches(expectedServerProof, serverProof)) {
                "GATES Runtime identity check failed"
            }
            val clientProof = ControlAuthentication.clientProof(token, clientNonce, serverNonce)
            writeFrame(output, ServiceProtocolParser.format(request(clientProof)))
            parseResponse(readFrame(input))
        }
    }.getOrElse { RuntimeCommandResult(1, "$RUNTIME_SERVICE_UNAVAILABLE ${it.message.orEmpty()}".trim()) }
}

private fun writeFrame(output: DataOutputStream, frame: String) {
    val bytes = frame.toByteArray(StandardCharsets.UTF_8)
    output.writeInt(bytes.size)
    output.write(bytes)
    output.flush()
}

private fun readFrame(input: DataInputStream): String {
    val responseSize = input.readInt()
    require(responseSize in 1..MAX_RESPONSE_BYTES) { "Invalid runtime service response length" }
    val responseBytes = ByteArray(responseSize)
    input.readFully(responseBytes)
    return String(responseBytes, StandardCharsets.UTF_8)
}

private fun readRuntimeControlToken(): String? {
    val programData = System.getenv("ProgramData").orEmpty()
    if (programData.isBlank()) return null
    val tokenFile = File(programData, "GATES${File.separator}Service${File.separator}control.token")
    return tokenFile.takeIf(File::isFile)?.readText(StandardCharsets.US_ASCII)
}

internal fun parseRuntimeServiceResponse(response: String): RuntimeCommandResult {
    val parts = response.split('|', limit = RESPONSE_PART_COUNT)
    if (parts.size != RESPONSE_PART_COUNT || parts[0] != CURRENT_PROTOCOL_VERSION.toString()) {
        return RuntimeCommandResult(1, "Invalid GATES Runtime response.")
    }
    return when (parts[1]) {
        "OK" -> RuntimeCommandResult(0, parts[2])
        "ERROR" -> RuntimeCommandResult(1, "GATES Runtime rejected the request: ${parts[2]}")
        else -> RuntimeCommandResult(1, "Invalid GATES Runtime response.")
    }
}

private fun parseResponse(response: String): RuntimeCommandResult = parseRuntimeServiceResponse(response)

internal fun requireRuntimeStartAcknowledgement(result: RuntimeCommandResult): RuntimeCommandResult =
    if (result.exitCode != 0 || result.output.trim() in setOf("STARTED", "RESTARTED", "ALREADY_RUNNING")) result
    else RuntimeCommandResult(1, "Runtime did not acknowledge the configuration")

internal fun requireRuntimeStopAcknowledgement(result: RuntimeCommandResult): RuntimeCommandResult = when {
    result.exitCode != 0 -> result
    result.output.trim() in setOf("STOPPED", "ALREADY_STOPPED") -> result
    result.output.trim() == "STOP_REQUESTED" -> RuntimeCommandResult(1, "Runtime stop is still pending")
    else -> RuntimeCommandResult(1, "Runtime did not acknowledge stop")
}

internal fun runtimeConfigurationDigest(json: String): String {
    val canonical = Gson().toJson(JsonParser.parseString(json))
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private val CONTROL_TOKEN_PATTERN = Regex("[0-9a-f]{64}")
private const val MAX_RESPONSE_BYTES = 4096
