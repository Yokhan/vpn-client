package com.yokhan.gates.windows

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
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
import java.util.Base64
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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsAccessRecoveryTest {
    @Test
    fun tokenSuccessThenUnlinkedProfileKeepsAuthenticatedAndRetryImportsWithoutRuntimeChanges() = runBlocking {
        windowsDpapiAvailable()
        val root = Files.createTempDirectory("gates-auth-recovery-").toFile()
        var approved = false
        var meCalls = 0
        val http = RecoveryHttpClient { request, _ ->
            when {
                request.uri().path.endsWith("openid-configuration") -> 200 to METADATA
                request.uri().path.endsWith("/auth/device") -> 200 to DEVICE
                request.uri().path.endsWith("/token") -> 200 to TOKENS
                request.uri().path.endsWith("/api/app/singbox") ->
                    if (approved) 200 to PROFILE else 403 to ERROR_UNLINKED
                request.uri().path.endsWith("/api/app/me") -> {
                    meCalls += 1
                    403 to ERROR_UNLINKED
                }
                else -> error("Unexpected request ${request.uri()}")
            }
        }
        val controller = controller(root, http)

        val failed = controller.run("login")

        assertEquals(403, failed.exitCode)
        assertEquals(WindowsAccessStage.PROFILE, failed.accessFailure?.stage)
        assertEquals(WindowsAccessCode.ACCESS_NOT_LINKED, failed.accessFailure?.code)
        assertFalse(failed.accessPending)
        assertTrue(controller.sessionState().signedIn)
        assertFalse(controller.sessionState().ready)
        assertEquals(1, meCalls)
        assertFalse(failed.output.contains("ACCESS_SENTINEL"))
        assertFalse(root.resolve("runtime/account.singbox.json").exists())
        assertFalse(root.resolve("runtime/config.json").exists())
        assertFalse(root.resolve("runtime/process-state.json").exists())

        approved = true
        val retried = controller.run("import-app")

        assertEquals(0, retried.exitCode)
        assertNull(retried.accessFailure)
        assertTrue(controller.sessionState().signedIn)
        assertTrue(controller.sessionState().ready)
        assertEquals(PROFILE, root.resolve("runtime/account.singbox.json").readText())
        assertEquals(1, meCalls)
    }

    @Test
    fun pendingRequestIsWaitingAndAccessRequestIsSentOnlyByExplicitCommand() = runBlocking {
        windowsDpapiAvailable()
        val root = Files.createTempDirectory("gates-auth-pending-").toFile()
        var approved = false
        var accessRequestPosts = 0
        var accessRequestBody = ""
        val http = RecoveryHttpClient { request, body ->
            when {
                request.uri().path.endsWith("openid-configuration") -> 200 to METADATA
                request.uri().path.endsWith("/auth/device") -> 200 to DEVICE
                request.uri().path.endsWith("/token") -> 200 to TOKENS
                request.uri().path.endsWith("/api/app/singbox") ->
                    if (approved) 200 to PROFILE else 403 to ERROR_UNLINKED
                request.uri().path.endsWith("/api/app/me") -> 200 to """{"status":"pending","request_id":"REQUEST_SENTINEL"}"""
                request.uri().path.endsWith("/api/app/access-request") -> {
                    accessRequestPosts += 1
                    accessRequestBody = body
                    201 to """{"request_id":"REQUEST_SENTINEL","status":"pending","result":"created"}"""
                }
                else -> error("Unexpected request ${request.uri()}")
            }
        }
        val controller = controller(root, http)

        val pending = controller.run("login")

        assertEquals(403, pending.exitCode)
        assertEquals(WindowsAccessCode.ACCESS_PENDING, pending.accessFailure?.code)
        assertTrue(pending.accessPending)
        assertEquals(0, accessRequestPosts)

        val requested = controller.run("access-request")

        assertEquals(0, requested.exitCode)
        assertTrue(requested.accessPending)
        assertEquals(1, accessRequestPosts)
        assertTrue(accessRequestBody.contains("\"device\""))
        assertTrue(accessRequestBody.contains("\"main\""))
        assertFalse(accessRequestBody.contains("note"))

        val rechecked = controller.run("import-app")

        assertEquals(403, rechecked.exitCode)
        assertEquals(WindowsAccessCode.ACCESS_PENDING, rechecked.accessFailure?.code)
        assertTrue(rechecked.accessPending)
        assertEquals(1, accessRequestPosts)

        approved = true
        val imported = controller.run("import-app")

        assertEquals(0, imported.exitCode)
        assertTrue(controller.sessionState().ready)
        assertEquals(1, accessRequestPosts)
        assertEquals(PROFILE, root.resolve("runtime/account.singbox.json").readText())
    }

    @Test
    fun deviceRouteRateLimitServerAndTransportFailuresStayBoundedAndKeepProfile() = runBlocking {
        windowsDpapiAvailable()
        val root = Files.createTempDirectory("gates-auth-failures-").toFile()
        lateinit var response: () -> Pair<Int, String>
        val http = RecoveryHttpClient { request, _ ->
            when {
                request.uri().path.endsWith("/api/app/singbox") -> response()
                else -> error("Unexpected request ${request.uri()}")
            }
        }
        val controller = controller(root, http)
        seedAuth(root)
        controller.storeImportedProfile("account", PROFILE)
        val profileFile = root.resolve("runtime/account.singbox.json")
        val settingsFile = root.resolve("app-config.json")
        val generatedFile = root.resolve("runtime/config.json")
        val processFile = root.resolve("runtime/process-state.json")
        generatedFile.writeText("""{"sentinel":"generated"}""")
        processFile.writeText("""{"sentinel":"runtime"}""")
        val profileBefore = profileFile.readBytes()
        val settingsBefore = settingsFile.readBytes()
        val generatedBefore = generatedFile.readBytes()
        val processBefore = processFile.readBytes()

        response = { 404 to ERROR_DEVICE }
        val device = controller.run("import-app")
        assertEquals(WindowsAccessCode.DEVICE_NOT_FOUND, device.accessFailure?.code)
        assertEquals(404, device.accessFailure?.httpStatus)

        response = { 409 to ERROR_ROUTE }
        val route = controller.run("import-app")
        assertEquals(WindowsAccessCode.APP_ROUTE_UNAVAILABLE, route.accessFailure?.code)
        assertEquals(409, route.accessFailure?.httpStatus)

        response = { 429 to """{"error":"rate limited"}""" }
        val limited = controller.run("import-app")
        assertEquals(WindowsAccessCode.RATE_LIMITED, limited.accessFailure?.code)
        assertEquals(429, limited.accessFailure?.httpStatus)

        response = { 500 to """{"error":"SECRET_SERVER_DETAIL","code":"GATES_APP_ROUTE_UNAVAILABLE"}""" }
        val server = controller.run("import-app")
        assertEquals(WindowsAccessCode.APP_ROUTE_UNAVAILABLE, server.accessFailure?.code)
        assertFalse(server.output.contains("SECRET_SERVER_DETAIL"))

        response = { throw IOException("SECRET_TRANSPORT_DETAIL") }
        val transport = controller.run("import-app")
        assertEquals(WindowsAccessCode.TRANSPORT_ERROR, transport.accessFailure?.code)
        assertFalse(transport.output.contains("SECRET_TRANSPORT_DETAIL"))

        assertContentEquals(profileBefore, profileFile.readBytes())
        assertContentEquals(settingsBefore, settingsFile.readBytes())
        assertContentEquals(generatedBefore, generatedFile.readBytes())
        assertContentEquals(processBefore, processFile.readBytes())
        assertTrue(controller.sessionState().ready)

        response = { 200 to PROFILE }
        assertEquals(0, controller.run("import-app").exitCode)
        assertTrue(controller.sessionState().ready)
        assertContentEquals(generatedBefore, generatedFile.readBytes())
        assertContentEquals(processBefore, processFile.readBytes())
    }

    @Test
    fun freshLoginWhileSignedInStartsDeviceAuthorizationAgain() = runBlocking {
        windowsDpapiAvailable()
        val root = Files.createTempDirectory("gates-auth-fresh-login-").toFile()
        var deviceCalls = 0
        var tokenCalls = 0
        val http = RecoveryHttpClient { request, _ ->
            when {
                request.uri().path.endsWith("openid-configuration") -> 200 to METADATA
                request.uri().path.endsWith("/auth/device") -> {
                    deviceCalls += 1
                    200 to DEVICE
                }
                request.uri().path.endsWith("/token") -> {
                    tokenCalls += 1
                    200 to TOKENS
                }
                request.uri().path.endsWith("/api/app/singbox") -> 200 to PROFILE
                else -> error("Unexpected request ${request.uri()}")
            }
        }
        val controller = controller(root, http)
        seedAuth(root)

        val result = controller.run("login")

        assertEquals(0, result.exitCode)
        assertEquals(1, deviceCalls)
        assertEquals(1, tokenCalls)
        assertTrue(controller.sessionState().signedIn)
        assertTrue(controller.sessionState().ready)
        assertEquals(PROFILE, root.resolve("runtime/account.singbox.json").readText())
        assertEquals(WINDOWS_ACCESS_RESTART_COMMAND, "login")
        assertEquals(WINDOWS_ACCESS_RETRY_COMMAND, "import-app")
        assertEquals("login", windowsAccessPrimaryCommand(false))
        assertEquals("import-app", windowsAccessPrimaryCommand(true))
        assertEquals("login", windowsAccessCommand(signedIn = true, forceLogin = true))
        assertEquals("import-app", windowsAccessCommand(signedIn = true, forceLogin = false))
        assertEquals("login", windowsAccessCommand(signedIn = false, forceLogin = false))
    }

    @Test
    fun trustedUserInfoProvidesDisplayLabelWithoutLoggingOrBlockingAccess() = runBlocking {
        windowsDpapiAvailable()
        val root = Files.createTempDirectory("gates-auth-label-").toFile()
        var userInfoAuthorization = ""
        val http = RecoveryHttpClient { request, _ ->
            when {
                request.uri().path.endsWith("openid-configuration") -> 200 to METADATA_WITH_USERINFO
                request.uri().path.endsWith("/auth/device") -> 200 to DEVICE
                request.uri().path.endsWith("/token") -> 200 to tokens("sub-marat")
                request.uri().path.endsWith("/userinfo") -> {
                    userInfoAuthorization = request.headers().firstValue("Authorization").orElse("")
                    200 to """{"sub":"sub-marat","preferred_username":"marat","name":"Marat Igorevich"}"""
                }
                request.uri().path.endsWith("/api/app/singbox") -> 200 to PROFILE
                else -> error("Unexpected request ${request.uri()}")
            }
        }
        val controller = controller(root, http)

        val result = controller.run("login")

        assertEquals(0, result.exitCode)
        assertEquals("marat (Marat Igorevich)", controller.sessionState().accountLabel)
        assertTrue(userInfoAuthorization.startsWith("Bearer "))
        assertFalse(result.output.contains("Marat"))
        assertFalse(root.resolve("auth-state.json").readText().contains("Marat"))
    }

    @Test
    fun mismatchedUserInfoSubjectIsIgnoredAndAccessStillSucceeds() = runBlocking {
        windowsDpapiAvailable()
        val root = Files.createTempDirectory("gates-auth-label-mismatch-").toFile()
        val http = RecoveryHttpClient { request, _ ->
            when {
                request.uri().path.endsWith("openid-configuration") -> 200 to METADATA_WITH_USERINFO
                request.uri().path.endsWith("/auth/device") -> 200 to DEVICE
                request.uri().path.endsWith("/token") -> 200 to tokens("sub-marat")
                request.uri().path.endsWith("/userinfo") -> 200 to """{"sub":"sub-other","name":"Wrong Person"}"""
                request.uri().path.endsWith("/api/app/singbox") -> 200 to PROFILE
                else -> error("Unexpected request ${request.uri()}")
            }
        }
        val controller = controller(root, http)

        val result = controller.run("login")

        assertEquals(0, result.exitCode)
        assertEquals("", controller.sessionState().accountLabel)
        assertTrue(controller.sessionState().ready)
    }

    @Test
    fun nameOnlyOrMalformedUserInfoNeverInventsIdentityAndAccessStillSucceeds() = runBlocking {
        windowsDpapiAvailable()
        val userInfoBodies = listOf(
            """{"sub":"sub-marat","name":"Marat"}""",
            """{"sub":"sub-marat","preferred_username":{"unexpected":true}}""",
        )
        for (body in userInfoBodies) {
            val root = Files.createTempDirectory("gates-auth-label-optional-").toFile()
            val http = RecoveryHttpClient { request, _ ->
                when {
                    request.uri().path.endsWith("openid-configuration") -> 200 to METADATA_WITH_USERINFO
                    request.uri().path.endsWith("/auth/device") -> 200 to DEVICE
                    request.uri().path.endsWith("/token") -> 200 to tokens("sub-marat")
                    request.uri().path.endsWith("/userinfo") -> 200 to body
                    request.uri().path.endsWith("/api/app/singbox") -> 200 to PROFILE
                    else -> error("Unexpected request ${request.uri()}")
                }
            }
            val controller = controller(root, http)

            val result = controller.run("login")

            assertEquals(0, result.exitCode)
            assertEquals("", controller.sessionState().accountLabel)
            assertTrue(controller.sessionState().ready)
        }
    }

    @Test
    fun freshLoginForDifferentSubjectDoesNotCarryPreviousRefreshToken() = runBlocking {
        windowsDpapiAvailable()
        val root = Files.createTempDirectory("gates-auth-account-switch-").toFile()
        seedAuth(root, subject = "sub-old", refresh = "OLD_REFRESH_SECRET")
        val http = RecoveryHttpClient { request, _ ->
            when {
                request.uri().path.endsWith("openid-configuration") -> 200 to METADATA
                request.uri().path.endsWith("/auth/device") -> 200 to DEVICE
                request.uri().path.endsWith("/token") -> 200 to tokensWithoutRefresh("sub-new")
                request.uri().path.endsWith("/api/app/singbox") -> 200 to PROFILE
                else -> error("Unexpected request ${request.uri()}")
            }
        }
        val controller = controller(root, http)

        assertEquals(0, controller.run("login").exitCode)

        val stored = WindowsEncryptedJsonStore(root.resolve("auth-state.json")).read()!!.json
        val auth = JsonParser.parseString(stored).asJsonObject
        assertEquals("sub-new", auth.get("accountSubject").asString)
        assertEquals("", auth.get("refreshToken").asString)
        assertFalse(stored.contains("OLD_REFRESH_SECRET"))
    }

    @Test
    fun forcedLoginThroughUiWrapperResetsBusyOnUnlinkedFailureAndKeepsOldProfile() = runBlocking {
        windowsDpapiAvailable()
        val root = Files.createTempDirectory("gates-auth-ui-recovery-").toFile()
        var deviceCalls = 0
        val http = RecoveryHttpClient { request, _ ->
            when {
                request.uri().path.endsWith("openid-configuration") -> 200 to METADATA
                request.uri().path.endsWith("/auth/device") -> {
                    deviceCalls += 1
                    200 to DEVICE
                }
                request.uri().path.endsWith("/token") -> 200 to TOKENS
                request.uri().path.endsWith("/api/app/singbox") -> 403 to ERROR_UNLINKED
                request.uri().path.endsWith("/api/app/me") -> 403 to ERROR_UNLINKED
                else -> error("Unexpected request ${request.uri()}")
            }
        }
        val controller = controller(root, http)
        seedAuth(root)
        controller.storeImportedProfile("account", PROFILE)
        val profileFile = root.resolve("runtime/account.singbox.json")
        val before = profileFile.readBytes()
        val busyChanges = mutableListOf<Boolean>()
        var message = ""

        runWindowsUiAction(busyChanges::add, { message = it }) {
            val result = controller.run(WINDOWS_ACCESS_RESTART_COMMAND)
            message = windowsUiResultMessage(WINDOWS_ACCESS_RESTART_COMMAND, result)
        }

        assertEquals(1, deviceCalls)
        assertEquals(listOf(true, false), busyChanges)
        assertTrue(message.contains("не привязан"))
        assertContentEquals(before, profileFile.readBytes())
        assertTrue(controller.sessionState().signedIn)
        assertTrue(controller.sessionState().ready)
    }

    @Test
    fun cancelledForcedLoginThroughUiWrapperResetsBusyAndKeepsProfile() = runBlocking {
        windowsDpapiAvailable()
        val root = Files.createTempDirectory("gates-auth-ui-cancel-").toFile()
        seedAuth(root)
        val waiting = CountDownLatch(1)
        val http = RecoveryHttpClient { request, _ ->
            when {
                request.uri().path.endsWith("openid-configuration") -> 200 to METADATA
                request.uri().path.endsWith("/auth/device") -> 200 to DEVICE
                else -> error("Unexpected request ${request.uri()}")
            }
        }
        val controller = WindowsRuntimeController(
            appDir = root,
            accessOnly = true,
            authHttpClient = http,
            authorizationBrowser = { false },
            authorizationPause = {
                waiting.countDown()
                Thread.sleep(60_000)
            },
        )
        controller.storeImportedProfile("account", PROFILE)
        val profileFile = root.resolve("runtime/account.singbox.json")
        val before = profileFile.readBytes()
        val busyChanges = mutableListOf<Boolean>()

        val job = launch {
            runWindowsUiAction(busyChanges::add, {}) {
                controller.run(WINDOWS_ACCESS_RESTART_COMMAND)
            }
        }
        assertTrue(withContext(Dispatchers.IO) { waiting.await(3, TimeUnit.SECONDS) })
        job.cancelAndJoin()

        assertEquals(listOf(true, false), busyChanges)
        assertContentEquals(before, profileFile.readBytes())
        assertNull(controller.authorizationPrompt.value)
        assertTrue(controller.sessionState().signedIn)
    }

    @Test
    fun userInfoTimeoutDoesNotBlockAccessSetupOrKeepBusy() = runBlocking {
        windowsDpapiAvailable()
        val root = Files.createTempDirectory("gates-auth-label-timeout-").toFile()
        val http = RecoveryHttpClient(
            respond = { request, _ ->
                when {
                    request.uri().path.endsWith("openid-configuration") -> 200 to METADATA_WITH_USERINFO
                    request.uri().path.endsWith("/auth/device") -> 200 to DEVICE
                    request.uri().path.endsWith("/token") -> 200 to tokens("sub-marat")
                    request.uri().path.endsWith("/api/app/singbox") -> 200 to PROFILE
                    else -> error("Unexpected request ${request.uri()}")
                }
            },
            hangUserInfo = true,
        )
        val controller = controller(root, http)
        val busyChanges = mutableListOf<Boolean>()
        var message = ""
        val started = System.nanoTime()

        runWindowsUiAction(busyChanges::add, { message = it }) {
            val result = controller.run(WINDOWS_ACCESS_RESTART_COMMAND)
            message = windowsUiResultMessage(WINDOWS_ACCESS_RESTART_COMMAND, result)
        }

        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals(listOf(true, false), busyChanges)
        assertTrue(message.isNotBlank())
        assertTrue(elapsedMillis < 6_000)
        assertEquals("", controller.sessionState().accountLabel)
        assertTrue(controller.sessionState().ready)
    }

    private fun controller(root: File, http: HttpClient): WindowsRuntimeController =
        WindowsRuntimeController(
            appDir = root,
            accessOnly = true,
            authHttpClient = http,
            authorizationBrowser = { false },
            authorizationPause = {},
        )

    private fun seedAuth(root: File, subject: String? = null, refresh: String = "SEEDED_REFRESH") {
        root.mkdirs()
        val state = JsonObject().apply {
            addProperty("issuer", GATES_AUTH_ISSUER)
            addProperty("clientId", WindowsAppConfig().authClientId)
            addProperty("tokenEndpoint", "$GATES_AUTH_ISSUER/protocol/openid-connect/token")
            addProperty("accessToken", "SEEDED_ACCESS")
            addProperty("refreshToken", refresh)
            addProperty("expiresAtEpochSecond", Instant.now().epochSecond + 3600)
            subject?.let { addProperty("accountSubject", it) }
        }
        root.resolve("auth-state.json").writeText(state.toString())
    }

    private fun windowsDpapiAvailable() = assumeTrue(System.getProperty("os.name").startsWith("Windows"))

    companion object {
        private const val ISSUER = GATES_AUTH_ISSUER
        private const val METADATA =
            """{"issuer":"$ISSUER","device_authorization_endpoint":"$ISSUER/protocol/openid-connect/auth/device","token_endpoint":"$ISSUER/protocol/openid-connect/token"}"""
        private const val METADATA_WITH_USERINFO =
            """{"issuer":"$ISSUER","device_authorization_endpoint":"$ISSUER/protocol/openid-connect/auth/device","token_endpoint":"$ISSUER/protocol/openid-connect/token","userinfo_endpoint":"$ISSUER/protocol/openid-connect/userinfo"}"""
        private const val DEVICE =
            """{"device_code":"DEVICE_SENTINEL","user_code":"ABCD-EFGH","verification_uri":"$ISSUER/device?user_code=ABCD-EFGH","expires_in":600,"interval":2}"""
        private const val PROFILE =
            """{"outbounds":[{"type":"vless","tag":"nl-tcp","server":"example.invalid","server_port":443}]}"""
        private const val ERROR_UNLINKED =
            """{"error":"access not linked","code":"GATES_ACCESS_NOT_LINKED"}"""
        private const val ERROR_DEVICE =
            """{"error":"device not found","code":"GATES_DEVICE_NOT_FOUND"}"""
        private const val ERROR_ROUTE =
            """{"error":"route unavailable","code":"GATES_APP_ROUTE_UNAVAILABLE"}"""
        private val TOKENS =
            """{"access_token":"${jwt("sub-marat")}","id_token":"${jwt("sub-marat")}","refresh_token":"REFRESH_SENTINEL","expires_in":300}"""

        private fun jwt(subject: String): String {
            val payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("""{"sub":"$subject"}""".toByteArray(StandardCharsets.UTF_8))
            return "header.$payload.signature"
        }

        private fun tokens(subject: String): String {
            val token = jwt(subject)
            return """{"access_token":"$token","id_token":"$token","refresh_token":"REFRESH_SENTINEL","expires_in":300}"""
        }

        private fun tokensWithoutRefresh(subject: String): String {
            val token = jwt(subject)
            return """{"access_token":"$token","id_token":"$token","expires_in":300}"""
        }
    }
}

private class RecoveryHttpClient(
    private val hangUserInfo: Boolean = false,
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
                    body.append(bytes.toString(StandardCharsets.UTF_8))
                }

                override fun onError(error: Throwable) {
                    throw error
                }

                override fun onComplete() = Unit
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
    ): CompletableFuture<HttpResponse<T>> = try {
        if (hangUserInfo && request.uri().path.endsWith("/userinfo")) {
            CompletableFuture<HttpResponse<T>>()
        } else {
            CompletableFuture.completedFuture(send(request, handler))
        }
    } catch (error: Exception) {
        CompletableFuture.failedFuture(error)
    }

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
        push: HttpResponse.PushPromiseHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = error("Unexpected push request")
}
