package com.yokhan.gates.windows.service

import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun validConfig(extraSection: String = ""): ByteArray {
        val separator = if (extraSection.isBlank()) "" else ",$extraSection"
        return """{"inbounds":[{"type":"tun"}],"outbounds":[{"type":"direct","tag":"default"}]$separator}""".toByteArray()
    }

    private fun assertRejected(config: ByteArray, expected: ConfigErrorCode) {
        val result = assertIs<ConfigValidationResult.Rejected>(SingBoxConfigValidator.validate(config))
        assertEquals(expected, result.error.code)
    }
}
