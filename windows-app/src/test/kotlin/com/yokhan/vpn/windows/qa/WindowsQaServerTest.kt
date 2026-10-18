package com.yokhan.gates.windows.qa

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WindowsQaServerTest {
    @Test
    fun stateRequiresBearerAndSuccessfulResponsesAreNonCacheable() = withServer { server, target ->
        val unauthenticated = get(server, "/v1/state")
        assertEquals(401, unauthenticated.statusCode())
        assertEquals("unauthorized", errorCode(unauthenticated))
        assertEquals(0, target.calls.get())

        val response = get(server, "/v1/state", server.token)
        assertEquals(200, response.statusCode())
        assertEquals("dashboard", jsonField(response.body(), "screen"))
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
        assertEquals(1, target.calls.get())
    }

    @Test
    fun bearerTokenIsFreshAndOriginIsRejectedBeforeCallingTarget() = withServer { server, target ->
        val second = WindowsQaServer(target)
        try {
            assertNotEquals(server.token, second.token)
            val response = request(server, "GET", "/v1/state", server.token, origin = "http://127.0.0.1")
            assertEquals(403, response.statusCode())
            assertEquals("origin_forbidden", errorCode(response))
            assertEquals(0, target.calls.get())
        } finally {
            second.close()
        }
    }

    @Test
    fun actionRequiresExactSchemaMediaTypeAndCurrentAllowlistedAction() = withServer { server, target ->
        val wrongType = request(server, "POST", "/v1/actions", server.token, "{\"id\":\"nav.dashboard\"}", "text/plain")
        assertEquals(415, wrongType.statusCode())
        assertEquals(0, target.actionCalls.get())

        for (body in listOf(
            "{}",
            "{\"id\":1}",
            "{\"id\":\"nav.dashboard\",\"extra\":true}",
            "{\"id\":\"nav.dashboard\",\"id\":\"nav.dashboard\"}",
            "{id: \"nav.dashboard\"}",
            "{\"id\":\"${"x".repeat(81)}\"}",
        )) {
            val invalid = request(server, "POST", "/v1/actions", server.token, body)
            assertEquals(422, invalid.statusCode(), body)
            assertEquals("invalid_request", errorCode(invalid))
        }

        val forbidden = request(server, "POST", "/v1/actions", server.token, "{\"id\":\"runtime.start\"}")
        assertEquals(403, forbidden.statusCode())
        assertEquals(0, target.actionCalls.get())

        val accepted = request(server, "POST", "/v1/actions", server.token, "{\"id\":\"nav.dashboard\"}")
        assertEquals(200, accepted.statusCode())
        assertEquals("nav.dashboard", target.lastAction)
        assertEquals("no-store", accepted.headers().firstValue("Cache-Control").orElse(null))
    }

    @Test
    fun syntheticExeInputRequiresBothAllowlistAndCurrentEditableControl() = withServer { server, target ->
        val ids = listOf("input.exe.sample", "input.exe.invalid", "input.exe.clear")
        for (id in ids) {
            val absent = request(server, "POST", "/v1/actions", server.token, "{\"id\":\"$id\"}")
            assertEquals(403, absent.statusCode())
        }
        target.actions = ids + "input.exe.arbitrary"
        for (id in ids) {
            val accepted = request(server, "POST", "/v1/actions", server.token, "{\"id\":\"$id\"}")
            assertEquals(200, accepted.statusCode())
            assertEquals(id, target.lastAction)
        }
        val forbidden = request(server, "POST", "/v1/actions", server.token, "{\"id\":\"input.exe.arbitrary\"}")
        assertEquals(403, forbidden.statusCode())
        assertEquals(3, target.actionCalls.get())
    }

    @Test
    fun actionBodiesAreBoundedForContentLengthAndChunkedRequests() = withServer { server, _ ->
        val tooLarge = request(server, "POST", "/v1/actions", server.token, " ".repeat(1025))
        assertEquals(413, tooLarge.statusCode())
        assertEquals("request_too_large", errorCode(tooLarge))

        val chunked = HttpRequest.newBuilder(uri(server, "/v1/actions"))
            .timeout(Duration.ofSeconds(4))
            .header("Authorization", "Bearer ${server.token}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofInputStream { " ".repeat(1400).byteInputStream() })
            .build()
        val chunkedResponse = client.send(chunked, HttpResponse.BodyHandlers.ofString())
        assertEquals(413, chunkedResponse.statusCode())
        assertEquals("request_too_large", errorCode(chunkedResponse))
    }

    @Test
    fun routesMethodsQueriesAndEncodedPathsAreRestricted() = withServer { server, _ ->
        val wrongMethod = request(server, "POST", "/v1/state", server.token)
        assertEquals(405, wrongMethod.statusCode())
        assertEquals("method_not_allowed", errorCode(wrongMethod))

        val unknown = get(server, "/v1/unknown", server.token)
        assertEquals(404, unknown.statusCode())
        assertEquals(404, get(server, "/v1/state?x=1", server.token).statusCode())
        assertEquals(404, get(server, "/v1/%73tate", server.token).statusCode())
    }

    @Test
    fun hostMustBeExactLoopbackAndExceptionsDoNotLeakDetails() = withServer { server, target ->
        val response = rawRequest(
            server.port,
            listOf(
                "GET /v1/state HTTP/1.1",
                "Host: localhost:${server.port}",
                "Authorization: Bearer ${server.token}",
                "Connection: close",
            ).joinToString("\r\n", postfix = "\r\n\r\n"),
        )
        assertTrue(response.startsWith("HTTP/1.1 403"), response)
        assertEquals(0, target.calls.get())

        target.failure = WindowsQaException(409, "conflict")
        val failure = get(server, "/v1/state", server.token)
        assertEquals(409, failure.statusCode())
        assertEquals("{\"error\":{\"code\":\"conflict\"}}", failure.body())

        target.failure = IllegalStateException("private diagnostic detail")
        val unexpected = get(server, "/v1/state", server.token)
        assertEquals(503, unexpected.statusCode())
        assertFalse(unexpected.body().contains("private diagnostic detail"))
    }

    @Test
    fun screenshotRequiresPngAndEnforcesEightMibLimit() = withServer { server, target ->
        val valid = get(server, "/v1/screenshot", server.token)
        assertEquals(200, valid.statusCode())
        assertEquals("image/png", valid.headers().firstValue("Content-Type").orElse(null))
        assertEquals("no-store", valid.headers().firstValue("Cache-Control").orElse(null))

        target.image = byteArrayOf(1, 2, 3)
        assertEquals(503, get(server, "/v1/screenshot", server.token).statusCode())

        target.image = ByteArray(8 * 1024 * 1024 + 1)
        val large = get(server, "/v1/screenshot", server.token)
        assertEquals(413, large.statusCode())
        assertEquals("screenshot_too_large", errorCode(large))
    }

    @Test
    fun closeStopsListeningAndDoesNotInvokeTargetForUnauthorizedRequests() = withServer { server, target ->
        assertEquals(0, target.calls.get())
        server.close()
        val failed = runCatching { get(server, "/v1/state", server.token) }.isFailure
        assertTrue(failed)
        assertEquals(0, target.calls.get())
    }

    private fun withServer(block: (WindowsQaServer, FakeTarget) -> Unit) {
        val target = FakeTarget()
        WindowsQaServer(target).use { server -> block(server, target) }
    }

    private fun get(server: WindowsQaServer, path: String, token: String? = null) =
        request(server, "GET", path, token)

    private fun request(
        server: WindowsQaServer,
        method: String,
        path: String,
        token: String?,
        body: String? = null,
        contentType: String = "application/json",
        origin: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(uri(server, path)).timeout(Duration.ofSeconds(4))
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (origin != null) builder.header("Origin", origin)
        if (method == "POST") {
            builder.header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body ?: ""))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun uri(server: WindowsQaServer, path: String) = URI("http://127.0.0.1:${server.port}$path")

    private fun errorCode(response: HttpResponse<String>): String =
        Regex("\\\"code\\\":\\\"([^\\\"]+)\\\"").find(response.body())?.groupValues?.get(1)
            ?: error("No error code in ${response.body()}")

    private fun jsonField(json: String, field: String): String =
        Regex("\\\"${Regex.escape(field)}\\\":\\\"([^\\\"]*)\\\"")
            .find(json)?.groupValues?.get(1) ?: error("No $field in $json")

    private fun rawRequest(port: Int, request: String): String = Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
        socket.soTimeout = 4000
        socket.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
        socket.getOutputStream().flush()
        socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII).readText()
    }

    private class FakeTarget : WindowsQaTarget {
        val calls = AtomicInteger()
        val actionCalls = AtomicInteger()
        var lastAction: String? = null
        var actions = listOf("nav.dashboard", "fixture.connected")
        var failure: RuntimeException? = null
        var image = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

        override fun state(): WindowsQaState {
            calls.incrementAndGet()
            failure?.let { throw it }
            return WindowsQaState("dashboard", 12, false, actions)
        }

        override fun action(id: String): WindowsQaState {
            actionCalls.incrementAndGet()
            lastAction = id
            return WindowsQaState("dashboard", 13, false, listOf("nav.dashboard"))
        }

        override fun screenshot(): ByteArray = image
    }

    companion object {
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    }
}
