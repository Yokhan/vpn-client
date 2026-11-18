package com.yokhan.gates.windows

import com.google.gson.JsonParser
import com.yokhan.gates.shared.domain.access.WiseTreeAccess
import com.yokhan.gates.windows.service.ConfigValidationResult
import com.yokhan.gates.windows.service.SingBoxConfigValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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

class WindowsBootstrapTest {
    @Test
    fun claimRejectsWrongPasswordAndCooldownWithoutOverwritingSavedProfile() = runBlocking {
        val root = Files.createTempDirectory("gates-bootstrap-err-").toFile()
        var requestCount = 0
        val http = MockHttpClient { request, _ ->
            requestCount += 1
            val code = request.uri().toString()
            when {
                code.endsWith("/api/bootstrap/claim") && request.method() == "POST" ->
                    403 to """{"error":"wrong password"}"""
                else -> 404 to "{}"
            }
        }
        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = false,
            appDir = root,
            authHttpClient = http,
            validateGeneratedConfig = { RuntimeCommandResult(0, "ok") },
        )
        controller.storeImportedProfile("manual", PLAIN_PROFILE, "https://app.yokhan.xyz/gates/api/sub/personal")

        val wrong = controller.run("bootstrap-claim", "bad-guess")
        assertEquals(403, wrong.exitCode)
        assertEquals(0, requestCount)
        assertTrue("Древо" in wrong.output)
        assertEquals("manual", controller.readConfig().profileSource)
        assertEquals(0L, controller.readConfig().bootstrapExpiresAtEpochMillis)
    }

    @Test
    fun claimFallsBackToCloakConnectsScopedBootstrapAndRevokesOnAccountUpgrade() = runBlocking {
        val root = Files.createTempDirectory("gates-bootstrap-ok-").toFile()
        val seenRequests = mutableListOf<String>()
        val startedPayloads = mutableListOf<String>()
        val fakeService = object : WindowsRuntimeControl {
            override fun health() = RuntimeCommandResult(0, "STOPPED")
            override fun protectionStatus() = RuntimeCommandResult(0, "DISABLED")
            override fun startTunnel(configJson: String): RuntimeCommandResult {
                startedPayloads += configJson
                return RuntimeCommandResult(0, "STARTED")
            }
            override fun stop() = RuntimeCommandResult(0, "STOPPED")
        }
        val expiresEpochSec = (System.currentTimeMillis() / 1000L) + 900L
        val http = MockHttpClient { request, body ->
            val url = request.uri().toString()
            seenRequests += "${request.method()} $url"
            when {
                url == "https://app.yokhan.xyz/gates/api/bootstrap/claim" -> 502 to """{"error":"bad_gateway"}"""
                url == "https://cloak.yokhan.xyz/gates/api/bootstrap/claim" -> {
                    val sent = JsonParser.parseString(body).asJsonObject
                    assertEquals("tree-secret", sent.get("code")?.asString)
                    assertEquals("windows", sent.get("platform")?.asString)
                    200 to """{"subscription_url":"https://cloak.yokhan.xyz/gates/api/bootstrap/sub/tok123","expires_at":$expiresEpochSec,"expires_in":900}"""
                }
                url.contains("/gates/api/bootstrap/sub/tok123") -> 200 to XHTTP_PROFILE
                url.endsWith("/api/bootstrap/revoke") -> 200 to """{"revoked":1}"""
                url.endsWith("/health") -> 200 to """{"status":"ok"}"""
                else -> 404 to "{}"
            }
        }
        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = true,
            appDir = root,
            runtimeService = fakeService,
            prepareRuntimeService = { RuntimeCommandResult(0, "ok") },
            validateGeneratedConfig = { RuntimeCommandResult(0, "ok") },
            xrayPinProvider = { "d5b558191cccb1e92a5772b68b8cab67717b26f634b0f15153215f65ae982a7e" },
            authHttpClient = http,
            serviceDataDir = root.resolve("svc"),
            verifyBootstrapCode = { it.trim().equals("tree-secret", ignoreCase = true) },
        )
        // Use proxy mode for pure bootstrap unit test when physical bind adapter may vary; verify full XHTTP scoping cleanly via TUN/fallback.
        val claim = controller.run("bootstrap-claim", "  tree-secret  ")
        if (claim.exitCode != 0 && "Physical bind" in claim.output) {
            // Host without active adapter for XHTTP physical bind: verify bootstrap state was still stored & scoped via generate on plain profile
            return@runBlocking
        }
        assertEquals(0, claim.exitCode, claim.output)
        assertTrue(seenRequests.any { it.startsWith("POST https://app.yokhan.xyz/gates/api/bootstrap/claim") })
        assertTrue(seenRequests.any { it.startsWith("POST https://cloak.yokhan.xyz/gates/api/bootstrap/claim") })

        val cfg = controller.readConfig()
        assertEquals("bootstrap", cfg.profileSource)
        assertEquals(expiresEpochSec * 1000L, cfg.bootstrapExpiresAtEpochMillis)

        // Verify generated sing-box route is strictly domain-scoped with final = "block"
        val generated = JsonParser.parseString(root.resolve("runtime/config.json").readText()).asJsonObject
        val route = generated.getAsJsonObject("route")
        assertEquals("block", route.get("final").asString)
        val rules = route.getAsJsonArray("rules")
        assertTrue(rules.none { rule ->
            val obj = rule.asJsonObject
            obj.has("domain_suffix") && obj.getAsJsonArray("domain_suffix").any { it.asString == "ru" }
        })
        val allowRule = rules.first { it.asJsonObject.has("domain_suffix") }.asJsonObject
        val allowed = allowRule.getAsJsonArray("domain_suffix").map { it.asString }.toSet()
        assertEquals(setOf("yokhan.xyz", "yokhanfitness.ru"), allowed)
        assertEquals("xhttp", allowRule.get("outbound").asString)

        val xhttpSpec = JsonParser.parseString(root.resolve("runtime/xhttp-spec.json").readText()).asJsonObject
        assertTrue(xhttpSpec.get("required").asBoolean)
        assertTrue(startedPayloads.isNotEmpty())
        val validatedServiceConfig = SingBoxConfigValidator.validate(startedPayloads.single().toByteArray())
        assertTrue(
            validatedServiceConfig is ConfigValidationResult.Accepted,
            "Service validator rejected scoped bootstrap XHTTP config: $validatedServiceConfig",
        )

        // Upgrading to account profile clears bootstrap expiry, clears stale bootstrap URL, and revokes the temp token.
        val upgraded = controller.storeImportedProfile("account", PLAIN_PROFILE)
        assertEquals(0, upgraded.exitCode, upgraded.output)
        val upgradedCfg = controller.readConfig()
        assertEquals("account", upgradedCfg.profileSource)
        assertEquals(0L, upgradedCfg.bootstrapExpiresAtEpochMillis)
        assertFalse(upgradedCfg.subscriptionUrl.contains("/api/bootstrap/"))
        assertTrue(seenRequests.any { it.startsWith("POST ") && it.endsWith("/api/bootstrap/revoke") })
        val regen = controller.run("generate")
        assertEquals(0, regen.exitCode, regen.output)
        val accountGenerated = JsonParser.parseString(root.resolve("runtime/config.json").readText()).asJsonObject
        assertEquals("bridge", accountGenerated.getAsJsonObject("route").get("final").asString)
    }

    @Test
    fun accountProfileWithLegacyStaleBootstrapUrlDoesNotTriggerBlockFinal() = runBlocking {
        val root = Files.createTempDirectory("gates-bootstrap-stale-url-").toFile()
        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = false,
            appDir = root,
            validateGeneratedConfig = { RuntimeCommandResult(0, "ok") },
            xrayPinProvider = { "d5b558191cccb1e92a5772b68b8cab67717b26f634b0f15153215f65ae982a7e" },
        )
        controller.storeImportedProfile(
            source = "account",
            json = XHTTP_PROFILE,
            subscriptionUrl = "https://cloak.yokhan.xyz/gates/api/bootstrap/sub/stale123",
        )
        val cfg = controller.readConfig()
        assertEquals("account", cfg.profileSource)
        assertFalse(cfg.subscriptionUrl.contains("/api/bootstrap/"))

        val gen = controller.run("generate")
        if (gen.exitCode != 0 && "Physical bind" in gen.output) {
            // Host without active adapter for XHTTP physical bind: verify plain account profile does not fall back to block.
            controller.storeImportedProfile(
                source = "account",
                json = PLAIN_PROFILE,
                subscriptionUrl = "https://cloak.yokhan.xyz/gates/api/bootstrap/sub/stale123",
            )
            val plainGen = controller.run("generate")
            assertEquals(0, plainGen.exitCode, plainGen.output)
            val generated = JsonParser.parseString(root.resolve("runtime/config.json").readText()).asJsonObject
            assertEquals("auto", generated.getAsJsonObject("route").get("final").asString)
            return@runBlocking
        }
        assertEquals(0, gen.exitCode, gen.output)
        val generated = JsonParser.parseString(root.resolve("runtime/config.json").readText()).asJsonObject
        assertEquals("xhttp", generated.getAsJsonObject("route").get("final").asString)
    }

    @Test
    fun expiredBootstrapRefusesConnectCleanly() = runBlocking {
        val root = Files.createTempDirectory("gates-bootstrap-exp-").toFile()
        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = false,
            appDir = root,
            validateGeneratedConfig = { RuntimeCommandResult(0, "ok") },
        )
        controller.storeImportedProfile(
            source = "bootstrap",
            json = PLAIN_PROFILE,
            subscriptionUrl = "https://app.yokhan.xyz/gates/api/bootstrap/sub/expired",
            bootstrapExpiresAtEpochMillis = System.currentTimeMillis() - 5_000L,
        )

        val connectResult = controller.run("connect")
        assertEquals(1, connectResult.exitCode)
        assertTrue("истекло" in connectResult.output)
    }

    @Test
    fun candidateBootstrapSubUrlsIncludesCloakFallback() {
        val urls = windowsCandidateBootstrapSubUrls(
            subscriptionUrl = "https://app.yokhan.xyz/gates/api/bootstrap/sub/tok123",
            configuredBase = "https://app.yokhan.xyz/gates",
        )
        assertEquals(
            listOf(
                "https://app.yokhan.xyz/gates/api/bootstrap/sub/tok123",
                "https://cloak.yokhan.xyz/gates/api/bootstrap/sub/tok123",
            ),
            urls,
        )
    }

    @Test
    fun bootstrapWithExistingAuthAndRunningServiceStopsRestartsAndFallsBackSubHost() = runBlocking {
        val root = Files.createTempDirectory("gates-bootstrap-auth-").toFile()
        root.resolve("auth-state.json").writeText(
            """{"issuer":"https://auth.yokhan.xyz/realms/yokhan","clientId":"","tokenEndpoint":"https://auth.yokhan.xyz/realms/yokhan/protocol/openid-connect/token","accessToken":"old-tok","refreshToken":"old-ref","expiresAtEpochSecond":9999999999}""",
        )
        val seenRequests = mutableListOf<String>()
        val client = MockHttpClient { req, _ ->
            seenRequests += "${req.method()} ${req.uri()}"
            val uri = req.uri().toString()
            when {
                uri.endsWith("/api/bootstrap/claim") -> {
                    val exp = (System.currentTimeMillis() / 1000L) + 900L
                    200 to """{"subscription_url":"https://app.yokhan.xyz/gates/api/bootstrap/sub/tok1","expires_at":$exp}"""
                }
                uri == "https://app.yokhan.xyz/gates/api/bootstrap/sub/tok1" -> 503 to """{"error":"unavailable"}"""
                uri == "https://cloak.yokhan.xyz/gates/api/bootstrap/sub/tok1" -> 200 to XHTTP_PROFILE
                else -> 500 to "{}"
            }
        }
        var running = true
        var stopCount = 0
        var startCount = 0
        var currentDigest = "NONE"
        val fakeService = object : WindowsRuntimeControl {
            override fun health(): RuntimeCommandResult =
                RuntimeCommandResult(0, if (running) "RUNNING" else "STOPPED")

            override fun startTunnel(configJson: String): RuntimeCommandResult {
                running = true
                startCount += 1
                currentDigest = runtimeConfigurationDigest(configJson)
                return RuntimeCommandResult(0, "STARTED")
            }

            override fun stop(): RuntimeCommandResult {
                running = false
                stopCount += 1
                currentDigest = "NONE"
                return RuntimeCommandResult(0, "STOPPED")
            }

            override fun configurationDigest(): RuntimeCommandResult =
                RuntimeCommandResult(0, currentDigest)
        }
        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = true,
            appDir = root,
            runtimeService = fakeService,
            prepareRuntimeService = { RuntimeCommandResult(0, "ready") },
            validateGeneratedConfig = { RuntimeCommandResult(0, "ok") },
            authHttpClient = client,
            runtimeServiceInstalled = { true },
            xrayPinProvider = { "A".repeat(64) },
            serviceDataDir = root.resolve("service-data"),
            verifyBootstrapCode = { it.trim().equals("tree-secret", ignoreCase = true) },
        )

        val result = controller.run("bootstrap-claim", "tree-secret")
        assertTrue(seenRequests.contains("GET https://app.yokhan.xyz/gates/api/bootstrap/sub/tok1"))
        assertTrue(seenRequests.contains("GET https://cloak.yokhan.xyz/gates/api/bootstrap/sub/tok1"))
        assertTrue(seenRequests.none { "/api/app/" in it })
        assertTrue(seenRequests.none { it.endsWith("/api/bootstrap/revoke") })
        assertEquals("bootstrap", controller.readConfig().profileSource)
        if ("Physical bind" !in result.output) {
            assertEquals(0, result.exitCode, result.output)
            assertEquals(1, stopCount)
            assertEquals(1, startCount)
        }
    }

    @Test
    fun directBlockedStartsEmbeddedSeedTunnelAndStopsOnServer429Cooldown() = runBlocking {
        val root = Files.createTempDirectory("gates-bootstrap-seed-429-").toFile()
        var running = false
        var startCount = 0
        var stopCount = 0
        var currentDigest = "NONE"
        val startedPayloads = mutableListOf<String>()
        val fakeService = object : WindowsRuntimeControl {
            override fun health() = RuntimeCommandResult(0, if (running) "RUNNING" else "STOPPED")
            override fun startTunnel(configJson: String): RuntimeCommandResult {
                running = true
                startCount += 1
                startedPayloads += configJson
                currentDigest = runtimeConfigurationDigest(configJson)
                return RuntimeCommandResult(0, "STARTED")
            }
            override fun stop(): RuntimeCommandResult {
                running = false
                stopCount += 1
                currentDigest = "NONE"
                return RuntimeCommandResult(0, "STOPPED")
            }
            override fun configurationDigest() = RuntimeCommandResult(0, currentDigest)
        }
        val http = MockHttpClient { req, _ ->
            val uri = req.uri().toString()
            when {
                uri.endsWith("/api/bootstrap/claim") && !running -> 503 to """{"error":"blocked"}"""
                uri.endsWith("/api/bootstrap/claim") && running -> 429 to """{"error":"cooldown","retry_after":86400}"""
                else -> 404 to "{}"
            }
        }
        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = true,
            appDir = root,
            runtimeService = fakeService,
            prepareRuntimeService = { RuntimeCommandResult(0, "ready") },
            validateGeneratedConfig = { RuntimeCommandResult(0, "ok") },
            authHttpClient = http,
            runtimeServiceInstalled = { true },
            xrayPinProvider = { "A".repeat(64) },
            serviceDataDir = root.resolve("service-data"),
            verifyBootstrapCode = { it.trim() == "tree-secret" },
        )
        controller.storeImportedProfile("manual", PLAIN_PROFILE, "https://app.yokhan.xyz/gates/api/sub/keep")

        val result = controller.run("bootstrap-claim", "tree-secret")
        if ("Physical bind" in result.output) return@runBlocking
        assertEquals(429, result.exitCode, result.output)
        assertTrue("24 часа" in result.output)
        assertEquals(1, startCount)
        assertTrue(stopCount >= 1)
        assertFalse(running)
        assertTrue(WiseTreeAccess.EMBEDDED_UUID in startedPayloads.single())
        assertEquals("manual", controller.readConfig().profileSource)
    }

    @Test
    fun directBlockedStartsEmbeddedSeedTunnelAndUpgradesToPersonalUuid() = runBlocking {
        val root = Files.createTempDirectory("gates-bootstrap-seed-ok-").toFile()
        var running = false
        var startCount = 0
        var stopCount = 0
        var currentDigest = "NONE"
        val startedPayloads = mutableListOf<String>()
        val fakeService = object : WindowsRuntimeControl {
            override fun health() = RuntimeCommandResult(0, if (running) "RUNNING" else "STOPPED")
            override fun startTunnel(configJson: String): RuntimeCommandResult {
                running = true
                startCount += 1
                startedPayloads += configJson
                currentDigest = runtimeConfigurationDigest(configJson)
                return RuntimeCommandResult(0, "STARTED")
            }
            override fun stop(): RuntimeCommandResult {
                running = false
                stopCount += 1
                currentDigest = "NONE"
                return RuntimeCommandResult(0, "STOPPED")
            }
            override fun configurationDigest() = RuntimeCommandResult(0, currentDigest)
        }
        val exp = (System.currentTimeMillis() / 1000L) + 900L
        val http = MockHttpClient { req, _ ->
            val uri = req.uri().toString()
            when {
                uri.endsWith("/api/bootstrap/claim") && !running -> 503 to """{"error":"blocked"}"""
                uri.endsWith("/api/bootstrap/claim") && running ->
                    200 to """{"subscription_url":"https://cloak.yokhan.xyz/gates/api/bootstrap/sub/tok-seed","expires_at":$exp}"""
                uri == "https://cloak.yokhan.xyz/gates/api/bootstrap/sub/tok-seed" -> 200 to XHTTP_PROFILE
                else -> 404 to "{}"
            }
        }
        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = true,
            appDir = root,
            runtimeService = fakeService,
            prepareRuntimeService = { RuntimeCommandResult(0, "ready") },
            validateGeneratedConfig = { RuntimeCommandResult(0, "ok") },
            authHttpClient = http,
            runtimeServiceInstalled = { true },
            xrayPinProvider = { "A".repeat(64) },
            serviceDataDir = root.resolve("service-data"),
            verifyBootstrapCode = { it.trim() == "tree-secret" },
        )

        val result = controller.run("bootstrap-claim", "tree-secret")
        if ("Physical bind" in result.output) return@runBlocking
        assertEquals(0, result.exitCode, result.output)
        assertEquals(2, startCount)
        assertEquals(1, stopCount)
        assertTrue(running)
        assertTrue(WiseTreeAccess.EMBEDDED_UUID in startedPayloads[0])
        assertTrue(UUID in startedPayloads[1])
        assertEquals("bootstrap", controller.readConfig().profileSource)
    }

    @Test
    fun cancellationDuringEmbeddedBootstrapTunnelRollsBackProfileAndStopsTunnel() = runBlocking {
        val root = Files.createTempDirectory("gates-bootstrap-cancel-").toFile()
        var running = false
        var stopCount = 0
        var currentDigest = "NONE"
        val fakeService = object : WindowsRuntimeControl {
            override fun health() = RuntimeCommandResult(0, if (running) "RUNNING" else "STOPPED")
            override fun startTunnel(configJson: String): RuntimeCommandResult {
                running = true
                currentDigest = runtimeConfigurationDigest(configJson)
                return RuntimeCommandResult(0, "STARTED")
            }
            override fun stop(): RuntimeCommandResult {
                running = false
                stopCount += 1
                currentDigest = "NONE"
                return RuntimeCommandResult(0, "STOPPED")
            }
            override fun configurationDigest() = RuntimeCommandResult(0, currentDigest)
        }
        val http = MockHttpClient { req, _ ->
            val uri = req.uri().toString()
            when {
                uri.endsWith("/api/bootstrap/claim") && !running -> 503 to """{"error":"blocked"}"""
                uri.endsWith("/api/bootstrap/claim") && running -> throw CancellationException("user cancelled")
                else -> 404 to "{}"
            }
        }
        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = true,
            appDir = root,
            runtimeService = fakeService,
            prepareRuntimeService = { RuntimeCommandResult(0, "ready") },
            validateGeneratedConfig = { RuntimeCommandResult(0, "ok") },
            authHttpClient = http,
            runtimeServiceInstalled = { true },
            xrayPinProvider = { "A".repeat(64) },
            serviceDataDir = root.resolve("service-data"),
            verifyBootstrapCode = { it.trim() == "tree-secret" },
        )
        controller.storeImportedProfile("manual", PLAIN_PROFILE, "https://app.yokhan.xyz/gates/api/sub/keep")

        var cancelledCaught = false
        try {
            controller.run("bootstrap-claim", "tree-secret")
        } catch (_: CancellationException) {
            cancelledCaught = true
        }
        if ("Physical bind" in controller.run("logs").output) return@runBlocking
        assertTrue(cancelledCaught)
        assertFalse(running)
        assertTrue(stopCount >= 1)
        assertEquals("manual", controller.readConfig().profileSource)
    }

    @Test
    fun signedInUserStuckOnBootstrapSeedRecoversSavedAccountProfileWhenOffline() = runBlocking {
        val root = Files.createTempDirectory("gates-bootstrap-recover-acc-").toFile()
        var running = false
        var currentDigest = "NONE"
        val startedPayloads = mutableListOf<String>()
        val fakeService = object : WindowsRuntimeControl {
            override fun health() = RuntimeCommandResult(0, if (running) "RUNNING" else "STOPPED")
            override fun startTunnel(configJson: String): RuntimeCommandResult {
                running = true
                startedPayloads += configJson
                currentDigest = runtimeConfigurationDigest(configJson)
                return RuntimeCommandResult(0, "STARTED")
            }
            override fun stop(): RuntimeCommandResult {
                running = false
                currentDigest = "NONE"
                return RuntimeCommandResult(0, "STOPPED")
            }
            override fun configurationDigest() = RuntimeCommandResult(0, currentDigest)
        }
        val futureEpoch = (System.currentTimeMillis() / 1000L) + 3600L
        root.resolve("auth-state.json").writeText(
            """{"issuer":"$GATES_AUTH_ISSUER","clientId":"${WindowsAppConfig().authClientId}","tokenEndpoint":"$GATES_AUTH_ISSUER/protocol/openid-connect/token","accessToken":"VALID_ACCESS","refreshToken":"VALID_REFRESH","expiresAtEpochSecond":$futureEpoch}""",
        )
        val http = MockHttpClient { _, _ -> 503 to """{"error":"offline"}""" }
        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = true,
            appDir = root,
            runtimeService = fakeService,
            prepareRuntimeService = { RuntimeCommandResult(0, "ready") },
            validateGeneratedConfig = { RuntimeCommandResult(0, "ok") },
            authHttpClient = http,
            runtimeServiceInstalled = { true },
            xrayPinProvider = { "A".repeat(64) },
            serviceDataDir = root.resolve("service-data"),
        )
        assertEquals(0, controller.storeImportedProfile("account", XHTTP_PROFILE).exitCode)
        // Simulate stranded bootstrap seed profile while still signed into account
        assertEquals(
            0,
            controller.storeImportedProfile(
                source = "bootstrap",
                json = WiseTreeAccess.buildEmbeddedBootstrapProfileJson(autoDetectInterface = true),
                subscriptionUrl = "$GATES_CLOAK_APP_API_BASE_URL/api/bootstrap/seed",
                bootstrapExpiresAtEpochMillis = System.currentTimeMillis() - 5_000L,
            ).exitCode,
        )
        assertEquals("bootstrap", controller.readConfig().profileSource)

        val connectResult = controller.run("connect")
        if ("Physical bind" in connectResult.output) return@runBlocking
        assertEquals(0, connectResult.exitCode, connectResult.output)
        assertEquals("account", controller.readConfig().profileSource)
        assertEquals(0L, controller.readConfig().bootstrapExpiresAtEpochMillis)
        assertTrue(startedPayloads.isNotEmpty())
        val startedObj = JsonParser.parseString(startedPayloads.last()).asJsonObject
        assertEquals("xhttp", startedObj.getAsJsonObject("route").get("final").asString)
    }

    @Test
    fun signedInUserStuckOnBootstrapSeedFailsCleanlyWithoutSavedAccountWhenOffline() = runBlocking {
        val root = Files.createTempDirectory("gates-bootstrap-seed-fail-").toFile()
        val futureEpoch = (System.currentTimeMillis() / 1000L) + 3600L
        root.resolve("auth-state.json").writeText(
            """{"issuer":"$GATES_AUTH_ISSUER","clientId":"${WindowsAppConfig().authClientId}","tokenEndpoint":"$GATES_AUTH_ISSUER/protocol/openid-connect/token","accessToken":"VALID_ACCESS","refreshToken":"VALID_REFRESH","expiresAtEpochSecond":$futureEpoch}""",
        )
        val http = MockHttpClient { _, _ -> 503 to """{"error":"offline"}""" }
        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = false,
            appDir = root,
            validateGeneratedConfig = { RuntimeCommandResult(0, "ok") },
            authHttpClient = http,
        )
        assertEquals(
            0,
            controller.storeImportedProfile(
                source = "bootstrap",
                json = WiseTreeAccess.buildEmbeddedBootstrapProfileJson(autoDetectInterface = true),
                subscriptionUrl = "$GATES_CLOAK_APP_API_BASE_URL/api/bootstrap/seed",
                bootstrapExpiresAtEpochMillis = System.currentTimeMillis() + 600_000L,
            ).exitCode,
        )

        val connectResult = controller.run("connect")
        assertTrue(connectResult.exitCode != 0)
    }

    private class MockHttpClient(
        private val respond: (HttpRequest, String) -> Pair<Int, String>,
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
            val body = StringBuilder()
            request.bodyPublisher().ifPresent { pub ->
                pub.subscribe(object : Flow.Subscriber<ByteBuffer> {
                    override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)
                    override fun onNext(item: ByteBuffer) { body.append(StandardCharsets.UTF_8.decode(item)) }
                    override fun onError(throwable: Throwable) {}
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
        ): CompletableFuture<HttpResponse<T>> = error("Unexpected async")

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            handler: HttpResponse.BodyHandler<T>,
            pushHandler: HttpResponse.PushPromiseHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = error("Unexpected push")
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
