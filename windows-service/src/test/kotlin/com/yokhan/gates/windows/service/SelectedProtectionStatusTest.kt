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
