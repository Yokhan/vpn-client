package com.yokhan.gates.app.roomnet

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.Base64

class RoomNetContractTest {
    @Test
    fun derivesCanonicalSameOriginWebSocketAndPreservesApiPrefix() {
        assertEquals(
            "wss://api.example:8443/gates/api/app/roomnet/ws?device=phone+%2F+one&room=Room+%26+Two",
            canonicalRoomNetWebSocketUrl(
                "https://api.example:8443/gates/",
                "",
                "phone / one",
                "Room & Two",
            ),
        )
        assertEquals(
            "https://api.example/gates/api/app/roomnet",
            roomNetApiUrl("https://api.example/gates/"),
        )
    }

    @Test
    fun rejectsUntrustedOrMalformedOriginsAndAdvertisedSocketUrls() {
        val rejected = listOf(
            "http://api.example/api/app/roomnet/ws",
            "wss://attacker.example/api/app/roomnet/ws",
            "ws://api.example/api/app/roomnet/ws",
            "wss://api.example/api/app/roomnet/other",
            "wss://user@api.example/api/app/roomnet/ws",
            "wss://api.example/api/app/roomnet/ws?token=leak",
            "wss://api.example/api/app/roomnet/ws#fragment",
        )
        rejected.forEach { advertised ->
            assertFailsWith<IllegalArgumentException> {
                canonicalRoomNetWebSocketUrl("https://api.example", advertised, "device", "")
            }
        }
        listOf(
            "https://user@api.example/gates",
            "https://api.example/gates?room=x",
            "https://api.example/gates/../admin",
            "https://api.example/gates/%2e%2e/admin",
            "http://api.example/gates",
        ).forEach { base ->
            assertFailsWith<IllegalArgumentException> {
                roomNetApiUrl(base)
            }
        }
    }

    @Test
    fun validatesRoomMembershipPortsAndStrictPayloadEncoding() {
        val allowed = roomNetAllowedPorts(listOf(27015, 27016, 0), listOf(27016, 27017))
        assertEquals(setOf(27016), allowed)
        assertEquals(setOf(27015), roomNetAllowedPorts(listOf(27015), emptyList()))
        val valid = RoomNetDatagram("room-a", "self", "peer", 27016, "flow_1", "aGVsbG8=")
        assertContentEquals(
            "hello".toByteArray(),
            validateRoomNetDatagram(valid, "room-a", "self", setOf("peer"), allowed),
        )
        assertNull(validateRoomNetDatagram(valid.copy(room = "other"), "room-a", "self", setOf("peer"), allowed))
        assertNull(validateRoomNetDatagram(valid.copy(to = "other"), "room-a", "self", setOf("peer"), allowed))
        assertNull(validateRoomNetDatagram(valid.copy(from = "unknown"), "room-a", "self", setOf("peer"), allowed))
        assertNull(validateRoomNetDatagram(valid.copy(from = "self"), "room-a", "self", setOf("self"), allowed))
        assertNull(validateRoomNetDatagram(valid.copy(destinationPort = 27015), "room-a", "self", setOf("peer"), allowed))
        assertNull(validateRoomNetDatagram(valid.copy(flow = "bad flow"), "room-a", "self", setOf("peer"), allowed))
        assertNull(validateRoomNetDatagram(valid.copy(encodedPayload = "aGVsbG8"), "room-a", "self", setOf("peer"), allowed))
        val oversized = Base64.getEncoder().encodeToString(ByteArray(ROOMNET_MAX_DATAGRAM_BYTES + 1))
        assertNull(validateRoomNetDatagram(valid.copy(encodedPayload = oversized), "room-a", "self", setOf("peer"), allowed))
    }

    @Test
    fun permitsOnlyDistinctPeerLoopbackEndpointsAndEnforcesFairCaps() {
        assertTrue(areRoomNetAddressesDistinct("127.77.1.9", listOf("127.77.1.10", "127.77.1.11")))
        assertFalse(areRoomNetAddressesDistinct("127.77.1.9", listOf("127.77.1.9")))
        assertFalse(areRoomNetAddressesDistinct("127.77.1.9", listOf("127.0.0.1")))
        assertFalse(areRoomNetAddressesDistinct("127.77.01.9", listOf("127.77.1.10")))
        assertEquals("127.77.1.10", roomNetPeerSocketAddress("127.77.1.10", 27015)?.address?.hostAddress)
        assertNull(roomNetPeerSocketAddress("192.168.1.10", 27015))
        assertNull(roomNetPeerSocketAddress("127.77.1.10", 0))
        assertFalse(canCreateRoomNetFlow("peer-a", ROOMNET_MAX_FLOWS_PER_PEER, 2))
        assertTrue(canCreateRoomNetFlow("peer-b", 0, 8))
        assertFalse(canCreateRoomNetFlow("peer-b", 0, ROOMNET_MAX_FLOW_COUNT))
        assertTrue(roomNetResponseFlowMatches("peer-a", 27015, "peer-a", 27015))
        assertFalse(roomNetResponseFlowMatches("peer-a", 27015, "peer-b", 27015))
    }

    @Test
    fun refreshesSlidingTtlAndStopsPolicyOrUnrenewedAuthFailures() {
        val expiredAt = roomNetFlowExpiresAt(1_000)
        assertTrue(isRoomNetFlowExpired(1_000, expiredAt))
        assertFalse(isRoomNetFlowExpired(expiredAt - 1, expiredAt))
        assertEquals(
            RoomNetRetryDecision.RETRY,
            decideRoomNetRetry(1008, "session expired", "old", "new"),
        )
        assertEquals(
            RoomNetRetryDecision.STOP,
            decideRoomNetRetry(1008, "policy violation", "old", "new"),
        )
        assertEquals(RoomNetRetryDecision.STOP, decideRoomNetRetry(401, "", "same", "same"))
        assertEquals(RoomNetRetryDecision.STOP, decideRoomNetRetry(403, "", "old", "new"))
        assertEquals(
            RoomNetRetryDecision.STOP,
            decideRoomNetRetry(401, "", "old", "new", consecutiveAuthRetries = ROOMNET_AUTH_RETRY_STREAK_LIMIT),
        )
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (1..7).map(::roomNetReconnectDelayMillis))
    }

    @Test
    fun newerSessionGenerationInvalidatesLateUpdatesFromOlderSessions() {
        val generations = RoomNetSessionGeneration()
        val first = generations.next()
        assertTrue(generations.isCurrent(first))

        val replacement = generations.next()
        assertFalse(generations.isCurrent(first))
        assertTrue(generations.isCurrent(replacement))

        val stopped = generations.next()
        assertFalse(generations.isCurrent(replacement))
        assertTrue(generations.isCurrent(stopped))
    }
}
