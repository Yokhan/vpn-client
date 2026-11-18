package com.yokhan.gates.windows

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.Authenticator
import java.net.CookieHandler
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Flow
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsReportAndProxyFallbackTest {
    @Test
    fun reportBugSendsGzippedJsonAndFallsBackToPlainOn415() = runBlocking {
        val root = Files.createTempDirectory("gates-report-gzip-").toFile()
        val seenRequests = mutableListOf<Triple<String, String?, ByteArray>>()
        val http = RecordingHttpClient { request, bodyBytes ->
            val encoding = request.headers().firstValue("Content-Encoding").orElse(null)
            seenRequests += Triple(request.uri().toString(), encoding, bodyBytes)
            if (encoding == "gzip") {
                415 to """{"error":"unsupported media"}"""
            } else {
                201 to """{"status":"ok","report_id":"r-1"}"""
            }
        }
        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = false,
            appDir = root,
            authHttpClient = http,
        )

        val result = controller.run("report-bug", "Test bug message")
        assertEquals(0, result.exitCode, result.output)
        assertEquals(2, seenRequests.size)
        val first = seenRequests[0]
        assertEquals("gzip", first.second)
        val decompressed = GZIPInputStream(ByteArrayInputStream(first.third)).use {
            it.readBytes().toString(StandardCharsets.UTF_8)
        }
        val obj = JsonParser.parseString(decompressed).asJsonObject
        assertEquals("Test bug message", obj.get("message").asString)

        val second = seenRequests[1]
        assertEquals(null, second.second)
        val plainObj = JsonParser.parseString(second.third.toString(StandardCharsets.UTF_8)).asJsonObject
        assertEquals("Test bug message", plainObj.get("message").asString)
    }

    @Test
    fun reportBugFallsBackToLocalHttpProxyWhenDirectFailsAndProxyIsReachable() = runBlocking {
        val root = Files.createTempDirectory("gates-report-proxy-").toFile()
        var directAttempts = 0
        val http = RecordingHttpClient { _, _ ->
            directAttempts += 1
            503 to """{"error":"direct blocked"}"""
        }
        val connectLines = ConcurrentHashMap.newKeySet<String>()
        val proxySocket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val proxyThread = thread(isDaemon = true) {
            while (!proxySocket.isClosed) {
                val client = runCatching { proxySocket.accept() }.getOrNull() ?: break
                runCatching {
                    client.soTimeout = 2_000
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
                    val line = reader.readLine()
                    if (!line.isNullOrBlank()) connectLines += line
                    client.getOutputStream().write("HTTP/1.1 502 Proxy Test Sink\r\nContent-Length: 0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    client.close()
                }
            }
        }
        try {
            val proxyPort = proxySocket.localPort
            val controller = WindowsRuntimeController(
                allowRuntimeServiceDelegation = false,
                appDir = root,
                authHttpClient = http,
                localProxyAddressProvider = { InetSocketAddress("127.0.0.1", proxyPort) },
            )

            controller.run("report-bug", "Direct blocked, proxy alive")
            assertTrue(directAttempts >= 2, "Expected direct attempts to all candidate URLs before proxy fallback")
            assertTrue(
                connectLines.any { it.startsWith("CONNECT ") && it.contains("yokhan.xyz:443") },
                "Expected local HTTP proxy fallback to issue CONNECT to yokhan.xyz, got=$connectLines",
            )
        } finally {
            runCatching { proxySocket.close() }
            proxyThread.join(1_000)
        }
    }

    private class RecordingHttpClient(
        private val respond: (HttpRequest, ByteArray) -> Pair<Int, String>,
    ) : HttpClient() {
        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.of(Duration.ofSeconds(5))
        override fun followRedirects(): Redirect = Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters(): SSLParameters = SSLParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
        override fun version(): Version = Version.HTTP_1_1
        override fun executor(): Optional<Executor> = Optional.empty()

        override fun <T : Any?> send(
            request: HttpRequest,
            handler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> {
            val bytes = ByteArrayOutputStream()
            request.bodyPublisher().ifPresent { pub ->
                pub.subscribe(object : Flow.Subscriber<ByteBuffer> {
                    override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)
                    override fun onNext(item: ByteBuffer) {
                        val arr = ByteArray(item.remaining())
                        item.get(arr)
                        bytes.write(arr)
                    }
                    override fun onError(throwable: Throwable) {}
                    override fun onComplete() {}
                })
            }
            val (status, response) = respond(request, bytes.toByteArray())
            @Suppress("UNCHECKED_CAST")
            return object : HttpResponse<String> {
                override fun statusCode() = status
                override fun request() = request
                override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
                override fun headers() = HttpHeaders.of(emptyMap()) { _, _ -> true }
                override fun body() = response
                override fun sslSession(): Optional<SSLSession> = Optional.empty()
                override fun uri(): URI = request.uri()
                override fun version() = Version.HTTP_1_1
            } as HttpResponse<T>
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            handler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = error("Unexpected async")

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            handler: HttpResponse.BodyHandler<T>,
            pushHandler: HttpResponse.PushPromiseHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = error("Unexpected push")
    }
}
