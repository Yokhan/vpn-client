package com.yokhan.gates.windows.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class XhttpSidecarSpecTest {

    @Test
    fun acceptsAndStripsLoopbackXhttpSidecar() {
        val result = assertIs<ConfigValidationResult.Accepted>(SingBoxConfigValidator.validate(validConfig())).config

        assertEquals("xhttp", result.sidecar?.outboundTag)
        assertEquals(2090, result.sidecar?.listenPort)
        assertEquals(true, result.sidecar?.required)
        assertEquals("xray.exe", result.sidecar?.executableName)
        assertFalse(result.canonicalJson.contains("gates_xhttp"))
        assertFalse(result.canonicalJson.contains("xhttpSettings"))
    }

    @Test
    fun keepsOptionalSidecarOutOfTheFinalRouteRule() {
        val result = assertIs<ConfigValidationResult.Accepted>(
            SingBoxConfigValidator.validate(validConfig(required = false, autoCandidates = true)),
        ).config

        assertEquals(false, result.sidecar?.required)
    }

    @Test
    fun acceptsAFixedPhysicalInterfaceBinding() {
        val result = assertIs<ConfigValidationResult.Accepted>(
            SingBoxConfigValidator.validate(validConfig(physicalInterface = "Ethernet 2")),
        ).config
        assertEquals(true, result.sidecar?.xrayConfigJson?.contains("\"interface\":\"Ethernet 2\""))
    }

    @Test
    fun rejectsXhttpWithoutAConcretePhysicalInterfaceBinding() {
        assertRejected(
            validConfig(physicalInterface = null),
            ConfigErrorCode.XHTTP_PHYSICAL_BIND_REQUIRED,
        )
        assertRejected(
            validConfig(physicalInterface = " "),
            ConfigErrorCode.XHTTP_PHYSICAL_BIND_REQUIRED,
        )
        assertRejected(
            validConfig(tunInterfaceName = "GATES", physicalInterface = "GATES"),
            ConfigErrorCode.XHTTP_PHYSICAL_BIND_REQUIRED,
        )
    }

    @Test
    fun rejectsSidecarWhoseSocksOutboundDoesNotMatch() {
        assertRejected(validConfig(socksTag = "other"), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
        assertRejected(validConfig(socksServer = "10.0.0.1"), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
        assertRejected(validConfig(socksPort = 2091), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
        assertRejected(validConfig(socksUser = "user"), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
        assertRejected(validConfig(socksVersion = "4"), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
    }

    @Test
    fun rejectsSocksOutboundWithoutSidecarMetadata() {
        assertRejected(validConfig(withSidecar = false), ConfigErrorCode.INVALID_SOCKS_OUTBOUND)
    }

    @Test
    fun rejectsPortCollisionsAndUnsafePorts() {
        assertRejected(validConfig(sidecarPort = 2088), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
        assertRejected(validConfig(sidecarPort = 38417), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
        assertRejected(validConfig(sidecarPort = 80), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
    }

    @Test
    fun rejectsRequiredSidecarThatTheTunnelDoesNotDependOn() {
        assertRejected(validConfig(finalRoute = "direct"), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
    }

    @Test
    fun rejectsXrayConfigsThatWouldCreateASecondDnsOrTunStack() {
        assertRejected(validConfig(extraXraySection = """"dns":{"servers":["1.1.1.1"]}"""), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
        assertRejected(validConfig(extraXraySection = """"routing":{"rules":[]}"""), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
        assertRejected(validConfig(extraInbound = true), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
        assertRejected(validConfig(xrayInboundProtocol = "tun"), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
        assertRejected(validConfig(xrayListen = "0.0.0.0"), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
    }

    @Test
    fun rejectsNonLiteralServersAndMuxCool() {
        assertRejected(validConfig(serverAddress = "entry.example.com"), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
        assertRejected(validConfig(xrayMux = true), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
        assertRejected(validConfig(sockoptExtra = """"dialerProxy":"direct""""), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
        assertRejected(validConfig(sockoptExtra = """"mark":1"""), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
    }

    @Test
    fun rejectsInvalidExecutablePinsAndSchema() {
        assertRejected(validConfig(executable = "..\\\\xray.exe"), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
        assertRejected(validConfig(executable = "other.exe"), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
        assertRejected(validConfig(sha256 = "not-a-hash"), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
        assertRejected(validConfig(schema = 2), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
        assertRejected(validConfig(extraSpecKey = ""","extra":true"""), ConfigErrorCode.INVALID_XHTTP_SIDECAR)
    }

    @Test
    fun leavesPlainSingBoxConfigsUnchanged() {
        val plain = """{"inbounds":[{"type":"tun","tag":"tun-in"}],"outbounds":[{"type":"direct","tag":"direct"}]}"""
        val result = assertIs<ConfigValidationResult.Accepted>(SingBoxConfigValidator.validate(plain.toByteArray())).config
        assertNull(result.sidecar)
        assertEquals(plain, result.canonicalJson)
    }

    private fun assertRejected(config: ByteArray, expected: ConfigErrorCode) {
        val result = assertIs<ConfigValidationResult.Rejected>(SingBoxConfigValidator.validate(config))
        assertEquals(expected, result.error.code)
    }

    private fun validConfig(
        socksTag: String = "xhttp",
        socksServer: String = "127.0.0.1",
        socksPort: Int = 2090,
        socksUser: String? = null,
        socksVersion: String = "5",
        sidecarPort: Int = 2090,
        finalRoute: String = "xhttp",
        required: Boolean = true,
        autoCandidates: Boolean = false,
        serverAddress: String = "203.0.113.10",
        xrayMux: Boolean = false,
        xrayInboundProtocol: String = "socks",
        xrayListen: String = "127.0.0.1",
        extraXraySection: String = "",
        extraInbound: Boolean = false,
        tunInterfaceName: String? = null,
        physicalInterface: String? = "Wi-Fi",
        sockoptExtra: String = "",
        executable: String = "xray.exe",
        sha256: String = "a".repeat(64),
        schema: Int = 1,
        extraSpecKey: String = "",
        withSidecar: Boolean = true,
    ): ByteArray {
        val user = socksUser?.let { ""","username":"$it"""" }.orEmpty()
        val socks = """{"type":"socks","tag":"$socksTag","server":"$socksServer","server_port":$socksPort,"version":"$socksVersion"$user}"""
        val autoOutbound = if (autoCandidates) {
            """,{"type":"selector","tag":"auto","outbounds":["$socksTag","direct"],"default":"$socksTag"}"""
        } else {
            ""
        }
        val singBoxFinal = if (autoCandidates) "auto" else finalRoute
        val extraInboundJson = if (extraInbound) {
            """{"protocol":"socks","listen":"127.0.0.1","port":2091,"settings":{"auth":"noauth","udp":true}},"""
        } else {
            ""
        }
        val mux = if (xrayMux) ""","mux":{"enabled":true}""" else ""
        val sockoptFields = listOfNotNull(
            physicalInterface?.let { """"interface":"$it"""" },
            sockoptExtra.takeIf(String::isNotBlank),
        )
        val sockopt = if (sockoptFields.isEmpty()) "" else ""","sockopt":{${sockoptFields.joinToString(",")}}"""
        val xrayExtra = if (extraXraySection.isBlank()) "" else """,$extraXraySection"""

        val outbound = """{"protocol":"vless","settings":{"vnext":[{"address":"$serverAddress","port":443,""" +
            """"users":[{"id":"00000000-0000-4000-8000-000000000001","encryption":"none"}]}]},""" +
            """"streamSettings":{"network":"xhttp","security":"reality",""" +
            """"realitySettings":{"serverName":"entry.example.com","fingerprint":"chrome",""" +
            """"publicKey":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","shortId":"abcd"},""" +
            """"xhttpSettings":{"path":"/lab","host":"entry.example.com","mode":"auto"}$sockopt}$mux}"""
        val xrayConfig = """{"log":{"loglevel":"warning"},"inbounds":[$extraInboundJson{"protocol":"$xrayInboundProtocol",""" +
            """"listen":"$xrayListen","port":$sidecarPort,"settings":{"auth":"noauth","udp":true}}],""" +
            """"outbounds":[$outbound]$xrayExtra}"""
        val sidecar = if (!withSidecar) {
            ""
        } else {
            ""","gates_xhttp":{"schema":$schema,"outbound_tag":"$socksTag","executable":"$executable",""" +
                """"executable_sha256":"$sha256","listen_port":$sidecarPort,"required":$required,""" +
                """"xray_config":$xrayConfig$extraSpecKey}"""
        }
        val tunInterface = tunInterfaceName?.let { ""","interface_name":"$it"""" }.orEmpty()
        return ("""{"inbounds":[{"type":"tun","tag":"tun-in"$tunInterface},""" +
            """{"type":"mixed","tag":"mixed-in","listen":"127.0.0.1","listen_port":2088}],""" +
            """"outbounds":[$socks$autoOutbound,{"type":"direct","tag":"direct"}],""" +
            """"route":{"final":"$singBoxFinal"}$sidecar}""").toByteArray()
    }
}
