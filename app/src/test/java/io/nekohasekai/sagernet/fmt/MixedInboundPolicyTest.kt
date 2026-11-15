package io.nekohasekai.sagernet.fmt

import com.yokhan.gates.app.routing.WholeTrafficPolicy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MixedInboundPolicyTest {
    private val wholeTraffic = WholeTrafficPolicy(wholeTrafficMode = true)
    private val split = WholeTrafficPolicy(wholeTrafficMode = false)

    @Test
    fun wholeTrafficSuppressesLocalInboundEvenWhenLegacySwitchIsOn() {
        assertFalse(
            shouldCreateMixedInbound(
                policy = wholeTraffic,
                isVpnServiceMode = true,
                isUsbBridgeEnabled = false,
                enableLocalInbound = true,
                stealthMode = false,
            ),
        )
    }

    @Test
    fun wholeTrafficSuppressesUsbBridgeInboundEvenWhenEnabled() {
        assertFalse(
            shouldCreateMixedInbound(
                policy = wholeTraffic,
                isVpnServiceMode = true,
                isUsbBridgeEnabled = true,
                enableLocalInbound = true,
                stealthMode = false,
            ),
        )
    }

    @Test
    fun splitModeKeepsLocalInboundWhenEnabled() {
        assertTrue(
            shouldCreateMixedInbound(
                policy = split,
                isVpnServiceMode = true,
                isUsbBridgeEnabled = false,
                enableLocalInbound = true,
                stealthMode = false,
            ),
        )
    }

    @Test
    fun splitModeKeepsUsbBridgeInbound() {
        assertTrue(
            shouldCreateMixedInbound(
                policy = split,
                isVpnServiceMode = true,
                isUsbBridgeEnabled = true,
                enableLocalInbound = false,
                stealthMode = false,
            ),
        )
    }

    @Test
    fun splitModeStillSuppressesLocalInboundInStealthMode() {
        assertFalse(
            shouldCreateMixedInbound(
                policy = split,
                isVpnServiceMode = true,
                isUsbBridgeEnabled = false,
                enableLocalInbound = true,
                stealthMode = true,
            ),
        )
    }

    @Test
    fun splitModeWithoutLegacySwitchesKeepsNoListener() {
        assertFalse(
            shouldCreateMixedInbound(
                policy = split,
                isVpnServiceMode = true,
                isUsbBridgeEnabled = false,
                enableLocalInbound = false,
                stealthMode = false,
            ),
        )
    }

    @Test
    fun proxyOnlyModeKeepsItsProductListener() {
        assertTrue(
            shouldCreateMixedInbound(
                policy = wholeTraffic,
                isVpnServiceMode = false,
                isUsbBridgeEnabled = false,
                enableLocalInbound = true,
                stealthMode = false,
            ),
        )
    }
}
