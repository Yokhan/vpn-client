package com.yokhan.gates.windows

import com.google.gson.JsonParser
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.file.Files
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import kotlin.test.*

class WindowsAuthorizationTest {
    @Test fun endpointPolicyRejectsUntrustedDestinations() {
        val issuer = GATES_AUTH_ISSUER
        assertEquals("$issuer/device?user_code=ABCD-EFGH", validatedWindowsAuthEndpoint(issuer, "$issuer/device?user_code=ABCD-EFGH"))
        for (url in listOf("http://auth.yokhan.xyz/realms/yokhan/token", "https://foreign.invalid/realms/yokhan/token",
            "https://user:secret@auth.yokhan.xyz/realms/yokhan/token", "$issuer/../other/token",
            "$issuer/%2e%2e/other/token", "$issuer/token#fragment", "https://auth.yokhan.xyz:444/realms/yokhan/token")) {
            assertFails { validatedWindowsAuthEndpoint(issuer, url) }
        }
    }

    @Test fun accessAcceptanceRejectsEveryRuntimeCommandBeforeCreatingState() = runBlocking {
        val root = Files.createTempDirectory("gates-access-deny-").toFile()
        val controller = windowsAccessAcceptanceController(root)
        for (command in listOf("init", "connect", "stop", "start-proxy", "generate", "check", "status", "test",
            "update", "check-update", "report-bug", "set-runtime", "set-route", "enable-system-proxy", "disable-system-proxy", "unknown")) {
            assertEquals(403, controller.run(command).exitCode, command)
        }
        assertFalse(root.resolve("app-config.json").exists())
    }

    @Test fun deviceLoginUsesPkceAndStoresNoRuntimeState() = runBlocking {
        windowsDpapiAvailable()
        val root = Files.createTempDirectory("gates-auth-success-").toFile()
        var opened = false
        var challenge = ""
        lateinit var controller: WindowsRuntimeController
        val http = ScriptedAuthClient { request, body ->
            when {
                request.uri().path.endsWith("openid-configuration") -> 200 to METADATA
                request.uri().path.endsWith("/auth/device") -> {
                    assertTrue(body.contains("code_challenge_method=S256"))
                    challenge = body.substringAfter("code_challenge=").substringBefore('&')
                    200 to DEVICE
                }
                request.uri().path.endsWith("/token") -> {
                    val verifier = body.substringAfter("code_verifier=").substringBefore('&')
                    val expected = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                        java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
                    assertEquals(challenge, expected)
                    200 to TOKENS
                }
                else -> {
                    assertEquals(WindowsAuthorizationPhase.ReceivingProfile, controller.authorizationPhase.value)
                    assertNull(controller.authorizationPrompt.value)
                    assertFalse(controller.cancelPendingAuthorization { error("Cancellation after commit") })
                    assertEquals("Bearer ACCESS_SENTINEL", request.headers().firstValue("Authorization").orElse(""))
                    200 to PROFILE
                }
            }
        }
        controller = WindowsRuntimeController(appDir = root, accessOnly = true, authHttpClient = http,
            authorizationBrowser = { opened = true; assertTrue(it.startsWith("$GATES_AUTH_ISSUER/device?")); false }, authorizationPause = {})
        val result = controller.run("login")
        assertEquals(0, result.exitCode)
        assertTrue(opened)
        assertTrue(controller.sessionState().signedIn)
        assertTrue(controller.sessionState().ready)
        assertNull(controller.authorizationPrompt.value)
        assertFalse(result.output.contains("SENTINEL"))
        assertFalse(result.output.contains("ABCD-EFGH"))
        assertFalse(root.resolve("auth-state.json").readText().contains("SENTINEL"))
        assertFalse(root.resolve("runtime/config.json").exists())
        assertFalse(root.resolve("runtime/process-state.json").exists())
    }

