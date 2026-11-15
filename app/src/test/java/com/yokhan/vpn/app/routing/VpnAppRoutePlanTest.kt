package com.yokhan.gates.app.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VpnAppRoutePlanTest {

    @Test
    fun autoBypassAppliesEvenWhenStaleBypassFlagIsFalse() {
        val plan = VpnAppRoutePlanner.build(
            ownPackageName = "com.yokhan.gates",
            wholeTrafficMode = false,
            proxyApps = false,
            configuredBypass = false,
            needBypassRootUid = false,
            manualPackages = setOf("com.stale.app"),
            allApps = listOf("ru.sberbankmobile", "org.telegram.messenger", "com.yokhan.gates"),
            autoBypassPackages = setOf("ru.sberbankmobile"),
            alwaysOnLockdown = false,
        )

        assertTrue(plan.applyPerAppSplit)
        assertTrue(plan.effectiveBypass)
        assertEquals(setOf("ru.sberbankmobile"), plan.disallowedPackages)
        assertTrue(plan.allowedPackages.isEmpty())
    }

    @Test
    fun allowlistModeExcludesAutoBypassedAppsInsteadOfForcingOnlySelf() {
        val plan = VpnAppRoutePlanner.build(
            ownPackageName = "com.yokhan.gates",
            wholeTrafficMode = false,
            proxyApps = true,
            configuredBypass = false,
            needBypassRootUid = false,
            manualPackages = setOf("org.telegram.messenger", "ru.sberbankmobile"),
            allApps = listOf("ru.sberbankmobile", "org.telegram.messenger", "com.yokhan.gates"),
            autoBypassPackages = setOf("ru.sberbankmobile"),
            alwaysOnLockdown = false,
        )

        assertTrue(plan.applyPerAppSplit)
        assertFalse(plan.effectiveBypass)
        assertEquals(setOf("org.telegram.messenger", "com.yokhan.gates"), plan.allowedPackages)
        assertTrue(plan.disallowedPackages.isEmpty())
    }

    @Test
    fun lockdownMovesDisallowedAppsToInTunnelDirectBypass() {
        val plan = VpnAppRoutePlanner.build(
            ownPackageName = "com.yokhan.gates",
            wholeTrafficMode = false,
            proxyApps = false,
            configuredBypass = true,
            needBypassRootUid = false,
            manualPackages = emptySet(),
            allApps = listOf("ru.sberbankmobile", "org.telegram.messenger"),
            autoBypassPackages = setOf("ru.sberbankmobile"),
            alwaysOnLockdown = true,
        )

        assertFalse(plan.applyPerAppSplit)
        assertTrue(plan.blockedBySystemLockdown)
        assertTrue(plan.disallowedPackages.isEmpty())
        assertEquals(setOf("ru.sberbankmobile"), plan.inTunnelDirectPackages)
    }

    @Test
    fun wholeTrafficDisablesAllAppSplits() {
        val plan = VpnAppRoutePlanner.build(
            ownPackageName = "com.yokhan.gates",
            wholeTrafficMode = true,
            proxyApps = true,
            configuredBypass = true,
            needBypassRootUid = false,
            manualPackages = setOf("com.example.manual"),
            allApps = listOf("ru.sberbankmobile", "com.example.manual"),
            autoBypassPackages = setOf("ru.sberbankmobile"),
            alwaysOnLockdown = false,
        )

        assertFalse(plan.applyPerAppSplit)
        assertTrue(plan.disallowedPackages.isEmpty())
        assertTrue(plan.allowedPackages.isEmpty())
        assertTrue(plan.inTunnelDirectPackages.isEmpty())
    }
}
