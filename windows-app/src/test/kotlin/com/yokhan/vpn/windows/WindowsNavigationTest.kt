package com.yokhan.gates.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsNavigationTest {
    @Test
    fun popBackReturnsToExactParentCaller() {
        val fromSettings = WindowsRouteStack()
            .switchTab(WindowsRoute.Settings)
            .push(WindowsRoute.History)
        assertEquals(WindowsRoute.History, fromSettings.current)
        assertEquals(WindowsRoute.Settings, fromSettings.popBack().current)

        val fromAccess = WindowsRouteStack()
            .switchTab(WindowsRoute.Access)
            .push(WindowsRoute.History)
        assertEquals(WindowsRoute.History, fromAccess.current)
        assertEquals(WindowsRoute.Access, fromAccess.popBack().current)
    }

    @Test
    fun bugReportOpensOverPreAuthAccessAndReturnsToAccess() {
        val stack = WindowsRouteStack().push(WindowsRoute.BugReport)
        assertEquals(WindowsRoute.BugReport, stack.resolveEffective(sessionReady = false))
        val afterDismiss = stack.popBack()
        assertEquals(WindowsRoute.Access, afterDismiss.resolveEffective(sessionReady = false))
    }

    @Test
    fun switchingTabsFromSubRouteResetsDepthPredictably() {
        val fromSettings = WindowsRouteStack()
            .switchTab(WindowsRoute.Settings)
            .push(WindowsRoute.History)
        assertEquals(WindowsRoute.Settings.qaName, fromSettings.bottomNavSelectedKey(WindowsRoute.History))

        val deep = fromSettings
            .switchTab(WindowsRoute.Settings)
            .push(WindowsRoute.Routing)
            .push(WindowsRoute.History)
        assertEquals(WindowsRoute.Routing.qaName, deep.bottomNavSelectedKey(WindowsRoute.History))

        val routesTab = deep.switchTab(WindowsRoute.Routes)
        assertEquals(listOf(WindowsRoute.Dashboard, WindowsRoute.Routes), routesTab.entries)
        assertEquals(WindowsRoute.Routes.qaName, routesTab.bottomNavSelectedKey(WindowsRoute.Routes))

        val switched = deep.switchTab(WindowsRoute.Dashboard)
        assertEquals(listOf(WindowsRoute.Dashboard), switched.entries)
        assertEquals(WindowsRoute.Dashboard, switched.popBack().current)
    }

    @Test
    fun qaNavigationAndBackWorkDeterministicWithoutDeadEnds() {
        val routingFromDashboard = WindowsRouteStack().applyQaNavigation("nav.routing")
        assertEquals(WindowsRoute.Routing, routingFromDashboard.current)
        assertEquals(WindowsRoute.Dashboard, routingFromDashboard.applyQaNavigation("nav.back").current)

        val routesFromDashboard = WindowsRouteStack().applyQaNavigation("nav.routes")
        assertEquals(WindowsRoute.Routes, routesFromDashboard.current)
        assertEquals(WindowsRoute.Dashboard, routesFromDashboard.applyQaNavigation("nav.back").current)

        val logsAndTools = WindowsRouteStack()
            .applyQaNavigation("nav.logs")
            .applyQaNavigation("nav.tools")
        assertEquals(WindowsRoute.Tools, logsAndTools.current)
        assertEquals(WindowsRoute.Logs, logsAndTools.applyQaNavigation("nav.back").current)

        val settingsThenBug = WindowsRouteStack()
            .applyQaNavigation("nav.settings")
            .applyQaNavigation("nav.bug")
        assertEquals(WindowsRoute.BugReport, settingsThenBug.current)
        assertEquals(WindowsRoute.Settings, settingsThenBug.applyQaNavigation("nav.back").current)
    }

    @Test
    fun qaTagWhitelistIncludesNewParityScreensAndDrawer() {
        assertTrue(isWindowsQaTag("routes.screen"))
        assertTrue(isWindowsQaTag("logs.screen"))
        assertTrue(isWindowsQaTag("tools.screen"))
        assertTrue(isWindowsQaTag("drawer.routes"))
        assertTrue(isWindowsQaTag("routing.drop.proxy"))
        assertTrue(isWindowsQaTag("nav.routes"))
        assertFalse(isWindowsQaTag("unrelated.tag"))
    }
}
