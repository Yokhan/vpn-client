package com.yokhan.gates.windows.service.wfp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WfpPolicyPlannerTest {
    @Test
    fun selectedScopeHasOnlyExactAppDeniesAndSameAppTunPermits() {
        val selected = "C:\\Program Files\\Example\\client.exe"
        val policy = WfpPolicy(
            scope = WfpScope.SelectedExecutables(listOf(selected)),
            bypassAbsoluteExePaths = emptyList(),
            coreAbsoluteExePath = "C:\\GATES\\sing-box.exe",
            verifiedTunLuid = 42,
        )

        val rules = WfpPolicyPlanner.plan(policy)
        assertEquals(4, rules.count { it.persistent && it.action == WfpAction.BLOCK })
        assertEquals(4, rules.count { !it.persistent && it.action == WfpAction.PERMIT })
        assertTrue(rules.all { rule ->
            rule.conditions.any { it == WfpCondition.ApplicationId(selected) } &&
                (rule.action == WfpAction.BLOCK || rule.conditions.contains(WfpCondition.LocalInterfaceLuid(42L)))
        })
        assertTrue(rules.filter { it.action == WfpAction.PERMIT }.all { it.weight > 0x1000L })
        assertFalse(rules.any { it.conditions.contains(WfpCondition.Loopback) })
        assertFalse(rules.any { it.purpose.contains("dhcp") })
        assertEquals(WfpAleLayer.entries.toSet(), rules.map(WfpRulePlan::layer).toSet())
    }

    @Test
    fun selectedScopeWithoutVerifiedTunRemainsDenyOnly() {
        val policy = WfpPolicy(
            WfpScope.SelectedExecutables(listOf("C:\\Tools\\app.exe")),
            emptyList(),
            "C:\\GATES\\sing-box.exe",
        )

        val rules = WfpPolicyPlanner.plan(policy)
        assertEquals(4, rules.size)
        assertTrue(rules.all { it.persistent && it.action == WfpAction.BLOCK })
    }

    @Test
    fun allScopeUsesGlobalPersistentFallbackWithoutBroadLoopbackOrHelperExemptions() {
        val policy = WfpPolicy(
            WfpScope.All,
            listOf("C:\\GATES\\browser.exe"),
            "C:\\GATES\\sing-box.exe",
            verifiedTunLuid = 17,
        )

        val rules = WfpPolicyPlanner.plan(policy)
        assertEquals(4, rules.count { it.persistent && it.conditions.isEmpty() })
        assertTrue(rules.filterNot(WfpRulePlan::persistent).all { it.action == WfpAction.PERMIT })
        assertTrue(rules.none { it.conditions.contains(WfpCondition.Loopback) })
        assertTrue(rules.none { it.purpose.contains("dhcp") })
        assertFalse(rules.any { rule ->
            rule.conditions.any { it is WfpCondition.ApplicationId && it.absoluteExePath == "C:\\Windows\\System32\\svchost.exe" }
        })
        assertTrue(rules.filter { it.action == WfpAction.PERMIT }.all { it.weight > 0x1000L })
    }

    @Test
    fun policyRejectsSelectedCoreAndRelativePaths() {
        val core = "C:\\GATES\\sing-box.exe"
        val selectedCore = runCatching {
            WfpPolicy(WfpScope.SelectedExecutables(listOf(core)), emptyList(), core)
        }
        assertTrue(selectedCore.isFailure)
        assertTrue(runCatching {
            WfpPolicy(WfpScope.SelectedExecutables(listOf("client.exe")), emptyList(), core)
        }.isFailure)
        listOf(
            "\\\\server\\share\\client.exe",
            "C:\\Apps\\..\\client.exe",
            "C:\\Apps\\client.exe:stream",
            "\\\\?\\C:\\Apps\\client.exe",
        ).forEach { unsafe ->
            assertTrue(runCatching {
                WfpPolicy(WfpScope.SelectedExecutables(listOf(unsafe)), emptyList(), core)
            }.isFailure, "Expected rejection for $unsafe")
        }
    }

    @Test
    fun backendConstructionDoesNotCreateOrCallApi() {
        var factoryCalls = 0
        val backend = NativeWfpGuardBackend {
            factoryCalls++
            FakeWfpApi()
        }
        assertEquals(0, factoryCalls)
        backend.close()
        assertEquals(0, factoryCalls)
    }

    @Test
    fun explicitInspectionOnFreshBackendQueriesPersistentProviderRules() {
        val fake = FakeWfpApi()
        val policy = WfpPolicy(
            WfpScope.SelectedExecutables(listOf("C:\\Apps\\browser.exe")),
            emptyList(),
            "C:\\GATES\\sing-box.exe",
        )
        fake.replacePersistentRules(WfpPolicyPlanner.plan(policy).filter(WfpRulePlan::persistent))
        var factoryCalls = 0
        val backend = NativeWfpGuardBackend { factoryCalls++; fake }

        assertEquals(4, backend.inspect().persistentBlockCount)
        assertEquals(1, factoryCalls)
    }

    @Test
    fun disarmOnFreshBackendEnumeratesAndRemovesOnlyOwnedRules() {
        val fake = FakeWfpApi()
        val policy = WfpPolicy(
            WfpScope.SelectedExecutables(listOf("C:\\Apps\\browser.exe")),
            emptyList(),
            "C:\\GATES\\sing-box.exe",
        )
        fake.replacePersistentRules(WfpPolicyPlanner.plan(policy).filter(WfpRulePlan::persistent))
        val backend = NativeWfpGuardBackend { fake }

        assertTrue(backend.disarm().rules.isEmpty())
        assertTrue(fake.rules.isEmpty())
    }

    @Test
    fun closePermitsOnlyLeavesPersistentRulesIntact() {
        val fake = FakeWfpApi()
        val backend = NativeWfpGuardBackend { fake }
        val policy = WfpPolicy(
            WfpScope.SelectedExecutables(listOf("C:\\Apps\\browser.exe")),
            emptyList(),
            "C:\\GATES\\sing-box.exe",
        )
        backend.armBlocked(policy)
        backend.permitTunnel(9)
        backend.closePermitsOnly()

        assertEquals(4, fake.rules.count { it.persistent && it.action == WfpAction.BLOCK })
        assertEquals(0, fake.rules.count { !it.persistent })
        assertEquals(2, fake.dynamicCloseCalls)
    }

    private class FakeWfpApi : WfpApi {
        var rules = emptyList<InstalledWfpRule>()
        var dynamicCloseCalls = 0

        override fun replacePersistentRules(rules: List<WfpRulePlan>) {
            this.rules = this.rules.filterNot(InstalledWfpRule::persistent) + rules.map(::installed)
        }

        override fun replaceDynamicRules(rules: List<WfpRulePlan>) {
            this.rules = this.rules.filter(InstalledWfpRule::persistent) + rules.map(::installed)
        }

        override fun removeDynamicRules() {
            dynamicCloseCalls++
            rules = rules.filter(InstalledWfpRule::persistent)
        }

        override fun removeOwnedPersistentRules() {
            rules = rules.filterNot(InstalledWfpRule::persistent)
        }

        override fun inspectOwnedRules() = WfpInspection(rules)
        override fun close() = removeDynamicRules()

        private fun installed(rule: WfpRulePlan) = InstalledWfpRule(
            rule.key, rule.action, rule.persistent, rule.layer, rule.purpose,
        )
    }
}
