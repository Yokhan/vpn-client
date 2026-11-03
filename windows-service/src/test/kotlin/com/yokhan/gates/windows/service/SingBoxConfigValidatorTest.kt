package com.yokhan.gates.windows.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class SingBoxConfigValidatorTest {

    @Test
    fun acceptsAConstrainedTunConfig() {
        val result = SingBoxConfigValidator.validate(validConfig())

        assertIs<ConfigValidationResult.Accepted>(result)
    }

    @Test
    fun acceptsProductTunConfigWithLocalProxyAndProcessRules() {
        val config = """{
            "inbounds":[
                {"type":"tun","tag":"tun-in"},
                {"type":"mixed","tag":"mixed-in","listen":"127.0.0.1","listen_port":2080},
                {"type":"http","tag":"http-in","listen":"127.0.0.1","listen_port":2081}
            ],
            "outbounds":[{"type":"direct","tag":"direct"}],
            "route":{"rules":[{"process_path":["C:/Games/game.exe"],"outbound":"direct"}]}
        }""".trimIndent().toByteArray()

        assertIs<ConfigValidationResult.Accepted>(SingBoxConfigValidator.validate(config))
    }

    @Test
    fun rejectsMalformedAndOversizedConfig() {
        assertRejected("{\"inbounds\":".toByteArray(), ConfigErrorCode.MALFORMED_JSON)
        assertRejected(ByteArray(MAX_CONFIG_BYTES + 1), ConfigErrorCode.CONFIG_TOO_LARGE)
    }

    @Test
    fun rejectsDangerousSectionsKeysAndDuplicateTags() {
        assertRejected(validConfig("\"experimental\":{}"), ConfigErrorCode.EXPERIMENTAL_NOT_ALLOWED)
        assertRejected(validConfig("\"route\":{\"rule_set\":[{\"certificate_path\":\"C:/bad\"}]}"), ConfigErrorCode.FORBIDDEN_KEY)
        assertRejected(validConfig("\"log\":{\"output\":\"C:/Windows/System32/bad.log\"}"), ConfigErrorCode.FORBIDDEN_KEY)
        assertRejected(
            """{"inbounds":[{"type":"tun"}],"outbounds":[{"type":"direct","tag":"same"},{"type":"block","tag":"same"}]}""".toByteArray(),
            ConfigErrorCode.DUPLICATE_TAG,
        )
    }

    @Test
    fun rejectsDuplicateJsonKeysAndInvalidServerPorts() {
        assertRejected(
            """{"inbounds":[{"type":"tun"}],"outbounds":[{"type":"vless","tag":"remote","server_port":0,"server_port":443}]}""".toByteArray(),
            ConfigErrorCode.DUPLICATE_JSON_KEY,
        )
        assertRejected(
            """{"inbounds":[{"type":"tun"}],"outbounds":[{"type":"vless","tag":"remote","server_port":70000}]}""".toByteArray(),
            ConfigErrorCode.INVALID_SERVER_PORT,
        )
    }

    @Test
    fun extractsSelectedProtectionAndExcludesItFromNativeJson() {
        val result = assertIs<ConfigValidationResult.Accepted>(
            SingBoxConfigValidator.validate(guardedConfig("C:\\Apps\\browser.exe")),
        ).config
        assertEquals(listOf("C:\\Apps\\browser.exe"), result.protection?.protectedExecutables)
        assertFalse(result.canonicalJson.contains("gates_protection"))
    }

    @Test
    fun rejectsInvalidSelectedProtectionPathsAndPolicyConflicts() {
        listOf(
            "\\\\server\\share\\browser.exe",
            "\\\\?\\C:\\Apps\\browser.exe",
            "C:\\Apps\\..\\browser.exe",
            "C:\\Apps\\browser.exe:stream",
            "C:\\Apps\\browser.txt",
            "C:\\Apps\\NUL.exe",
            "C:\\Apps\\bad\u0001.exe",
        ).forEach { path -> assertRejected(guardedConfig(path), ConfigErrorCode.INVALID_PROTECTION_METADATA) }
        assertRejected(
            guardedConfig("C:\\Apps\\browser.exe", schema = "1.0"),
            ConfigErrorCode.INVALID_PROTECTION_METADATA,
        )
        assertRejected(
            guardedConfig("C:\\Program Files\\GATES\\sing-box.exe"),
            ConfigErrorCode.INVALID_PROTECTION_METADATA,
            coreExecutablePath = "C:\\Program Files\\GATES\\sing-box.exe",
        )
        val duplicate = guardedConfig("C:\\Apps\\browser.exe", secondPath = "c:\\apps\\BROWSER.EXE")
        assertRejected(duplicate, ConfigErrorCode.INVALID_PROTECTION_METADATA)
    }

    @Test
    fun rejectsExactControlPlanePathsWithoutBanningOtherJavaExecutables() {
        val installedApp = "C:\\Program Files\\GATES\\GATES.exe"
        val serviceJvm = "C:\\Program Files\\GATES\\app\\resources\\gates-service\\jre\\bin\\java.exe"
        val core = "C:\\Program Files\\GATES\\app\\resources\\yokhan-runtime\\windows\\sing-box.exe"
        val reserved = setOf(installedApp, serviceJvm, core)

        listOf(installedApp, serviceJvm, core).forEach { path ->
            assertRejected(guardedConfig(path), ConfigErrorCode.INVALID_PROTECTION_METADATA, reservedExecutablePaths = reserved)
        }
        assertRejected(
            guardedConfig("C:\\Apps\\browser.exe"),
            ConfigErrorCode.INVALID_PROTECTION_METADATA,
            reservedExecutablePaths = null,
        )
        assertIs<ConfigValidationResult.Accepted>(
            SingBoxConfigValidator.validate(guardedConfig("D:\\Tools\\java.exe"), reservedExecutablePaths = reserved),
        )
    }

    private fun validConfig(extraSection: String = ""): ByteArray {
        val separator = if (extraSection.isBlank()) "" else ",$extraSection"
        return """{"inbounds":[{"type":"tun"}],"outbounds":[{"type":"direct","tag":"default"}]$separator}""".toByteArray()
    }

    private fun assertRejected(
        config: ByteArray,
        expected: ConfigErrorCode,
        coreExecutablePath: String? = null,
        reservedExecutablePaths: Set<String>? = emptySet(),
    ) {
        val result = assertIs<ConfigValidationResult.Rejected>(
            SingBoxConfigValidator.validate(config, coreExecutablePath, reservedExecutablePaths),
        )
        assertEquals(expected, result.error.code)
    }

    private fun guardedConfig(path: String, schema: String = "1", secondPath: String? = null): ByteArray {
        val paths = listOf(path, secondPath).filterNotNull().joinToString(",") { com.google.gson.Gson().toJson(it) }
        return """{"inbounds":[{"type":"tun"}],"outbounds":[{"type":"direct","tag":"default"}],"gates_protection":{"schema":$schema,"protected_executables":[$paths]}}""".toByteArray()
    }
}
