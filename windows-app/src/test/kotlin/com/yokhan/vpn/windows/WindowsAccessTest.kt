package com.yokhan.gates.windows

import java.nio.file.Files
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertTrue

class WindowsAccessTest {
    @Test fun manualNlOnlyProfileGetsAnExplicitNonemptyRouteOrder() {
        val json = """{"outbounds":[{"type":"vless","tag":"nl-tcp","server":"example.invalid","server_port":443}]}"""
        val selected = windowsImportedProfileConfig(WindowsAppConfig(), "manual", json)
        assertEquals(listOf("nl-tcp"), selected.routeOrder)
        assertEquals("auto", selected.activeRoute)
        assertFails { windowsImportedProfileConfig(WindowsAppConfig(activeRoute = "hy2"), "manual", json) }
    }
    @Test
    fun subscriptionEndpointPreservesQueryAndEscaping() {
        assertEquals("https://example.org/api/sub/user%3Atoken/singbox?app=gates",
            subscriptionJsonEndpoint(" https://example.org/api/sub/user%3Atoken?app=gates "))
        assertEquals("https://example.org/api/sub/user/singbox?app=gates",
            subscriptionJsonEndpoint("https://example.org/api/sub/user/singbox?app=gates"))
        assertEquals("https://example.org/config.json?token=secret",
            subscriptionJsonEndpoint("https://example.org/config.json?token=secret"))
    }

    @Test
    fun migratedGatewaySubscriptionRequestsJsonInsteadOfLinkList() {
        assertEquals("https://example.org/gates/api/sub/user%3Atoken/singbox?app=gates",
            subscriptionJsonEndpoint("https://example.org/gates/api/sub/user%3Atoken/?app=gates"))
        assertEquals("https://example.org/gates/api/sub/user/singbox",
            subscriptionJsonEndpoint("https://example.org/gates/api/sub/user/singbox/"))
        assertEquals("https://example.org/gates/config.json",
            subscriptionJsonEndpoint("https://example.org/gates/config.json"))
        assertEquals("https://example.org/gates/api/subscription/config.json",
            subscriptionJsonEndpoint("https://example.org/gates/api/subscription/config.json"))
    }

    @Test
    fun rejectsInsecureOrNonSubscriptionInputs() {
        for (input in listOf("", "http://example.org/sub", "vless://secret@example.org:443",
            "https://user:password@example.org/sub", "https://example.org/sub#secret")) {
            assertFails { subscriptionJsonEndpoint(input) }
        }
    }

    @Test
    fun accountAutoCandidatesIncludeEveryValidProxyAndExcludeDirect() {
        val outbounds = JsonParser.parseString(
            """{"outbounds":[{"type":"direct","tag":"direct"},{"type":"vless","tag":"nl-tcp","server":"45.134.174.246","server_port":41567},{"type":"hysteria2","tag":"hy2","server":"hy2.example","server_port":443},{"type":"hysteria2","tag":"se-hy2","server":"se.example","server_port":4443},{"type":"selector","tag":"auto"},{"type":"vless","tag":"broken","server":"invalid.example","server_port":70000}]}""",
        ).asJsonObject.getAsJsonArray("outbounds")
        val proxyRoutes = validWindowsProxyRouteTags(outbounds)

        assertTrue(hasImportedTunnelProfile("""{"outbounds":[{"type":"vless","tag":"nl-tcp","server":"45.134.174.246","server_port":41567}]}"""))
        assertEquals(listOf("nl-tcp", "hy2", "se-hy2"), proxyRoutes)
        assertEquals(
            listOf("hy2", "nl-tcp", "se-hy2"),
            windowsAutoRouteCandidates(listOf("bridge", "relay", "naive", "hy2"), proxyRoutes, true),
        )
        assertFalse("direct" in windowsAutoRouteCandidates(listOf("direct"), listOf("direct", "nl-tcp"), true))
    }

    @Test
    fun manualAutoCandidatesKeepCustomDeclaredOrderAndDoNotAppendRoutes() {
        assertEquals(
            listOf("custom-b", "custom-a"),
            windowsAutoRouteCandidates(
                listOf("custom-b", "custom-a"),
                listOf("custom-a", "nl-tcp", "custom-b"),
                includeUnlistedRoutes = false,
            ),
        )
    }

    @Test
    fun nlTcpCanBeSelectedAndIncludedInPhysicalBindConfiguration() {
        val fixture = Fixture()
        fixture.controller.writeConfig(
            WindowsAppConfig(
                activeRoute = "nl-tcp",
                physicalBindRoutes = listOf("nl-tcp", "direct"),
            ),
        )

        val config = fixture.controller.readConfig()
        assertEquals("nl-tcp", config.activeRoute)
        assertEquals(listOf("nl-tcp"), config.physicalBindRoutes)
        assertTrue(isWindowsProxyRouteId("nl-tcp"))
        assertTrue(isWindowsProxyRouteId("se-hy2"))
        assertFalse(isWindowsProxyRouteId("direct"))
    }

    @Test
    fun cachedLegacyProfileWorksWithoutAccountAndIsNotChanged() {
        val fixture = Fixture()
        val legacy = fixture.root.resolve("runtime/subscription.singbox.json")
        legacy.parentFile.mkdirs()
        legacy.writeText(PROFILE)
        val state = fixture.controller.sessionState()
        assertTrue(state.ready)
        assertFalse(state.signedIn)
        assertEquals(PROFILE, legacy.readText())
    }

