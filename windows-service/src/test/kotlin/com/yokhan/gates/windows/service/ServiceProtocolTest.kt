package com.yokhan.gates.windows.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import java.util.Base64

class ServiceProtocolTest {
    private val token = "a".repeat(64)

    @Test
    fun parsesAndFormatsTheOnlyStartTunnelRequest() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{}".toByteArray())
        val parsed = ServiceProtocolParser.parse("2|$token|START_TUNNEL|$payload")

        val accepted = assertIs<ProtocolParseResult.Accepted>(parsed)
        val request = assertIs<StartTunnelRequest>(accepted.request)
        assertEquals(payload, request.encodedConfig)
        assertEquals("2|$token|START_TUNNEL|$payload", ServiceProtocolParser.format(request))
    }

    @Test
    fun rejectsUnknownCommands() {
        val parsed = ServiceProtocolParser.parse("2|$token|EXECUTE|payload")

        assertRejected(parsed, ProtocolErrorCode.UNSUPPORTED_COMMAND)
    }

    @Test
    fun digestRequestUsesTheSameBoundedEmptyPayloadContract() {
        val accepted = assertIs<ProtocolParseResult.Accepted>(ServiceProtocolParser.parse("2|$token|CONFIG_DIGEST|"))
        assertIs<ConfigDigestRequest>(accepted.request)
        assertEquals("2|$token|CONFIG_DIGEST|", ServiceProtocolParser.format(accepted.request))
        assertRejected(ServiceProtocolParser.parse("2|$token|CONFIG_DIGEST|payload"), ProtocolErrorCode.UNEXPECTED_PAYLOAD)
    }

    @Test
    fun protectionStatusIsAnAdditiveEmptyPayloadCommand() {
        val frame = "2|$token|PROTECTION_STATUS|"
        val accepted = assertIs<ProtocolParseResult.Accepted>(ServiceProtocolParser.parse(frame))
        assertIs<ProtectionStatusRequest>(accepted.request)
        assertEquals(frame, ServiceProtocolParser.format(accepted.request))
        assertRejected(ServiceProtocolParser.parse("2|$token|PROTECTION_STATUS|extra"), ProtocolErrorCode.UNEXPECTED_PAYLOAD)
    }

    @Test
    fun parsesAndFormatsStandingArmAndDisarmProtectionRequests() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"schema":1,"protected_executables":["C:\\Apps\\app.exe"]}""".toByteArray())
        val armFrame = "2|$token|ARM_PROTECTION|$payload"
        val acceptedArm = assertIs<ProtocolParseResult.Accepted>(ServiceProtocolParser.parse(armFrame))
        val armRequest = assertIs<ArmProtectionRequest>(acceptedArm.request)
        assertEquals(payload, armRequest.encodedProtection)
        assertEquals(armFrame, ServiceProtocolParser.format(armRequest))
        assertRejected(ServiceProtocolParser.parse("2|$token|ARM_PROTECTION|"), ProtocolErrorCode.INVALID_FRAME)

        val disarmFrame = "2|$token|DISARM_PROTECTION|"
        val acceptedDisarm = assertIs<ProtocolParseResult.Accepted>(ServiceProtocolParser.parse(disarmFrame))
        assertIs<DisarmProtectionRequest>(acceptedDisarm.request)
        assertEquals(disarmFrame, ServiceProtocolParser.format(acceptedDisarm.request))
        assertRejected(ServiceProtocolParser.parse("2|$token|DISARM_PROTECTION|extra"), ProtocolErrorCode.UNEXPECTED_PAYLOAD)
    }

    @Test
    fun rejectsUnexpectedPayloadsAndInvalidBase64url() {
        assertRejected(ServiceProtocolParser.parse("2|$token|STOP|payload"), ProtocolErrorCode.UNEXPECTED_PAYLOAD)
        val decoded = assertIs<ConfigPayloadResult.Rejected>(ServiceProtocolParser.decodeConfig("not base64url!"))
        assertEquals(ProtocolErrorCode.INVALID_BASE64URL, decoded.error.code)
    }

    @Test
    fun rejectsFramesOutsideTheVersionedBoundedContract() {
        assertRejected(ServiceProtocolParser.parse("1|$token|STATUS|"), ProtocolErrorCode.UNSUPPORTED_VERSION)
        assertRejected(ServiceProtocolParser.parse("2|$token|STATUS|extra"), ProtocolErrorCode.UNEXPECTED_PAYLOAD)
        assertRejected(ServiceProtocolParser.parse("2|short|STATUS|"), ProtocolErrorCode.INVALID_AUTH_PROOF)
        assertRejected(ServiceProtocolParser.parse("x".repeat(MAX_FRAME_BYTES + 1)), ProtocolErrorCode.FRAME_TOO_LARGE)
    }

    private fun assertRejected(result: ProtocolParseResult, expected: ProtocolErrorCode) {
        val rejected = assertIs<ProtocolParseResult.Rejected>(result)
        assertEquals(expected, rejected.error.code)
    }
}
