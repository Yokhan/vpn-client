package com.yokhan.gates.windows.qa

import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.atomic.AtomicBoolean

data class WindowsQaState(
    val screen: String,
    val revision: Long,
    val busy: Boolean,
    val actions: List<String>,
    val fixture: Boolean = true,
    val runtimeControl: Boolean = false,
    val connectionStatus: String = "unknown",
    val signedIn: Boolean = false,
    val profileReady: Boolean = false,
)

interface WindowsQaTarget {
    fun state(): WindowsQaState
    fun action(id: String): WindowsQaState
    fun screenshot(): ByteArray
    fun controls(): Any = emptyList<Any>()
    fun performance(): Any = emptyMap<String, Any>()
}

class WindowsQaException(val status: Int, val code: String) : RuntimeException()

class WindowsQaServer(private val target: WindowsQaTarget) : AutoCloseable {
    private val gson = Gson()
    private val closed = AtomicBoolean(false)
    private val timeoutExecutor = ScheduledThreadPoolExecutor(1, namedThreadFactory("windows-qa-timeout")).apply {
        removeOnCancelPolicy = true
    }
    private val requestExecutor = ThreadPoolExecutor(
        4,
        4,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(16),
        namedThreadFactory("windows-qa-http"),
        RejectedExecutionHandler { task, executor ->
            if (!executor.isShutdown) {
                OVERLOADED_REQUEST.set(true)
                try {
                    task.run()
                } finally {
                    OVERLOADED_REQUEST.remove()
                }
            }
        },
    )
    private val server: HttpServer
    private val bearerBytes: ByteArray

    val port: Int
    val token: String

    init {
        val address = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        server = HttpServer.create(address, 16)
        port = server.address.port
        token = ByteArray(32).also(SecureRandom()::nextBytes).let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }
        bearerBytes = "Bearer $token".toByteArray(StandardCharsets.US_ASCII)
        server.executor = requestExecutor
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.stop(0)
        requestExecutor.shutdownNow()
        timeoutExecutor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        val overloaded = OVERLOADED_REQUEST.get() == true
        val timeout = if (overloaded) null else timeoutExecutor.schedule({
            try {
                exchange.close()
            } catch (_: Exception) {
                // The request may already have completed.
            }
        }, REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        try {
            if (!exchange.remoteAddress.address.isLoopbackAddress || !isExactHost(exchange)) {
                throw WindowsQaException(403, "forbidden")
            }
            if (exchange.requestHeaders.containsKey("Origin")) {
                throw WindowsQaException(403, "origin_forbidden")
            }
            if (!isAuthorized(exchange)) throw WindowsQaException(401, "unauthorized")

            val uri = exchange.requestURI
            if (uri.rawQuery != null || uri.rawPath.contains('%') || uri.rawPath.contains('\\')) {
                throw WindowsQaException(404, "not_found")
            }
            when (uri.rawPath) {
                STATE_PATH -> {
                    requireMethod(exchange, "GET")
                    if (overloaded) sendError(exchange, 503, "unavailable")
                    else sendJson(exchange, 200, target.state())
                }
                ACTIONS_PATH -> {
                    requireMethod(exchange, "POST")
                    requireJsonContentType(exchange)
                    if (overloaded) {
                        sendError(exchange, 503, "unavailable")
                        return
                    }
                    val id = readActionId(exchange)
                    val current = target.state()
                    val semanticClick = id.startsWith("click.") && id.removePrefix("click.")
                        .matches(Regex("(?:dashboard|settings|routing|report|route|form|history)\\.[a-z0-9_.-]{1,64}"))
                    if ((id !in allowedActions && !semanticClick) || id !in current.actions) {
                        throw WindowsQaException(403, "action_forbidden")
                    }
                    sendJson(exchange, 200, target.action(id))
                }
                SCREENSHOT_PATH -> {
                    requireMethod(exchange, "GET")
                    if (overloaded) {
                        sendError(exchange, 503, "unavailable")
                        return
                    }
                    val bytes = target.screenshot()
                    if (bytes.size > MAX_SCREENSHOT_BYTES) {
                        throw WindowsQaException(413, "screenshot_too_large")
                    }
                    if (!hasPngSignature(bytes)) throw IOException("Invalid screenshot payload")
                    sendBytes(exchange, 200, "image/png", bytes)
                }
                "/v1/controls", "/v1/performance" -> {
                    requireMethod(exchange, "GET")
                    if (overloaded) { sendError(exchange, 503, "unavailable"); return }
                    sendJson(exchange, 200, if (uri.rawPath == "/v1/controls") target.controls() else target.performance())
                }
                else -> throw WindowsQaException(404, "not_found")
            }
        } catch (failure: WindowsQaException) {
            sendError(exchange, failure.status, failure.code)
        } catch (_: Exception) {
            sendError(exchange, 503, "unavailable")
        } finally {
            timeout?.cancel(false)
            exchange.close()
        }
    }

    private fun isExactHost(exchange: HttpExchange): Boolean {
        val values = exchange.requestHeaders["Host"] ?: return false
        return values.size == 1 && values[0] == "127.0.0.1:$port"
    }

    private fun isAuthorized(exchange: HttpExchange): Boolean {
        val values = exchange.requestHeaders["Authorization"] ?: return false
        if (values.size != 1) return false
        return MessageDigest.isEqual(bearerBytes, values[0].toByteArray(StandardCharsets.UTF_8))
    }

