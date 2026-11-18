package com.yokhan.gates.windows.service

import com.yokhan.gates.windows.service.wfp.InstalledWfpRule
import com.yokhan.gates.windows.service.wfp.WfpAction
import com.yokhan.gates.windows.service.wfp.WfpAleLayer
import com.yokhan.gates.windows.service.wfp.WfpGuardBackend
import com.yokhan.gates.windows.service.wfp.WfpInspection
import com.yokhan.gates.windows.service.wfp.WfpPolicy
import com.yokhan.gates.windows.service.wfp.WfpPolicyPlanner
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SelectedProtectionStatusTest {
    @Test
    fun armedRequiresExpectedPersistentAndDynamicRulesTogether() {
        val backend = FakeBackend()
        val config = ValidatedSingBoxConfig(
            """{"inbounds":[{"type":"tun","address":["10.0.0.1/30"]}],"outbounds":[{"type":"direct","tag":"default"}]}""",
            SelectedAppProtection(listOf("C:\\Apps\\browser.exe")),
        )
        val protection = WfpSelectedAppProtection(
            "C:\\Program Files\\GATES\\sing-box.exe",
            "GATES-test",
            backendFactory = { backend },
            identityResolver = TunIdentityResolver { alias, addresses ->
                TunIdentityResolution.Ready(TunIdentity(alias, addresses, 91L))
            },
        )

        protection.arm(config)
        assertEquals(ProtectionStatus.BLOCKED, protection.status())
        assertEquals(ProtectionPermitResult.PERMITTED, protection.permit(config))
        assertEquals(ProtectionStatus.ARMED, protection.status())
        protection.block()
        assertEquals(ProtectionStatus.BLOCKED, protection.status())
        protection.release()
        assertEquals(ProtectionStatus.DISABLED, protection.status())
        assertTrue(backend.observedPolicies.all { it.scope is com.yokhan.gates.windows.service.wfp.WfpScope.SelectedExecutables })
    }

    @Test
    fun identityNotReadyCannotPermitAndLiveCheckRunsImmediatelyBeforeWfpPermit() {
        val backend = FakeBackend()
        val config = ValidatedSingBoxConfig(
            """{"inbounds":[{"type":"tun","address":["10.0.0.1/30"]}],"outbounds":[{"type":"direct","tag":"default"}]}""",
            SelectedAppProtection(listOf("C:\\Apps\\browser.exe")),
        )
        var resolverCalls = 0
        val protection = WfpSelectedAppProtection(
            "C:\\Program Files\\GATES\\sing-box.exe",
            "GATES-test",
            backendFactory = { backend },
            identityResolver = TunIdentityResolver { alias, addresses ->
                resolverCalls++
                if (resolverCalls == 1) TunIdentityResolution.NotReady
                else TunIdentityResolution.Ready(TunIdentity(alias, addresses, 92L))
            },
        )
        protection.arm(config)

        var aliveChecks = 0
        assertEquals(ProtectionPermitResult.IDENTITY_NOT_READY, protection.permit(config) { aliveChecks++ })
        assertEquals(0, aliveChecks)
        assertTrue(backend.lifecycle.isEmpty())

        assertEquals(ProtectionPermitResult.PERMITTED, protection.permit(config) {
            aliveChecks++
            backend.lifecycle += "alive"
        })
        assertEquals(1, aliveChecks)
        assertEquals(listOf("alive", "permit"), backend.lifecycle)
        assertEquals(92L, backend.policy?.verifiedTunLuid)
    }

    @Test
    fun unexpectedIdentityBackendErrorPropagatesWithoutPermitAttempt() {
        val backend = FakeBackend()
        val config = ValidatedSingBoxConfig(
            """{"inbounds":[{"type":"tun","address":["10.0.0.1/30"]}],"outbounds":[{"type":"direct","tag":"default"}]}""",
            SelectedAppProtection(listOf("C:\\Apps\\browser.exe")),
        )
        val protection = WfpSelectedAppProtection(
            "C:\\Program Files\\GATES\\sing-box.exe",
            "GATES-test",
            backendFactory = { backend },
            identityResolver = TunIdentityResolver { _, _ -> throw IOException("identity backend failed") },
        )
        protection.arm(config)
        var aliveChecks = 0

        assertIs<IOException>(runCatching { protection.permit(config) { aliveChecks++ } }.exceptionOrNull())

        assertEquals(0, aliveChecks)
        assertTrue(backend.lifecycle.isEmpty())
    }

    @Test
    fun addressMatchingCanonicalizesIpv6AndAllowsOnlyLinkLocalExtras() {
        val expected = setOf("2001:db8::1", "10.0.0.1")
        assertTrue(tunAddressesMatch(expected, listOf("2001:0db8:0:0:0:0:0:1", "10.0.0.1", "fe80::1234%12")))
        assertFalse(tunAddressesMatch(expected, listOf("2001:db8::1", "10.0.0.1", "2001:db8::2")))
        assertFalse(tunAddressesMatch(expected, listOf("2001:db8::1", "10.0.0.1", "10.0.0.2")))
        assertEquals("6:20010db8000000000000000000000001", normalizeNumericIpAddress("2001:0db8:0:0:0:0:0:1"))
    }

    @Test
    fun extractsIpv4AndIpv6UnicastAddressesFromNativeTableForTargetLuidOnly() {
        val targetLuid = 14918723521478656L
        val otherLuid = 6755399441055744L
        val rowBytes = 80L
        val headerBytes = 8L
        val table = com.sun.jna.Memory(headerBytes + rowBytes * 3L).apply {
            clear()
            setInt(0L, 3)
            // Row 0: target IPv4 172.30.248.1
            setShort(headerBytes + 0L, 2.toShort())
            write(headerBytes + 4L, byteArrayOf(172.toByte(), 30, 248.toByte(), 1), 0, 4)
            setLong(headerBytes + 32L, targetLuid)
            // Row 1: other interface IPv4 127.0.0.1
            val r1 = headerBytes + rowBytes
            setShort(r1 + 0L, 2.toShort())
            write(r1 + 4L, byteArrayOf(127, 0, 0, 1), 0, 4)
            setLong(r1 + 32L, otherLuid)
            // Row 2: target IPv6 fe80::1
            val r2 = headerBytes + rowBytes * 2L
            setShort(r2 + 0L, 23.toShort())
            write(
                r2 + 8L,
                byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
                0,
                16,
            )
            setLong(r2 + 32L, targetLuid)
        }

        val extracted = extractUnicastAddressesForLuid(table, targetLuid)
        assertEquals(2, extracted.size)
        assertEquals("172.30.248.1", extracted[0])
        assertTrue(tunAddressesMatch(setOf("172.30.248.1"), extracted))
        assertTrue(isInterfaceNotReadyWin32Error(2))
        assertTrue(isInterfaceNotReadyWin32Error(87))
        assertTrue(isInterfaceNotReadyWin32Error(1168))
        assertFalse(isInterfaceNotReadyWin32Error(5))
    }

    private class FakeBackend : WfpGuardBackend {
        var policy: WfpPolicy? = null
        var rules: List<InstalledWfpRule> = emptyList()
        val observedPolicies = mutableListOf<WfpPolicy>()
        val lifecycle = mutableListOf<String>()

        override fun armBlocked(policy: WfpPolicy): WfpInspection {
            this.policy = policy
            observedPolicies += policy
            rules = installed(WfpPolicyPlanner.plan(policy, includeTunnelPermit = false))
            return inspect()
        }

        override fun permitTunnel(verifiedTunLuid: Long) {
            lifecycle += "permit"
            val current = requireNotNull(policy)
            policy = current.copy(verifiedTunLuid = verifiedTunLuid)
            observedPolicies += policy!!
            rules = installed(WfpPolicyPlanner.plan(requireNotNull(policy)))
        }

        override fun block() {
            val current = requireNotNull(policy).copy(verifiedTunLuid = null)
            policy = current
            observedPolicies += current
            rules = installed(WfpPolicyPlanner.plan(current, includeTunnelPermit = false))
        }

        override fun disarm(): WfpInspection { rules = emptyList(); policy = null; return inspect() }
        override fun inspect() = WfpInspection(rules)
        override fun closePermitsOnly() = block()
        override fun close() = Unit

        private fun installed(plans: List<com.yokhan.gates.windows.service.wfp.WfpRulePlan>) = plans.map {
            InstalledWfpRule(it.key, it.action, it.persistent, it.layer, it.purpose)
        }
    }
}
