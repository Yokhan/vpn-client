package com.yokhan.gates.windows.service

import java.util.Base64

const val CURRENT_PROTOCOL_VERSION = 2
const val MAX_CONFIG_BYTES = 256 * 1024
const val CONTROL_PORT = 38417

private const val PROTOCOL_PART_COUNT = 4
private const val PROTOCOL_DELIMITER = '|'
private const val MAX_PROTOCOL_OVERHEAD_BYTES = 128
const val MAX_FRAME_BYTES = 350_000

enum class ServiceCommand(val wireValue: String) {
    START_TUNNEL("START_TUNNEL"),
    STOP("STOP"),
    STATUS("STATUS"),
    CONFIG_DIGEST("CONFIG_DIGEST"),
    PROTECTION_STATUS("PROTECTION_STATUS"),
}

sealed interface ServiceRequest {
    val version: Int
    val authProof: String
    val command: ServiceCommand
}

data class StartTunnelRequest(
    override val version: Int,
    override val authProof: String,
    val encodedConfig: String,
) : ServiceRequest {
    override val command = ServiceCommand.START_TUNNEL
}

data class StopRequest(
    override val version: Int,
    override val authProof: String,
) : ServiceRequest {
    override val command = ServiceCommand.STOP
}

data class StatusRequest(
    override val version: Int,
    override val authProof: String,
) : ServiceRequest {
    override val command = ServiceCommand.STATUS
}

data class ConfigDigestRequest(
    override val version: Int,
    override val authProof: String,
) : ServiceRequest {
    override val command = ServiceCommand.CONFIG_DIGEST
}

data class ProtectionStatusRequest(
    override val version: Int,
    override val authProof: String,
) : ServiceRequest {
    override val command = ServiceCommand.PROTECTION_STATUS
}

enum class ProtocolErrorCode {
    FRAME_TOO_LARGE,
    INVALID_FRAME,
    UNSUPPORTED_VERSION,
    UNSUPPORTED_COMMAND,
    INVALID_AUTH_PROOF,
    UNEXPECTED_PAYLOAD,
    INVALID_BASE64URL,
    CONFIG_TOO_LARGE,
}

data class ProtocolError(
    val code: ProtocolErrorCode,
    val message: String,
)

sealed interface ProtocolParseResult {
    data class Accepted(val request: ServiceRequest) : ProtocolParseResult
    data class Rejected(val error: ProtocolError) : ProtocolParseResult
}

sealed interface ConfigPayloadResult {
    data class Accepted(val configBytes: ByteArray) : ConfigPayloadResult
    data class Rejected(val error: ProtocolError) : ConfigPayloadResult
}

object ServiceProtocolParser {

    fun parse(frame: String): ProtocolParseResult {
        if (frame.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) {
            return reject(ProtocolErrorCode.FRAME_TOO_LARGE, "Protocol frame exceeds the maximum length")
        }
        val parts = frame.split(PROTOCOL_DELIMITER, limit = PROTOCOL_PART_COUNT + 1)
        if (parts.size != PROTOCOL_PART_COUNT) {
            return reject(ProtocolErrorCode.INVALID_FRAME, "Protocol frame must contain four parts")
        }
        return parseParts(parts)
    }

    fun format(request: ServiceRequest): String {
        require(request.version == CURRENT_PROTOCOL_VERSION) { "Unsupported protocol version" }
        require(AUTH_PROOF_PATTERN.matches(request.authProof)) { "Invalid auth proof" }
        val payload = (request as? StartTunnelRequest)?.encodedConfig.orEmpty()
        val frame = listOf(request.version, request.authProof, request.command.wireValue, payload)
            .joinToString(PROTOCOL_DELIMITER.toString())
        require(frame.toByteArray(Charsets.UTF_8).size <= MAX_FRAME_BYTES) { "Protocol frame exceeds the maximum length" }
        return frame
    }