    @Test fun expiredSessionAndInvalidGrantAllowReauthenticationWithoutLosingProfile() = runBlocking {
        windowsDpapiAvailable()
        val root = Files.createTempDirectory("gates-auth-expired-").toFile()
        val http = ScriptedAuthClient { _, _ -> 400 to """{"error":"invalid_grant","description":"SECRET_SENTINEL"}""" }
        val controller = WindowsRuntimeController(appDir = root, accessOnly = true, authHttpClient = http)
        controller.storeImportedProfile("account", PROFILE)
        val profile = root.resolve("runtime/account.singbox.json").readBytes()
        fun seed(refresh: String) {
            root.resolve("auth-state.json").writeText("""{"issuer":"$GATES_AUTH_ISSUER","clientId":"${WindowsAppConfig().authClientId}","tokenEndpoint":"$GATES_AUTH_ISSUER/protocol/openid-connect/token","accessToken":"OLD_ACCESS","refreshToken":"$refresh","expiresAtEpochSecond":1}""")
        }
        seed("")
        assertFalse(controller.sessionState().signedIn)
        seed("OLD_REFRESH")
        assertTrue(controller.sessionState().signedIn)
        val failed = controller.run("import-app")
        assertEquals(401, failed.exitCode)
        assertFalse(failed.output.contains("SECRET_SENTINEL"))
        assertFalse(controller.sessionState().signedIn)
        assertTrue(controller.sessionState().ready)
        assertContentEquals(profile, root.resolve("runtime/account.singbox.json").readBytes())
    }

