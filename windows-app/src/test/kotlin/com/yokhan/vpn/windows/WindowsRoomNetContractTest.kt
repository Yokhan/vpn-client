package com.yokhan.gates.windows

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsRoomNetContractTest {
    @Test
    fun derivesCanonicalEndpointWithConfiguredOriginAndPrefix() {
        assertEquals(
            "wss://api.example:8443/gates/api/app/roomnet/ws?device=desk+%2F+one&room=Room+%26+Two",
            canonicalWindowsRoomNetWebSocketUrl(
                "https://api.example:8443/gates/",
                "",
                "desk / one",
                "Room & Two",
            ),
        )
        assertEquals(
            "wss://api.example/gates/api/app/roomnet/ws?device=desk",
            canonicalWindowsRoomNetWebSocketUrl(
                "https://api.example/gates",
                "wss://api.example/gates/api/app/roomnet/ws",
                "desk",
                "",
            ),
        )
    }

    @Test
    fun migratesOnlyExactRetiredEndpointAndRejectsUntrustedVariants() {
        val canonical = "wss://api.example/api/app/roomnet/ws?device=d"
        assertEquals(
            canonical,
            canonicalWindowsRoomNetWebSocketUrl(
                "https://api.example",
                WINDOWS_ROOMNET_RETIRED_WEBSOCKET_URL,
                "d",
                "",
            ),
        )

        listOf(
            "wss://attacker.example/api/app/roomnet/ws",
            "ws://api.example/api/app/roomnet/ws",
            "wss://api.example/api/app/roomnet/other",
            "wss://user@api.example/api/app/roomnet/ws",
            "wss://api.example/api/app/roomnet/ws?existing=1",
            "wss://api.example/api/app/roomnet/ws#fragment",
            "wss://cloak.yokhanfitness.ru/api/app/roomnet/ws/",
        ).forEach { advertised ->
            assertFailsWith<IllegalArgumentException> {
                canonicalWindowsRoomNetWebSocketUrl("https://api.example", advertised, "d", "")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            canonicalWindowsRoomNetWebSocketUrl("https://api.example/path?token=x", "", "d", "")
        }
    }

    @Test
    fun validatesRoomPeerDestinationAndSharedPortBeforeStrictPayloadDecode() {
        val ports = windowsRoomNetAllowedPorts(listOf(27015, 27016, 0), listOf(27016, 27017))
        assertEquals(setOf(27016), ports)
        assertEquals(setOf(27015), windowsRoomNetAllowedPorts(listOf(27015), emptyList()))

        val valid = WindowsRoomNetDatagram("room-a", "self", "peer", 27016, "f1", "aGVsbG8=")
        assertContentEquals(
            "hello".toByteArray(),
            validateWindowsRoomNetDatagram(valid, "room-a", "self", setOf("peer"), ports),
        )
        assertNull(validateWindowsRoomNetDatagram(valid.copy(room = "room-b"), "room-a", "self", setOf("peer"), ports))
        assertNull(validateWindowsRoomNetDatagram(valid.copy(to = "other"), "room-a", "self", setOf("peer"), ports))
        assertNull(validateWindowsRoomNetDatagram(valid.copy(from = "unknown"), "room-a", "self", setOf("peer"), ports))
        assertNull(validateWindowsRoomNetDatagram(valid.copy(from = "self"), "room-a", "self", setOf("self"), ports))
        assertNull(validateWindowsRoomNetDatagram(valid.copy(destinationPort = 27015), "room-a", "self", setOf("peer"), ports))
        assertNull(validateWindowsRoomNetDatagram(valid.copy(flow = "bad flow"), "room-a", "self", setOf("peer"), ports))
        assertNull(validateWindowsRoomNetDatagram(valid.copy(flow = "x".repeat(257)), "room-a", "self", setOf("peer"), ports))
        assertNull(validateWindowsRoomNetDatagram(valid.copy(encodedPayload = "aGVsbG8"), "room-a", "self", setOf("peer"), ports))
        val oversized = java.util.Base64.getEncoder().encodeToString(ByteArray(WINDOWS_ROOMNET_MAX_DATAGRAM_BYTES + 1))
        assertNull(validateWindowsRoomNetDatagram(valid.copy(encodedPayload = oversized), "room-a", "self", setOf("peer"), ports))
    }

    @Test
    fun responseFlowsMustMatchPeerAndPortAndFlowTableIsBoundedAndExpires() {
        assertTrue(isWindowsRoomNetResponseFlowMatch("peer-a", 27016, "peer-a", 27016))
        assertFalse(isWindowsRoomNetResponseFlowMatch("peer-a", 27016, "peer-b", 27016))
        assertFalse(isWindowsRoomNetResponseFlowMatch("peer-a", 27016, "peer-a", 27017))

        val flows = linkedMapOf<String, Long>()
        putBoundedWindowsRoomNetFlow(flows, "expired", 10L, 20L) { it }
        putBoundedWindowsRoomNetFlow(flows, "live", 40L, 20L) { it }
        assertEquals(mapOf("live" to 40L), flows)

        repeat(WINDOWS_ROOMNET_MAX_FLOW_COUNT + 1) { index ->
            putBoundedWindowsRoomNetFlow(flows, "flow-$index", 100L + index, 0L) { it }
        }
        assertEquals(WINDOWS_ROOMNET_MAX_FLOW_COUNT, flows.size)
        assertTrue("flow-${WINDOWS_ROOMNET_MAX_FLOW_COUNT}" in flows)
    }

    @Test
    fun slidingFlowTtlKeepsActiveSessionsAndExpiresIdleOnes() {
        val activityAt = 1_000L
        val expiresAt = windowsRoomNetFlowExpiresAt(activityAt)
        assertTrue(isWindowsRoomNetFlowExpired(activityAt, expiresAt))

        val refreshedAt = expiresAt - 1
        val refreshedExpiry = windowsRoomNetFlowExpiresAt(refreshedAt)
        assertFalse(isWindowsRoomNetFlowExpired(refreshedAt, expiresAt))
        assertTrue(isWindowsRoomNetFlowExpired(refreshedAt, refreshedExpiry))
    }

    @Test
    fun peerSocketsUseDistinctWhitelistedLoopbackAddressesAndPerPeerQuotaIsFair() {
        val first = windowsRoomNetPeerSocketAddress("127.77.1.10", 27015)
        val second = windowsRoomNetPeerSocketAddress("127.77.1.11", 27015)
        assertEquals("127.77.1.10", first?.address?.hostAddress)
        assertEquals("127.77.1.11", second?.address?.hostAddress)
        assertFalse(first?.address == second?.address)
        assertEquals(null, windowsRoomNetPeerSocketAddress("192.168.1.10", 27015))
        assertEquals(null, windowsRoomNetPeerSocketAddress("127.77.01.10", 27015))

        assertTrue(areWindowsRoomNetAddressesDistinct(
            "127.77.1.9",
            listOf("127.77.1.10", "127.77.1.11"),
            "127.0.0.1",
        ))
        assertFalse(areWindowsRoomNetAddressesDistinct(
            "127.77.1.9",
            listOf("127.77.1.9"),
            "127.0.0.1",
        ))
        assertFalse(areWindowsRoomNetAddressesDistinct(
            "127.77.1.9",
            listOf("127.0.0.1"),
            "127.0.0.1",
        ))

        assertFalse(canCreateWindowsRoomNetFlow("peer-a", WINDOWS_ROOMNET_MAX_FLOWS_PER_PEER, 8))
        assertTrue(canCreateWindowsRoomNetFlow("peer-b", 0, 8))
        assertFalse(canCreateWindowsRoomNetFlow("peer-b", 0, WINDOWS_ROOMNET_MAX_FLOW_COUNT))
    }

    @Test
    fun retryRequiresRotatedTokenForExpiredSessionAndStopsOnPolicyOrRevocation() {
        assertEquals(
            WindowsRoomNetRetryDecision.RETRY,
            decideWindowsRoomNetRetry(1008, "session expired", "old-token", "new-token"),
        )
        assertEquals(
            WindowsRoomNetRetryDecision.STOP,
            decideWindowsRoomNetRetry(1008, "policy violation", "old-token", "new-token"),
        )
        assertEquals(
            WindowsRoomNetRetryDecision.STOP,
            decideWindowsRoomNetRetry(1008, "session expired", "same-token", "same-token"),
        )
        assertEquals(
            WindowsRoomNetRetryDecision.STOP,
            decideWindowsRoomNetRetry(401, "", "old-token", null),
        )
        assertEquals(
            WindowsRoomNetRetryDecision.RETRY,
            decideWindowsRoomNetRetry(401, "", "old-token", "new-token"),
        )
        assertEquals(
            WindowsRoomNetRetryDecision.STOP,
            decideWindowsRoomNetRetry(
                1008,
                "session expired",
                "new-token",
                "another-token",
                consecutiveAuthRetries = WINDOWS_ROOMNET_AUTH_RETRY_STREAK_LIMIT,
            ),
        )
        assertEquals(
            WindowsRoomNetRetryDecision.STOP,
            decideWindowsRoomNetRetry(null, "network error", "token", "token", stopped = true),
        )
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (1..7).map(::windowsRoomNetReconnectDelayMillis))
    }
}
