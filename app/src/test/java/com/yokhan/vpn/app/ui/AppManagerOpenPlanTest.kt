package com.yokhan.gates.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppManagerOpenPlanTest {
    @Test
    fun openingWithPerAppOffPreservesPersistedFlags() {
        val plan = planAppManagerOpen(proxyAppsEnabled = false, bypassEnabled = true)

        assertFalse(plan.proxyAppsEnabled)
        assertTrue(plan.bypassEnabled)
        assertEquals(AppManagerProxyMode.Off, plan.proxyMode)
    }

    @Test
    fun openingUsesPersistedPerAppModeWithoutChangingIt() {
        assertEquals(
            AppManagerOpenPlan(true, false, AppManagerProxyMode.Proxy),
            planAppManagerOpen(proxyAppsEnabled = true, bypassEnabled = false),
        )
        assertEquals(
            AppManagerOpenPlan(true, true, AppManagerProxyMode.Bypass),
            planAppManagerOpen(proxyAppsEnabled = true, bypassEnabled = true),
        )
    }
}
