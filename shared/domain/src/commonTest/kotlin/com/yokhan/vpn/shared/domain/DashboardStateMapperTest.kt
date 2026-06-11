package com.yokhan.gates.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DashboardStateMapperTest {
    @Test
    fun mapsConnectedStateToAnimatedDashboard() {
        val state = DashboardStateMapper.from(
            ConnectionSnapshot(
                status = ConnectionStatus.Connected,
                activeProfile = ProfileSummary(
                    id = 42,
                    name = "Bridge-igor",
                    protocol = VpnProtocol.Vless,
                    subscriptionLabel = "РџРѕРґРїРёСЃРєР° #1776115554141",
                ),
                traffic = TrafficSnapshot(
                    uploadBytesPerSecond = 12_492,
                    downloadBytesPerSecond = 49_869,
                    latencyMs = 103,
                    capturedAtMillis = 1_000,
                ),
                connectedForMillis = 84_000,
            ),
        )

        assertEquals(ConnectionStatus.Connected, state.connectionStatus)
        assertEquals("Bridge-igor", state.activeProfileName)
        assertEquals("VLESS", state.activeProfileProtocolLabel)
        assertEquals("VLESS В· РџРѕРґРїРёСЃРєР° #1776115554141", state.activeProfileSubtitle)
        assertEquals(RateDisplay(12_492), state.uploadRate)
        assertEquals(RateDisplay(49_869), state.downloadRate)
        assertEquals(LatencyDisplay(103), state.latency)
        assertEquals("12.2 KB/s", state.uploadLabel)
        assertEquals("48.7 KB/s", state.downloadLabel)
        assertEquals("103 РјСЃ", state.latencyLabel)
        assertEquals("00:01:24", state.timerLabel)
        assertTrue(state.shouldAnimateSpace)
        assertTrue(state.canRunConnectionTest)
        assertFalse(state.isConnectionTestRunning)
    }

    @Test
    fun mapsDisconnectedStateWithoutProfileSafely() {
        val state = DashboardStateMapper.from(
            ConnectionSnapshot(
                status = ConnectionStatus.Disconnected,
                activeProfile = null,
                traffic = TrafficSnapshot(
                    uploadBytesPerSecond = 0,
                    downloadBytesPerSecond = 0,
                    capturedAtMillis = 0,
                ),
            ),
        )

        assertEquals("РќРµС‚ РєРѕРЅС„РёРіСѓСЂР°С†РёРё", state.activeProfileName)
        assertEquals("Р’С‹Р±РµСЂРёС‚Рµ РїСЂРѕС„РёР»СЊ", state.activeProfileSubtitle)
        assertEquals(RateDisplay(0), state.uploadRate)
        assertEquals(RateDisplay(0), state.downloadRate)
        assertEquals(LatencyDisplay(null), state.latency)
        assertEquals("0 B/s", state.uploadLabel)
        assertEquals("--", state.latencyLabel)
        assertFalse(state.shouldAnimateSpace)
        assertFalse(state.canRunConnectionTest)
    }

    @Test
    fun animatesWhileConnectingButDoesNotAllowConnectionTest() {
        val state = DashboardStateMapper.from(
            ConnectionSnapshot(
                status = ConnectionStatus.Connecting,
                activeProfile = ProfileSummary(
                    id = 7,
                    name = "Relay-igor",
                    protocol = VpnProtocol.Vless,
                ),
                traffic = TrafficSnapshot(
                    uploadBytesPerSecond = 0,
                    downloadBytesPerSecond = 0,
                    capturedAtMillis = 0,
                ),
            ),
        )

        assertTrue(state.shouldAnimateSpace)
        assertFalse(state.canRunConnectionTest)
    }

    @Test
    fun preservesProtocolLabelWhenProtocolEnumIsUnknown() {
        val state = DashboardStateMapper.from(
            ConnectionSnapshot(
                status = ConnectionStatus.Disconnected,
                activeProfile = ProfileSummary(
                    id = 8,
                    name = "Custom-profile",
                    protocol = VpnProtocol.Unknown,
                    protocolLabel = "TUIC",
                    subscriptionLabel = "Manual",
                ),
                traffic = TrafficSnapshot(
                    uploadBytesPerSecond = 0,
                    downloadBytesPerSecond = 0,
                    capturedAtMillis = 0,
                ),
            ),
        )

        assertEquals("TUIC", state.activeProfileProtocolLabel)
        assertEquals("TUIC В· Manual", state.activeProfileSubtitle)
    }

    @Test
    fun rejectsNegativeTelemetryValues() {
        assertFailsWith<IllegalArgumentException> {
            RateDisplay(-1)
        }
        assertFailsWith<IllegalArgumentException> {
            LatencyDisplay(-1)
        }
    }
}