    @Test fun cancellationStopsPendingLoginWithoutSavingTokens() = runBlocking {
        val root = Files.createTempDirectory("gates-auth-cancel-").toFile()
        val waiting = CountDownLatch(1)
        val http = ScriptedAuthClient { request, _ -> 200 to if (request.uri().path.endsWith("openid-configuration")) METADATA else DEVICE }
        val controller = WindowsRuntimeController(appDir = root, accessOnly = true, authHttpClient = http,
            authorizationBrowser = { false }, authorizationPause = { waiting.countDown(); Thread.sleep(60_000) })
        val job = launch { controller.run("login") }
        assertTrue(withContext(Dispatchers.IO) { waiting.await(3, TimeUnit.SECONDS) })
        assertEquals("ABCD-EFGH", controller.authorizationPrompt.value?.userCode)
        val start = System.nanoTime()
        job.cancelAndJoin()
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1500)
        assertNull(controller.authorizationPrompt.value)
        assertFalse(root.resolve("auth-state.json").exists())
        assertFalse(controller.sessionState().ready)
    }

    @Test fun authorizationCodeLoopbackLoginUsesPkceAndHandlesCallback() = runBlocking {
        windowsDpapiAvailable()
        val root = Files.createTempDirectory("gates-auth-loopback-").toFile()
        var challenge = ""
        var seenRedirectUri = ""
        var openedAuthUri = ""
        lateinit var controller: WindowsRuntimeController
        val authMetadata = """{"issuer":"$GATES_AUTH_ISSUER","authorization_endpoint":"$GATES_AUTH_ISSUER/protocol/openid-connect/auth","device_authorization_endpoint":"$GATES_AUTH_ISSUER/protocol/openid-connect/auth/device","token_endpoint":"$GATES_AUTH_ISSUER/protocol/openid-connect/token"}"""
        val http = ScriptedAuthClient { request, body ->
            when {
                request.uri().path.endsWith("openid-configuration") -> 200 to authMetadata
                request.uri().path.endsWith("/token") -> {
                    assertTrue(body.contains("grant_type=authorization_code"))
                    assertTrue(body.contains("code=AUTH_CODE_OK"))
                    val verifier = body.substringAfter("code_verifier=").substringBefore('&')
                    val expected = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                        java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
                    )
                    assertEquals(challenge, expected)
                    val redirectParam = java.net.URLDecoder.decode(
                        body.substringAfter("redirect_uri=").substringBefore('&'),
                        Charsets.UTF_8,
                    )
                    assertEquals(seenRedirectUri, redirectParam)
                    200 to TOKENS
                }
                else -> 200 to PROFILE
            }
        }
        val callbackDispatched = CountDownLatch(1)
        controller = WindowsRuntimeController(
            appDir = root,
            accessOnly = true,
            authHttpClient = http,
            authorizationBrowser = { authUrl ->
                openedAuthUri = authUrl
                val uri = URI.create(authUrl)
                val query = uri.rawQuery.split('&').associate {
                    val i = it.indexOf('=')
                    java.net.URLDecoder.decode(it.substring(0, i), Charsets.UTF_8) to
                        java.net.URLDecoder.decode(it.substring(i + 1), Charsets.UTF_8)
                }
                challenge = query.getValue("code_challenge")
                seenRedirectUri = query.getValue("redirect_uri")
                val state = query.getValue("state")
                assertEquals("code", query["response_type"])
                assertEquals("S256", query["code_challenge_method"])
                assertTrue(seenRedirectUri.startsWith("http://127.0.0.1:"))
                Thread {
                    val client = HttpClient.newHttpClient()
                    val bad = client.send(
                        HttpRequest.newBuilder(URI.create("$seenRedirectUri?code=BAD&state=wrong-state")).GET().build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                    assertEquals(400, bad.statusCode())
                    val ok = client.send(
                        HttpRequest.newBuilder(URI.create("$seenRedirectUri?code=AUTH_CODE_OK&state=$state")).GET().build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                    assertEquals(200, ok.statusCode())
                    callbackDispatched.countDown()
                }.start()
                true
            },
            authorizationPause = { Thread.sleep(25) },
        )
        val result = withContext(Dispatchers.IO) { controller.run("login") }
        assertTrue(callbackDispatched.await(5, TimeUnit.SECONDS))
        assertEquals(0, result.exitCode, result.output)
        assertTrue(openedAuthUri.contains("/protocol/openid-connect/auth?"))
        assertTrue(controller.sessionState().signedIn)
        assertTrue(controller.sessionState().ready)
        assertNull(controller.authorizationPrompt.value)
    }

    @Test fun cancellationStopsLoopbackLoginWithoutSavingTokens() = runBlocking {
        val root = Files.createTempDirectory("gates-auth-loopback-cancel-").toFile()
        val waiting = CountDownLatch(1)
        val authMetadata = """{"issuer":"$GATES_AUTH_ISSUER","authorization_endpoint":"$GATES_AUTH_ISSUER/protocol/openid-connect/auth","token_endpoint":"$GATES_AUTH_ISSUER/protocol/openid-connect/token"}"""
        val http = ScriptedAuthClient { _, _ -> 200 to authMetadata }
        val controller = WindowsRuntimeController(
            appDir = root,
            accessOnly = true,
            authHttpClient = http,
            authorizationBrowser = { waiting.countDown(); false },
            authorizationPause = { Thread.sleep(50) },
        )
        val job = launch(Dispatchers.IO) { controller.run("login") }
        assertTrue(withContext(Dispatchers.IO) { waiting.await(3, TimeUnit.SECONDS) })
        assertEquals("", controller.authorizationPrompt.value?.userCode)
        val start = System.nanoTime()
        job.cancelAndJoin()
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1500)
        assertNull(controller.authorizationPrompt.value)
        assertFalse(root.resolve("auth-state.json").exists())
    }

    private fun windowsDpapiAvailable() = assumeTrue(System.getProperty("os.name").startsWith("Windows"))

    companion object {
        private const val METADATA = """{"issuer":"$GATES_AUTH_ISSUER","device_authorization_endpoint":"$GATES_AUTH_ISSUER/protocol/openid-connect/auth/device","token_endpoint":"$GATES_AUTH_ISSUER/protocol/openid-connect/token"}"""
        private const val DEVICE = """{"device_code":"DEVICE_SENTINEL","user_code":"ABCD-EFGH","verification_uri":"$GATES_AUTH_ISSUER/device?user_code=ABCD-EFGH","expires_in":600,"interval":2}"""
        private const val TOKENS = """{"access_token":"ACCESS_SENTINEL","refresh_token":"REFRESH_SENTINEL","expires_in":300}"""
        private const val PROFILE = """{"outbounds":[{"type":"vless","tag":"nl-tcp","server":"example.invalid","server_port":443}]}"""
    }
}

private class ScriptedAuthClient(private val respond: (HttpRequest, String) -> Pair<Int, String>) : HttpClient() {
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
        request.bodyPublisher().ifPresent { publisher -> publisher.subscribe(object : Flow.Subscriber<ByteBuffer> {
            override fun onSubscribe(subscription: Flow.Subscription) { subscription.request(Long.MAX_VALUE) }
            override fun onNext(buffer: ByteBuffer) { val bytes = ByteArray(buffer.remaining()); buffer.get(bytes); body.append(bytes.toString(Charsets.UTF_8)) }
            override fun onError(error: Throwable) { throw error }
            override fun onComplete() {}
        }) }
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
    override fun <T : Any?> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): CompletableFuture<HttpResponse<T>> = error("Unexpected async request")
    override fun <T : Any?> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>, push: HttpResponse.PushPromiseHandler<T>): CompletableFuture<HttpResponse<T>> = error("Unexpected push request")
}
