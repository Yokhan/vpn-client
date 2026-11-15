package com.yokhan.gates.app.routing

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WholeTrafficPolicyTest {
    @Test
    fun wholeTrafficDisablesAdvancedRoutingAndInboundSurfaces() {
        val policy = WholeTrafficPolicy(wholeTrafficMode = true)

        assertFalse(policy.applyPerAppSplit)
        assertFalse(policy.applyAutoDirectRules)
        assertFalse(policy.applyLanBypass)
        assertFalse(policy.allowLanAccess)
        assertFalse(policy.allowExternalListeners)
        assertFalse(policy.allowInboundListeners)
        assertFalse(policy.applyUserDirectRules)
    }

    @Test
    fun splitModeKeepsAdvancedRoutingAndInboundSurfaces() {
        val policy = WholeTrafficPolicy(wholeTrafficMode = false)

        assertTrue(policy.applyPerAppSplit)
        assertTrue(policy.applyAutoDirectRules)
        assertTrue(policy.applyLanBypass)
        assertTrue(policy.allowLanAccess)
        assertTrue(policy.allowExternalListeners)
        assertTrue(policy.allowInboundListeners)
        assertTrue(policy.applyUserDirectRules)
    }
}
