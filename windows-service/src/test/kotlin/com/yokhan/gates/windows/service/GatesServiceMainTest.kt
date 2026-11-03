package com.yokhan.gates.windows.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatesServiceMainTest {
    @Test
    fun localSystemIdentityUsesTheBuiltInSystemSidOnly() {
        assertTrue(isLocalSystemSid("S-1-5-18"))
        assertFalse(isLocalSystemSid("S-1-5-32-544"))
        assertFalse(isLocalSystemSid("S-1-5-21-1-2-3-1001"))
        assertFalse(isLocalSystemSid(null))
    }

    @Test
    fun serviceRunnerRejectsUnsupportedArgumentsBeforeCheckingIdentity() {
        assertEquals(2, runGatesServiceProcess(arrayOf("--unexpected")))
    }

    @Test
    fun startupFailureClosesAllPartiallyOwnedResourcesBeforeReturning() {
        val closed = mutableListOf<String>()
        val lifecycle = GatesServiceLifecycle()

        kotlin.test.assertFailsWith<IllegalStateException> {
            lifecycle.run {
                own("supervisor") { closed += it }
                own("executor") { closed += it }
                error("API startup failed")
            }
        }

        assertEquals(listOf("executor", "supervisor"), closed)
    }
}
