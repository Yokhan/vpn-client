package com.yokhan.gates.windows.service

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val LOOPBACK_ADDRESS = "127.0.0.1"
private const val CLIENT_READ_TIMEOUT_MILLIS = 5_000
private const val CONTROL_WORKER_COUNT = 4
private const val CONTROL_QUEUE_CAPACITY = 16

class SerializedServiceHandler(
    private val supervisor: SerializedTunnelSupervisor,
    private val expectedControlToken: String,
) {
    private val lock = Any()

    internal fun controlTokenForHandshake(): String = expectedControlToken

    fun handle(frame: String, clientNonce: String, serverNonce: String): String = synchronized(lock) {
        when (val parsed = ServiceProtocolParser.parse(frame)) {
            is ProtocolParseResult.Rejected -> errorResponse(parsed.error.code.name)
            is ProtocolParseResult.Accepted -> if (isAuthorized(parsed.request.authProof, clientNonce, serverNonce)) {
                handleRequest(parsed.request)
            } else {
                errorResponse("UNAUTHORIZED")
            }
        }
    }

    private fun isAuthorized(authProof: String, clientNonce: String, serverNonce: String): Boolean =
        ControlAuthentication.matches(
            ControlAuthentication.clientProof(expectedControlToken, clientNonce, serverNonce),
            authProof,
        )

    private fun handleRequest(request: ServiceRequest): String = when (request) {
        is StartTunnelRequest -> handleStart(request)
        is StopRequest -> handleStop()
        is StatusRequest -> successResponse(supervisor.state().name)
        is ConfigDigestRequest -> successResponse(supervisor.configurationDigest() ?: "NONE")
    }

    private fun handleStop(): String = try {
        when (supervisor.stop()) {
            TunnelStopResult.Stopped -> successResponse("STOPPED")
            TunnelStopResult.StopRequested -> successResponse("STOP_REQUESTED")
            TunnelStopResult.AlreadyStopped -> successResponse("ALREADY_STOPPED")
        }
    } catch (_: IOException) {
        errorResponse("STOP_FAILED")
    }

    private fun handleStart(request: StartTunnelRequest): String {
        val payload = when (val decoded = ServiceProtocolParser.decodeConfig(request.encodedConfig)) {
            is ConfigPayloadResult.Accepted -> decoded.configBytes
            is ConfigPayloadResult.Rejected -> return errorResponse(decoded.error.code.name)
        }
        val config = when (val validated = SingBoxConfigValidator.validate(payload)) {
            is ConfigValidationResult.Accepted -> validated.config
            is ConfigValidationResult.Rejected -> return errorResponse(validated.error.code.name)
        }
        return try {
            when (supervisor.start(config)) {
                TunnelStartResult.Started -> successResponse("STARTED")
                TunnelStartResult.Restarted -> successResponse("RESTARTED")
                TunnelStartResult.AlreadyRunning -> successResponse("ALREADY_RUNNING")
            }
        } catch (_: IOException) {
            errorResponse("LAUNCH_FAILED")
        } catch (_: IllegalArgumentException) {
            errorResponse("LAUNCH_FAILED")
        }
    }

    private fun successResponse(value: String): String = "$CURRENT_PROTOCOL_VERSION|OK|$value"

    private fun errorResponse(value: String): String = "$CURRENT_PROTOCOL_VERSION|ERROR|$value"
}

class LoopbackControlServer(
    private val handler: SerializedServiceHandler,
) : AutoCloseable {
    private var serverSocket: ServerSocket? = null
    private val executor = ThreadPoolExecutor(
        CONTROL_WORKER_COUNT,
        CONTROL_WORKER_COUNT,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(CONTROL_QUEUE_CAPACITY),
    )

    fun runForever() {
        ServerSocket(CONTROL_PORT, 1, InetAddress.getByName(LOOPBACK_ADDRESS)).use { socket ->
            serverSocket = socket
            while (!socket.isClosed) {
                val client = socket.accept()
                runCatching { executor.execute { client.use(::handleClient) } }
                    .onFailure { client.close() }
            }
        }
    }

    override fun close() {
        serverSocket?.close()
        executor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = CLIENT_READ_TIMEOUT_MILLIS
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val clientNonce = ControlAuthentication.parseHello(readLengthPrefixedFrame(input))
                ?: throw InvalidFrameException()
            val serverNonce = ControlAuthentication.newNonce()
            val serverProof = ControlAuthentication.serverProof(expectedControlToken(), clientNonce, serverNonce)
            writeFrame(output, ControlAuthentication.challenge(serverNonce, serverProof))
            val response = handler.handle(readLengthPrefixedFrame(input), clientNonce, serverNonce)
            writeFrame(output, response)
        } catch (_: EOFException) {
            return
        } catch (_: InvalidFrameException) {
            return
        } catch (_: IOException) {
            return
        }
    }

    private fun expectedControlToken(): String = handler.controlTokenForHandshake()
}

internal fun readLengthPrefixedFrame(input: DataInputStream): String {
    val length = input.readInt()
    if (length <= 0 || length > MAX_FRAME_BYTES) throw InvalidFrameException()
    val frame = ByteArray(length)
    input.readFully(frame)
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(frame))
            .toString()
    } catch (_: CharacterCodingException) {
        throw InvalidFrameException()
    }
}

private fun writeFrame(output: DataOutputStream, response: String) {
    val frame = response.toByteArray(Charsets.UTF_8)
    output.writeInt(frame.size)
    output.write(frame)
    output.flush()
}

private fun errorResponse(code: ProtocolErrorCode): String = "$CURRENT_PROTOCOL_VERSION|ERROR|${code.name}"

internal class InvalidFrameException : RuntimeException()
