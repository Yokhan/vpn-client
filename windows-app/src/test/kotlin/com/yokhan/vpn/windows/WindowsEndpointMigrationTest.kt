package com.yokhan.gates.windows

import com.google.gson.JsonParser
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsEndpointMigrationTest {
    @Test
    fun migratesOnlyExactLegacyIssuerAndApiBaseValues() {
        val fixture = Fixture()
        val configFile = fixture.root.resolve("app-config.json")
        configFile.writeText(
            """{"appApiBaseUrl":"https://cloak.yokhanfitness.ru","authIssuer":"https://auth.yokhanfitness.ru/realms/yokhan","subscriptionUrl":"https://cloak.yokhanfitness.ru/api/sub/manual","profileSource":"manual","custom":"keep"}""",
        )

        val config = fixture.controller.readConfig()
        val persisted = JsonParser.parseString(configFile.readText()).asJsonObject

        assertEquals(GATES_APP_API_BASE_URL, config.appApiBaseUrl)
        assertEquals(GATES_AUTH_ISSUER, config.authIssuer)
        assertEquals("https://cloak.yokhanfitness.ru/api/sub/manual", config.subscriptionUrl)
        assertEquals("manual", config.profileSource)
        assertEquals("keep", persisted.get("custom").asString)
    }

    @Test
    fun leavesCustomEndpointsAndMismatchedSessionsAlone() {
        val customApi = "https://custom.example/gates"
        val customIssuer = "https://sso.example/realms/customer"
        assertEquals(customApi, migrateWindowsAppApiBaseUrl(customApi))
        assertEquals(customIssuer, migrateWindowsAuthIssuer(customIssuer))

        assertTrue(windowsAuthIssuerMatches(GATES_AUTH_ISSUER, GATES_AUTH_ISSUER))
        assertFalse(windowsAuthIssuerMatches("https://auth.yokhanfitness.ru/realms/yokhan", GATES_AUTH_ISSUER))
        assertFalse(windowsAuthIssuerMatches(null, GATES_AUTH_ISSUER))
    }

    @Test
    fun migratesDownloadAndReportDefaultsWithoutChangingCustomUrlsOrKeys() {
        val source = JsonParser.parseString(
            """{"downloadPageUrl":"https://yokhanfitness.ru/hub/gates","reportUrls":["https://cloak.yokhanfitness.ru/api/app/bug-report","https://yokhanfitness.ru/api/app/bug-report","https://custom.example/report","https://custom.example/report"],"subscriptionUrl":"https://custom.example/personal","authRoom":"family","unknown":12}""",
        ).asJsonObject
        assertTrue(migrateWindowsConfigEndpoints(source))
        assertEquals(GATES_DOWNLOAD_PAGE_URL, source.get("downloadPageUrl").asString)
        assertEquals(listOf(GATES_BUG_REPORT_URL, "https://custom.example/report", "https://custom.example/report"),
            source.getAsJsonArray("reportUrls").map { it.asString })
        assertEquals("https://custom.example/personal", source.get("subscriptionUrl").asString)
        assertEquals("family", source.get("authRoom").asString)
        assertEquals(12, source.get("unknown").asInt)
        assertFalse(migrateWindowsConfigEndpoints(source))
        assertEquals("https://custom.example/download", migrateWindowsDownloadPage("https://custom.example/download"))
    }

    @Test
    fun downloadPageCanonicalUrlMigratesBothLegacyHubUrls() {
        assertEquals("https://fitness.yokhan.xyz/download", GATES_DOWNLOAD_PAGE_URL)
        assertFalse(GATES_DOWNLOAD_PAGE_URL.contains("/hub"))

        assertEquals(GATES_DOWNLOAD_PAGE_URL, migrateWindowsDownloadPage("https://yokhanfitness.ru/hub/gates"))
        assertEquals(GATES_DOWNLOAD_PAGE_URL, migrateWindowsDownloadPage("https://app.yokhan.xyz/hub/gates"))
        assertEquals(GATES_DOWNLOAD_PAGE_URL, migrateWindowsDownloadPage("https://yokhanfitness.ru/hub/gates/"))
        assertEquals(GATES_DOWNLOAD_PAGE_URL, migrateWindowsDownloadPage("https://app.yokhan.xyz/hub/gates/"))
        assertEquals("https://custom.example/download", migrateWindowsDownloadPage("https://custom.example/download"))
    }

    private class Fixture {
        val root = Files.createTempDirectory("gates-endpoint-migration").toFile()
        val controller = WindowsRuntimeController(appDir = root)
    }
}
