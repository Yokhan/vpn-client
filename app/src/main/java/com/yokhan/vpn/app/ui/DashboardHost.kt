package com.yokhan.gates.app.ui

import androidx.annotation.StringRes

interface DashboardHost {
    fun openDashboardMenu()

    fun toggleDashboardConnection()

    fun runDashboardConnectionTest()

    fun openDashboardConfiguration()

    fun updateDashboardProtocol(protocol: String)

    fun showDashboardMessage(message: CharSequence)

    fun showDashboardMessage(@StringRes messageRes: Int)
}