    @Test
    fun emptyOrCorruptFileDoesNotMakeAppReady() {
        val fixture = Fixture()
        val legacy = fixture.root.resolve("runtime/subscription.singbox.json")
        legacy.parentFile.mkdirs()
        for (json in listOf("", "invalid", "{}", "{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}]}")) {
            legacy.writeText(json)
            assertFalse(fixture.controller.sessionState().ready)
        }
    }

    @Test
    fun manualAndAccountImportsKeepSeparateCachesAndSelectedSource() {
        val fixture = Fixture()
        assertEquals(0, fixture.controller.storeImportedProfile("account", PROFILE).exitCode)
        assertEquals(0, runBlocking { fixture.controller.run("import-sub", URL) }.exitCode)
        assertEquals("manual", fixture.controller.readConfig().profileSource)
        assertEquals(URL, fixture.controller.readConfig().subscriptionUrl)
        assertEquals(PROFILE, fixture.root.resolve("runtime/account.singbox.json").readText())
        assertEquals(MANUAL, fixture.root.resolve("runtime/manual.singbox.json").readText())
        assertTrue(fixture.controller.sessionState().ready)
        assertEquals(0, fixture.controller.storeImportedProfile("account", PROFILE).exitCode)
        assertEquals("account", fixture.controller.readConfig().profileSource)
        assertEquals(MANUAL, fixture.root.resolve("runtime/manual.singbox.json").readText())
    }

    @Test
    fun rejectedImportPreservesSelectedProfileAndDoesNotExposeInput() {
        val fixture = Fixture(fetch = { "{\"error\":\"secret-from-server\"}" })
        fixture.controller.storeImportedProfile("account", PROFILE)
        val settings = fixture.root.resolve("app-config.json").readText()
        val result = runBlocking { fixture.controller.run("import-sub", URL) }
        assertTrue(result.exitCode != 0)
        assertEquals(settings, fixture.root.resolve("app-config.json").readText())
        assertEquals("account", fixture.controller.readConfig().profileSource)
        assertTrue(fixture.controller.sessionState().ready)
        assertFalse(result.output.contains("secret"))
        assertFalse(fixture.root.resolve("runtime/manual.singbox.json").exists())
    }

    @Test
    fun fetchFailureKeepsManualProfileAndDoesNotExposeUrlOrException() {
        val fixture = Fixture(fetch = { throw IllegalStateException("$it password") })
        fixture.controller.storeImportedProfile("manual", MANUAL, URL)
        val result = runBlocking { fixture.controller.run("import-sub", URL) }
        assertTrue(result.exitCode != 0)
        assertEquals(MANUAL, fixture.root.resolve("runtime/manual.singbox.json").readText())
        assertTrue(fixture.controller.sessionState().ready)
        assertFalse(result.output.contains("secret"))
        assertFalse(result.output.contains("password"))
    }

    @Test
    fun malformedReplacementPreservesExistingManualCache() {
        val fixture = Fixture()
        fixture.controller.storeImportedProfile("manual", MANUAL, URL)
        val file = fixture.root.resolve("runtime/manual.singbox.json")
        val result = fixture.controller.storeImportedProfile("manual", "{}", URL)
        assertTrue(result.exitCode != 0)
        assertEquals(MANUAL, file.readText())
    }

    @Test
    fun failedProfileWriteKeepsAccountSelection() {
        val fixture = Fixture()
        fixture.controller.storeImportedProfile("account", PROFILE)
        fixture.root.resolve("runtime/manual.singbox.json").mkdirs()
        val settings = fixture.root.resolve("app-config.json").readText()
        val result = runBlocking { fixture.controller.run("import-sub", URL) }
        assertTrue(result.exitCode != 0)
        assertEquals(settings, fixture.root.resolve("app-config.json").readText())
        assertEquals("account", fixture.controller.readConfig().profileSource)
        assertEquals(PROFILE, fixture.root.resolve("runtime/account.singbox.json").readText())
    }

    @Test
    fun transcriptHidesPersonalSubscriptionArgument() {
        val fixture = Fixture()
        val transcript = fixture.controller.writeCliTranscript("import-sub", listOf(URL), RuntimeCommandResult(1, "Import failed"))
        val text = transcript.readText()
        assertFalse(text.contains(URL))
        assertFalse(text.contains("secret"))
        assertTrue(text.contains("<redacted>"))
    }

    @Test
    fun exportHidesEntireUrlRegardlessOfCredentialPlacement() {
        for (url in listOf(URL, "https://example.org/private-secret/config.json?access_key=secret",
            "https://example.org/api/sub/token/singbox?another_secret=secret")) {
            assertEquals("<redacted>", redactedSubscriptionUrl(url))
        }
        assertEquals("", redactedSubscriptionUrl(""))
    }

    private class Fixture(fetch: (String) -> String = { MANUAL }) {
        val root = Files.createTempDirectory("gates-access-test").toFile()
        val controller = WindowsRuntimeController(appDir = root, fetchSubscriptionJson = fetch)
    }

    companion object {
        const val URL = "https://example.org/api/sub/user:secret"
        const val PROFILE = """{"outbounds":[{"type":"vless","tag":"relay","server":"example.org","server_port":443}]}"""
        const val MANUAL = """{"outbounds":[{"type":"hysteria2","tag":"hy2","server":"manual.example.org","server_port":443}]}"""
    }
}