    private fun requireMethod(exchange: HttpExchange, expected: String) {
        if (exchange.requestMethod != expected) throw WindowsQaException(405, "method_not_allowed")
    }

    private fun requireJsonContentType(exchange: HttpExchange) {
        val values = exchange.requestHeaders["Content-Type"] ?: throw WindowsQaException(415, "unsupported_media_type")
        if (values.size != 1 || !values[0].substringBefore(';').trim().equals("application/json", ignoreCase = true)) {
            throw WindowsQaException(415, "unsupported_media_type")
        }
        val charset = values[0].split(';').drop(1).map { it.trim() }
            .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')?.trim('"')
        if (charset != null && !charset.equals("utf-8", ignoreCase = true)) {
            throw WindowsQaException(415, "unsupported_media_type")
        }
    }

    private fun readActionId(exchange: HttpExchange): String {
        val contentLengthValues = exchange.requestHeaders["Content-length"]
        if (contentLengthValues != null && (contentLengthValues.size != 1 || contentLengthValues[0].toLongOrNull() == null)) {
            throw WindowsQaException(422, "invalid_request")
        }
        if (contentLengthValues?.singleOrNull()?.toLongOrNull()?.let { it > MAX_ACTION_BYTES } == true) {
            throw WindowsQaException(413, "request_too_large")
        }

        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(256)
        exchange.requestBody.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > MAX_ACTION_BYTES) throw WindowsQaException(413, "request_too_large")
                bytes.write(buffer, 0, count)
            }
        }

        val reader = try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            JsonReader(decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).let { chars -> java.io.StringReader(chars.toString()) })
        } catch (_: Exception) {
            throw WindowsQaException(422, "invalid_request")
        }

        return try {
            reader.isLenient = false
            reader.beginObject()
            if (!reader.hasNext() || reader.nextName() != "id" || reader.peek() != JsonToken.STRING) {
                throw WindowsQaException(422, "invalid_request")
            }
            val id = reader.nextString()
            if (id.isEmpty() || id.length > MAX_ACTION_ID_LENGTH || reader.hasNext()) {
                throw WindowsQaException(422, "invalid_request")
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT) throw WindowsQaException(422, "invalid_request")
            id
        } catch (failure: WindowsQaException) {
            throw failure
        } catch (_: Exception) {
            throw WindowsQaException(422, "invalid_request")
        } finally {
            reader.close()
        }
    }

    private fun sendJson(exchange: HttpExchange, status: Int, value: Any) {
        sendBytes(exchange, status, "application/json; charset=utf-8", gson.toJson(value).toByteArray(StandardCharsets.UTF_8))
    }

    private fun sendError(exchange: HttpExchange, status: Int, code: String) {
        if (exchange.responseCode != -1) return
        val safeStatus = status.takeIf { it in ERROR_STATUSES } ?: 503
        val safeCode = code.takeIf { it.matches(CODE_PATTERN) } ?: "request_failed"
        val body = "{\"error\":{\"code\":\"$safeCode\"}}".toByteArray(StandardCharsets.UTF_8)
        sendBytes(exchange, safeStatus, "application/json; charset=utf-8", body)
    }

    private fun sendBytes(exchange: HttpExchange, status: Int, contentType: String, bytes: ByteArray) {
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun hasPngSignature(bytes: ByteArray): Boolean =
        bytes.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    companion object {
        private const val STATE_PATH = "/v1/state"
        private const val ACTIONS_PATH = "/v1/actions"
        private const val SCREENSHOT_PATH = "/v1/screenshot"
        private const val MAX_ACTION_BYTES = 1024
        private const val MAX_ACTION_ID_LENGTH = 80
        private const val MAX_SCREENSHOT_BYTES = 8 * 1024 * 1024
        private const val REQUEST_TIMEOUT_SECONDS = 10L
        private val ERROR_STATUSES = setOf(401, 403, 404, 405, 409, 413, 415, 422, 503)
        private val CODE_PATTERN = Regex("[a-z0-9._-]{1,64}")
        private val OVERLOADED_REQUEST = ThreadLocal<Boolean>()
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        private val allowedActions = setOf(
            "perf.reset", "perf.stop", "viewport.compact", "viewport.default", "viewport.wide",
            "scroll.forward", "scroll.back",
            "key.tab", "key.escape",
            "focus.first",
        "fixture.loading", "fixture.empty", "fixture.long", "fixture.default", "fixture.signedout", "fixture.signedin", "fixture.update", "fixture.effects-off", "fixture.effects-on",
            "input.report.sample", "input.report.clear", "input.access.sample", "input.access.clear", "input.access.private",
            "input.routing.sample", "input.routing.clear", "input.routing.none",
            "nav.dashboard",
            "nav.settings",
            "nav.access",
            "nav.routing",
            "nav.history",
            "nav.bug",
            "nav.back",
            "history.refresh",
            "fixture.disconnected",
            "fixture.connected",
            "fixture.connecting",
            "fixture.error",
        )

        private fun namedThreadFactory(prefix: String) = ThreadFactory { runnable ->
            Thread(runnable, "$prefix-${THREAD_NUMBER.incrementAndGet()}").apply { isDaemon = true }
        }

        private val THREAD_NUMBER = java.util.concurrent.atomic.AtomicInteger()
    }
}