    fun decodeConfig(encodedConfig: String): ConfigPayloadResult = try {
        val configBytes = Base64.getUrlDecoder().decode(encodedConfig)
        if (configBytes.size > MAX_CONFIG_BYTES) {
            ConfigPayloadResult.Rejected(ProtocolError(ProtocolErrorCode.CONFIG_TOO_LARGE, "Config exceeds 256 KiB"))
        } else {
            ConfigPayloadResult.Accepted(configBytes)
        }
    } catch (_: IllegalArgumentException) {
        ConfigPayloadResult.Rejected(ProtocolError(ProtocolErrorCode.INVALID_BASE64URL, "Config must be base64url"))
    }

    private fun parseParts(parts: List<String>): ProtocolParseResult {
        val version = parts[0].toIntOrNull()
            ?: return reject(ProtocolErrorCode.UNSUPPORTED_VERSION, "Protocol version must be numeric")
        if (version != CURRENT_PROTOCOL_VERSION) {
            return reject(ProtocolErrorCode.UNSUPPORTED_VERSION, "Protocol version is not supported")
        }
        val authProof = parts[1]
        if (!AUTH_PROOF_PATTERN.matches(authProof)) {
            return reject(ProtocolErrorCode.INVALID_AUTH_PROOF, "Auth proof is invalid")
        }
        return when (parts[2]) {
            ServiceCommand.START_TUNNEL.wireValue -> parseStart(version, authProof, parts[3])
            ServiceCommand.STOP.wireValue -> parseEmptyPayload(version, authProof, ServiceCommand.STOP, parts[3])
            ServiceCommand.STATUS.wireValue -> parseEmptyPayload(version, authProof, ServiceCommand.STATUS, parts[3])
            ServiceCommand.CONFIG_DIGEST.wireValue -> parseEmptyPayload(version, authProof, ServiceCommand.CONFIG_DIGEST, parts[3])
            ServiceCommand.PROTECTION_STATUS.wireValue -> parseEmptyPayload(version, authProof, ServiceCommand.PROTECTION_STATUS, parts[3])
            else -> reject(ProtocolErrorCode.UNSUPPORTED_COMMAND, "Command is not permitted")
        }
    }

    private fun parseStart(version: Int, authProof: String, payload: String): ProtocolParseResult {
        if (payload.isBlank()) return reject(ProtocolErrorCode.INVALID_FRAME, "START_TUNNEL requires a config payload")
        if (payload.length > MAX_FRAME_BYTES - MAX_PROTOCOL_OVERHEAD_BYTES) {
            return reject(ProtocolErrorCode.FRAME_TOO_LARGE, "START_TUNNEL payload exceeds the frame bound")
        }
        return ProtocolParseResult.Accepted(StartTunnelRequest(version, authProof, payload))
    }

    private fun parseEmptyPayload(
        version: Int,
        authProof: String,
        command: ServiceCommand,
        payload: String,
    ): ProtocolParseResult {
        if (payload.isNotEmpty()) return reject(ProtocolErrorCode.UNEXPECTED_PAYLOAD, "${command.wireValue} does not accept a payload")
        val request = when (command) {
            ServiceCommand.STOP -> StopRequest(version, authProof)
            ServiceCommand.STATUS -> StatusRequest(version, authProof)
            ServiceCommand.CONFIG_DIGEST -> ConfigDigestRequest(version, authProof)
            ServiceCommand.PROTECTION_STATUS -> ProtectionStatusRequest(version, authProof)
            ServiceCommand.START_TUNNEL -> error("START_TUNNEL requires a payload")
        }
        return ProtocolParseResult.Accepted(request)
    }

    private fun reject(code: ProtocolErrorCode, message: String): ProtocolParseResult.Rejected =
        ProtocolParseResult.Rejected(ProtocolError(code, message))
}

private val AUTH_PROOF_PATTERN = Regex("[0-9a-f]{64}")
