package com.yokhan.gates.windows

enum class WindowsRoute(val qaName: String) {
    Dashboard("dashboard"),
    Access("access"),
    Settings("settings"),
    Routes("routes"),
    Routing("routing"),
    Logs("logs"),
    Tools("tools"),
    History("history"),
    BugReport("bug"),
}

data class WindowsRouteStack(
    val entries: List<WindowsRoute> = listOf(WindowsRoute.Dashboard),
) {
    val current: WindowsRoute
        get() = entries.lastOrNull() ?: WindowsRoute.Dashboard

    fun push(route: WindowsRoute): WindowsRouteStack {
        if (route == WindowsRoute.Dashboard) {
            return WindowsRouteStack(listOf(WindowsRoute.Dashboard))
        }
        val base = entries.ifEmpty { listOf(WindowsRoute.Dashboard) }
        if (base.last() == route) return this
        val deduplicated = if (base.contains(route)) {
            base.filterNot { it == route } + route
        } else {
            base + route
        }
        return WindowsRouteStack(deduplicated)
    }

    fun switchTab(tab: WindowsRoute): WindowsRouteStack {
        return when (tab) {
            WindowsRoute.Dashboard -> WindowsRouteStack(listOf(WindowsRoute.Dashboard))
            WindowsRoute.Access -> push(WindowsRoute.Access)
            WindowsRoute.Routes,
            WindowsRoute.Routing,
            WindowsRoute.Tools,
            WindowsRoute.Settings -> WindowsRouteStack(listOf(WindowsRoute.Dashboard, tab))
            else -> push(tab)
        }
    }

    fun popBack(): WindowsRouteStack {
        return if (entries.size > 1) {
            WindowsRouteStack(entries.dropLast(1))
        } else {
            WindowsRouteStack(listOf(WindowsRoute.Dashboard))
        }
    }

    fun resetToDashboard(): WindowsRouteStack = WindowsRouteStack(listOf(WindowsRoute.Dashboard))

    fun resolveEffective(sessionReady: Boolean): WindowsRoute {
        return when {
            current == WindowsRoute.BugReport -> WindowsRoute.BugReport
            !sessionReady -> WindowsRoute.Access
            else -> current
        }
    }

    fun bottomNavSelectedKey(effectiveRoute: WindowsRoute): String {
        if (effectiveRoute in TOP_LEVEL_TABS) return effectiveRoute.qaName
        return entries.asReversed().firstOrNull { it in TOP_LEVEL_TABS }?.qaName
            ?: WindowsRoute.Settings.qaName
    }

    fun applyQaNavigation(actionId: String): WindowsRouteStack {
        return when (actionId) {
            "nav.dashboard" -> switchTab(WindowsRoute.Dashboard)
            "nav.settings" -> switchTab(WindowsRoute.Settings)
            "nav.routes" -> switchTab(WindowsRoute.Routes)
            "nav.access" -> push(WindowsRoute.Access)
            "nav.routing" -> push(WindowsRoute.Routing)
            "nav.logs" -> push(WindowsRoute.Logs)
            "nav.tools" -> push(WindowsRoute.Tools)
            "nav.history" -> push(WindowsRoute.History)
            "nav.bug" -> push(WindowsRoute.BugReport)
            "nav.back" -> popBack()
            else -> this
        }
    }

    companion object {
        private val TOP_LEVEL_TABS = setOf(
            WindowsRoute.Dashboard,
            WindowsRoute.Access,
            WindowsRoute.Routes,
            WindowsRoute.Routing,
            WindowsRoute.Tools,
            WindowsRoute.Settings,
        )
    }
}
