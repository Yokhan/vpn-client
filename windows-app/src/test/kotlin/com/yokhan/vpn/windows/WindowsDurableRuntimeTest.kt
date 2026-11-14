package com.yokhan.gates.windows

import com.google.gson.JsonParser
import java.io.File
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Flow
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsDurableRuntimeTest {
    @Test
    fun appConfigImportFallsBackFromPrimaryToCloakEdgeOn502() {
        val apiCandidates = windowsCandidateAppApiBases("https://app.yokhan.xyz/gates")
        assertEquals("https://app.yokhan.xyz/gates", apiCandidates.first())
        assertTrue("https://cloak.yokhan.xyz/gates" in apiCandidates)

        val visitedUrls = mutableListOf<String>()
        val http = FakeFallbackHttpClient { request, _ ->
            val url = request.uri().toString()
            visitedUrls += url
            when (request.uri().host) {
                "app.yokhan.xyz" -> 502 to """{"error":"bad_gateway"}"""
                "cloak.yokhan.xyz" -> 200 to PLAIN_PROFILE
                else -> 404 to "{}"
            }
        }
        val root = Files.createTempDirectory("gates-api-fallback-").toFile()
        root.resolve("auth-state.json").writeText(
            """{"issuer":"$GATES_AUTH_ISSUER","clientId":"${WindowsAppConfig().authClientId}","tokenEndpoint":"$GATES_AUTH_ISSUER/protocol/openid-connect/token","accessToken":"TEST_ACCESS_TOKEN","refreshToken":"TEST_REFRESH","expiresAtEpochSecond":${Instant.now().epochSecond + 3600}}""",
            StandardCharsets.UTF_8,
        )
        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = false,
            appDir = root,
            accessOnly = true,
            authHttpClient = http,
            serviceDataDir = root.resolve("svc"),
        )

        val result = kotlinx.coroutines.runBlocking { controller.run("import-app") }
        assertEquals(0, result.exitCode, result.output)
        assertTrue(visitedUrls.any { it.startsWith("https://app.yokhan.xyz/gates/api/app/singbox") })
        assertTrue(visitedUrls.any { it.startsWith("https://cloak.yokhan.xyz/gates/api/app/singbox") })
        assertTrue(root.resolve("runtime/account.singbox.json").isFile)
    }

    @Test
    fun xhttpRoutingChangeRollbackRestoresSidecarSpec() {
        val root = Files.createTempDirectory("gates-xhttp-rollback-").toFile()
        val fakeService = FakeXhttpService()
        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = true,
            appDir = root,
            runtimeService = fakeService,
            runtimeServiceInstalled = { true },
            prepareRuntimeService = { RuntimeCommandResult(0, "") },
            validateGeneratedConfig = { RuntimeCommandResult(0, "ok") },
            xrayPinProvider = { "a".repeat(64) },
        )
        controller.writeConfig(
            WindowsAppConfig(
                activeRoute = "xhttp",
                physicalBindEnabled = true,
                physicalBindInterface = "Wi-Fi",
                physicalBindIpv4 = "192.0.2.10",
                restoreSystemProxyOnStop = false,
                roomNetEnabled = false,
            ),
        )
        root.resolve("runtime").mkdirs()
        root.resolve("runtime/subscription.singbox.json").writeText(XHTTP_PROFILE, StandardCharsets.UTF_8)
        val gen = kotlinx.coroutines.runBlocking { controller.run("generate") }
        assertEquals(0, gen.exitCode, gen.output)
        assertTrue(root.resolve("runtime/xhttp-spec.json").isFile)

        val seededServiceConfig = root.resolve("runtime/config.json").readText(StandardCharsets.UTF_8)
        fakeService.liveConfig = seededServiceConfig
        fakeService.responses += RuntimeCommandResult(1, "IPC timeout")
        fakeService.responses += RuntimeCommandResult(0, "RESTARTED")

        val result = controller.addAppRouting("game.exe", proxy = true)
        assertEquals(1, result.exitCode, result.output)
        assertTrue(fakeService.started.size >= 2)
        val rollbackPayload = fakeService.started.last()
        assertTrue(rollbackPayload.contains("gates_xhttp"), "Rollback payload must restore gates_xhttp sidecar spec")
        assertTrue(controller.readConfig().appProxyProcessNames.isEmpty())
    }

    @Test
    fun probeRoutesIsNonDisruptiveWhenServiceTunnelRunning() {
        val root = Files.createTempDirectory("gates-probe-running-").toFile()
        val fakeService = FakeXhttpService().apply {
            state = "RUNNING"
        }
        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = true,
            appDir = root,
            runtimeService = fakeService,
            runtimeServiceInstalled = { true },
            prepareRuntimeService = { RuntimeCommandResult(0, "") },
            validateGeneratedConfig = { RuntimeCommandResult(0, "ok") },
        )
        controller.writeConfig(
            WindowsAppConfig(
                activeRoute = "bridge",
                appApiBaseUrl = "http://127.0.0.1:9/gates",
                physicalBindEnabled = false,
                restoreSystemProxyOnStop = false,
                roomNetEnabled = false,
            ),
        )
        root.resolve("runtime").mkdirs()
        root.resolve("runtime/subscription.singbox.json").writeText(PLAIN_PROFILE, StandardCharsets.UTF_8)

        val result = kotlinx.coroutines.runBlocking { controller.run("probe-routes") }
        assertEquals(0, result.exitCode, result.output)
        assertEquals(0, fakeService.stops, "Non-disruptive probe must not stop running GatesVpn service")
        assertTrue(result.output.contains("bridge"))
    }

    @Test
    fun diagnosticLogReaderTailsLargeServiceLogAndRedactsBearerSecrets() {
        val root = Files.createTempDirectory("gates-log-tail-").toFile()
        val serviceDir = root.resolve("service-data").apply { mkdirs() }
        val bigLog = serviceDir.resolve("service.log")
        bigLog.outputStream().buffered().use { out ->
            val filler = ("2026-11-13 INFO noisy-heartbeat line padding for bounded tail reading test\n")
                .toByteArray(StandardCharsets.UTF_8)
            var written = 0
            while (written < 3 * 1024 * 1024) {
                out.write(filler)
                written += filler.size
            }
            val tail = "2026-11-13 TAIL_MARKER Authorization: Bearer super-secret-token-12345\n"
                .toByteArray(StandardCharsets.UTF_8)
            out.write(tail)
        }

        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = false,
            appDir = root,
            serviceDataDir = serviceDir,
        )
        val diag = controller.readDiagnosticLogs("service")
        assertTrue(diag.length < 64_000, "Log tail should stay bounded, was ${diag.length}")
        assertTrue("TAIL_MARKER" in diag)
        assertFalse("super-secret-token-12345" in diag)
    }

    private class FakeFallbackHttpClient(
        private val respond: (HttpRequest, String) -> Pair<Int, String>,
    ) : HttpClient() {
        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.empty()
        override fun followRedirects() = Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters() = SSLParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
        override fun version() = Version.HTTP_1_1
        override fun executor(): Optional<Executor> = Optional.empty()

        override fun <T : Any?> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
            val body = StringBuilder()
            request.bodyPublisher().ifPresent { publisher ->
                publisher.subscribe(object : Flow.Subscriber<ByteBuffer> {
                    override fun onSubscribe(subscription: Flow.Subscription) {
                        subscription.request(Long.MAX_VALUE)
                    }

                    override fun onNext(buffer: ByteBuffer) {
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        body.append(bytes.toString(Charsets.UTF_8))
                    }

                    override fun onError(error: Throwable) {
                        throw error
                    }

                    override fun onComplete() {}
                })
            }
            val (status, response) = respond(request, body.toString())
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
        ): CompletableFuture<HttpResponse<T>> = error("Unexpected async request")

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            handler: HttpResponse.BodyHandler<T>,
            pushHandler: HttpResponse.PushPromiseHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = error("Unexpected push request")
    }

    private class FakeXhttpService : WindowsRuntimeControl {
        var state = "RUNNING"
        var liveConfig: String? = null
        val started = mutableListOf<String>()
        val responses = mutableListOf<RuntimeCommandResult>()
        var stops = 0

        override fun health() = RuntimeCommandResult(0, state)
        override fun protectionStatus() = RuntimeCommandResult(0, "DISABLED")
        override fun configurationDigest(): RuntimeCommandResult {
            val raw = liveConfig ?: return RuntimeCommandResult(0, "NONE")
            val stripped = JsonParser.parseString(raw).asJsonObject.apply {
                remove("gates_xhttp")
                remove("gates_protection")
            }.toString()
            return RuntimeCommandResult(0, runtimeConfigurationDigest(stripped))
        }

        override fun startTunnel(configJson: String): RuntimeCommandResult {
            started += configJson
            val next = if (responses.isEmpty()) RuntimeCommandResult(0, "RESTARTED") else responses.removeAt(0)
            if (next.exitCode == 0) liveConfig = configJson
            return next
        }

        override fun stop(): RuntimeCommandResult {
            stops += 1
            liveConfig = null
            return RuntimeCommandResult(0, "STOPPED")
        }
    }

    private companion object {
        const val UUID = "00000000-0000-4000-8000-000000000001"
        const val XHTTP_PROFILE = """{"inbounds":[{"type":"tun","tag":"tun-in"}],"outbounds":[
            {"type":"direct","tag":"direct"},
            {"type":"vless","tag":"xhttp","server":"203.0.113.10","server_port":443,
             "uuid":"$UUID","flow":"",
             "tls":{"enabled":true,"server_name":"entry.example.com",
               "reality":{"enabled":true,"public_key":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","short_id":"abcd"},
               "utls":{"enabled":true,"fingerprint":"chrome"}},
             "transport":{"type":"xhttp","host":"entry.example.com","path":"/lab","mode":"auto"}}
        ]}"""
        const val PLAIN_PROFILE = """{"inbounds":[{"type":"tun","tag":"tun-in"}],"outbounds":[
            {"type":"direct","tag":"direct"},
            {"type":"vless","tag":"bridge","server":"203.0.113.10","server_port":443,"uuid":"$UUID"}
        ]}"""
    }
}
