package com.yokhan.gates.app.ui

import android.os.SystemClock
import android.view.MenuItem
import androidx.appcompat.widget.PopupMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.yokhan.gates.app.ui.dashboard.DashboardComposeActions
import com.yokhan.gates.app.auth.YokhanKeycloakAuth
import com.yokhan.gates.app.ui.dashboard.DashboardComposeScreen
import com.yokhan.gates.app.ui.dashboard.DashboardProfileUiItem
import com.yokhan.gates.app.ui.shell.YokhanShellActivityRoute
import com.yokhan.gates.app.ui.shell.YokhanShellRoute
import com.yokhan.gates.shared.domain.ConnectionStatus
import com.yokhan.gates.shared.domain.DashboardState
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.widget.QRCodeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DashboardController(
    private val activity: MainActivity,
) {

    private var latestProfiles: List<ProxyEntity> = emptyList()
    private var latestProfile: ProxyEntity? = null
    private var latestProfileId = 0L
    private var latestGroupName = ""
    private var latestTxRate = 0L
    private var latestRxRate = 0L
    private var latestLatencyMs: Long? = null
    private var currentGroupIsSubscription = false
    private var currentState = BaseService.State.Idle
    private var connectionTestRunning = false
    private var hasCompletedInitialShow = false
    private var scrollToTopSignal = 0
    private var latestProfileItems: List<DashboardProfileUiItem> = emptyList()
    private var timerJob: Job? = null
    private var connectionTestJob: Job? = null
    private var autoLatencyJob: Job? = null
    private var connectionStartTime = 0L
    private val composeModel = mutableStateOf<DashboardComposeModel?>(null)

    @Composable
    fun Content() {
        val model = composeModel.value
        if (model != null) {
            DashboardComposeScreen(
                state = model.state,
                profiles = model.profiles,
                scrollToTopSignal = model.scrollToTopSignal,
                actions = DashboardComposeActions(
                    onMenuClick = activity::openNavigationMenu,
                    onToggleConnection = {
                        if (!DataStore.serviceState.canStop && latestProfileId <= 0L) {
                            activity.displayRoute(YokhanShellRoute.Configuration)
                            activity.showDashboardMessage("Выберите или добавьте конфигурацию (вход в аккаунт или «Мудрое Древо»).")
                        } else {
                            activity.toggleConnection()
                        }
                    },
                    onRunConnectionTest = ::runConnectionTest,
                    onAddProfile = {
                        if (YokhanKeycloakAuth.isConfigured() && YokhanKeycloakAuth.sessionAvailable.value) {
                            activity.startYokhanLogin()
                        } else {
                            activity.displayRoute(YokhanShellRoute.Configuration)
                        }
                    },
                    onSelectProfile = ::selectProfileById,
                    onEditProfile = ::editProfileById,
                    onOpenProfileMenu = ::showProfileMenuById,
                    onCurrentConfigClick = { activity.displayRoute(YokhanShellRoute.Configuration) },
                ),
            )
        }
    }

    fun onShown() {
        if (hasCompletedInitialShow) {
            scrollToTopSignal += 1
            renderDashboard()
        } else {
            hasCompletedInitialShow = true
        }
        refreshDashboard()
        updateConnectionState(DataStore.serviceState)
    }

    fun dispose() {
        timerJob?.cancel()
        connectionTestJob?.cancel()
        autoLatencyJob?.cancel()
        connectionTestJob = null
        autoLatencyJob = null
        connectionTestRunning = false
        composeModel.value = null
    }

    fun refreshDashboard() {
        activity.lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                DataStore.ensureGroupsLoaded()
                val groupId = DataStore.currentGroupId()
                val groupEntity = DataStore.currentGroup()
                val profile = ProfileManager.getProfile(DataStore.selectedProxy)
                DashboardSummary(
                    profile = profile,
                    groupName = groupEntity.displayName(),
                    isSubscriptionGroup = groupEntity.type == GroupType.SUBSCRIPTION,
                    profiles = SagerDatabase.proxyDao.getByGroup(groupId),
                )
            }

            latestProfiles = summary.profiles
            latestProfile = summary.profile
            latestProfileId = summary.profile?.id ?: 0L
            latestGroupName = summary.groupName
            currentGroupIsSubscription = summary.isSubscriptionGroup
            latestProfileItems = latestProfiles.toDashboardComposeProfileItems(latestProfileId)
            renderDashboard(buildDashboardState())
            activity.updateDashboardProtocol(summary.profile?.displayType() ?: "--")
            updateConnectionState(currentState)
        }
    }

    fun updateConnectionState(
        state: BaseService.State,
        serviceConnectedAtElapsedMs: Long = 0L,
    ) {
        currentState = state
        if (!state.connected) {
            latestTxRate = 0L
            latestRxRate = 0L
            latestLatencyMs = null
            connectionTestRunning = false
            connectionTestJob?.cancel()
            autoLatencyJob?.cancel()
            connectionTestJob = null
            autoLatencyJob = null
        }
        val dashboardState = buildDashboardState()
        renderDashboard(dashboardState)
        if (dashboardState.connectionStatus == ConnectionStatus.Connected) {
            startTimer(serviceConnectedAtElapsedMs)
            scheduleAutoLatencyProbeIfNeeded()
        } else {
            stopTimer()
        }
    }

    fun updateSpeeds(txRate: Long, rxRate: Long) {
        if (latestTxRate == txRate && latestRxRate == rxRate) return
        latestTxRate = txRate
        latestRxRate = rxRate
        renderDashboard(buildDashboardState())
    }

    fun runConnectionTest() {
        if (!DataStore.serviceState.connected) {
            activity.showDashboardMessage(R.string.not_connected)
            return
        }
        if (connectionTestJob?.isActive == true) return

        autoLatencyJob?.cancel()
        autoLatencyJob = null
        connectionTestRunning = true
        renderDashboard()
        activity.showDashboardMessage(R.string.connection_test_testing)
        connectionTestJob = activity.lifecycleScope.launch {
            try {
                val elapsed = withContext(Dispatchers.IO) {
                    activity.urlTest()
                }
                if (elapsed > 0) {
                    latestLatencyMs = elapsed.toLong()
                    activity.showDashboardMessage(
                        activity.getString(R.string.connection_test_available, elapsed),
                    )
                } else {
                    latestLatencyMs = null
                    activity.showDashboardMessage(R.string.connection_test_fail)
                }
            } catch (e: Exception) {
                latestLatencyMs = null
                activity.showDashboardMessage(
                    activity.getString(R.string.connection_test_error, e.readableMessage),
                )
            } finally {
                connectionTestRunning = false
                connectionTestJob = null
                renderDashboard()
            }
        }
    }

    private fun scheduleAutoLatencyProbeIfNeeded() {
        if (latestLatencyMs != null) return
        if (connectionTestJob?.isActive == true || autoLatencyJob?.isActive == true) return
        autoLatencyJob = activity.lifecycleScope.launch {
            delay(500L)
            if (!isActive || currentState != BaseService.State.Connected || latestLatencyMs != null) return@launch
            val elapsed = runCatching {
                withContext(Dispatchers.IO) { activity.urlTest() }
            }.getOrNull()
            if (elapsed != null && elapsed > 0 && currentState == BaseService.State.Connected) {
                latestLatencyMs = elapsed.toLong()
                renderDashboard(buildDashboardState())
            }
        }
    }

    private fun startTimer(serviceConnectedAtElapsedMs: Long = 0L) {
        val targetStart = when {
            serviceConnectedAtElapsedMs > 0L -> serviceConnectedAtElapsedMs
            connectionStartTime > 0L -> connectionStartTime
            else -> SystemClock.elapsedRealtime()
        }
        if (timerJob?.isActive == true && connectionStartTime == targetStart) return
        timerJob?.cancel()
        connectionStartTime = targetStart
        timerJob = activity.lifecycleScope.launch {
            while (isActive) {
                val elapsed = (SystemClock.elapsedRealtime() - connectionStartTime).coerceAtLeast(0L)
                renderDashboard(buildDashboardState(elapsed))
                delay(1000)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        connectionStartTime = 0L
    }

    private fun selectProfile(profile: ProxyEntity) {
        if (profile.id == latestProfileId) return
        latestProfileId = profile.id
        latestProfile = profile
        latestLatencyMs = null
        latestProfileItems = latestProfiles.toDashboardComposeProfileItems(latestProfileId)
        renderDashboard(buildDashboardState())
        activity.updateDashboardProtocol(profile.displayType())

        activity.lifecycleScope.launch(Dispatchers.IO) {
            val previous = DataStore.selectedProxy
            DataStore.selectedProxy = profile.id
            DataStore.currentProfile = profile.id
            ProfileManager.postUpdate(previous, true)
            ProfileManager.postUpdate(profile.id, true)
            if (DataStore.serviceState.canStop) {
                SagerNet.reloadService()
            }
            withContext(Dispatchers.Main) {
                refreshDashboard()
            }
        }
    }

    private fun selectProfileById(profileId: Long) {
        latestProfiles.firstOrNull { it.id == profileId }?.let(::selectProfile)
    }

    private fun editProfile(profile: ProxyEntity) {
        activity.openActivityRoute(
            YokhanShellActivityRoute.EditProfile(profile, currentGroupIsSubscription),
        )
    }

    private fun editProfileById(profileId: Long) {
        latestProfiles.firstOrNull { it.id == profileId }?.let(::editProfile)
    }

    private fun showProfileMenuById(profileId: Long) {
        val profile = latestProfiles.firstOrNull { it.id == profileId } ?: return
        val popup = PopupMenu(activity, activity.window.decorView)
        popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)

        if (!profile.haveStandardLink()) {
            popup.menu.findItem(R.id.action_group_qr).subMenu?.removeItem(R.id.action_standard_qr)
            popup.menu.findItem(R.id.action_group_clipboard).subMenu?.removeItem(
                R.id.action_standard_clipboard,
            )
        }
        if (!profile.haveLink()) {
            popup.menu.removeItem(R.id.action_group_qr)
            popup.menu.removeItem(R.id.action_group_clipboard)
        }
        if (profile.nekoBean != null) {
            popup.menu.removeItem(R.id.action_group_configuration)
        } else {
            popup.menu.findItem(R.id.action_group_configuration).subMenu?.removeItem(
                R.id.action_config_export_file,
            )
        }

        popup.setOnMenuItemClickListener { item -> handleProfileMenuItem(profile, item) }
        popup.show()
    }

    private fun handleProfileMenuItem(profile: ProxyEntity, item: MenuItem): Boolean {
        return try {
            val name = profile.displayName() ?: ""
            when (item.itemId) {
                R.id.action_standard_qr -> showProfileQr(profile.toStdLink(), name)
                R.id.action_standard_clipboard -> exportProfile(profile.toStdLink())
                R.id.action_universal_qr -> showProfileQr(profile.requireBean().toUniversalLink(), name)
                R.id.action_universal_clipboard -> exportProfile(profile.requireBean().toUniversalLink())
                R.id.action_config_export_clipboard -> exportProfile(profile.exportConfig().first)
            }
            true
        } catch (e: Exception) {
            activity.showDashboardMessage(e.readableMessage)
            true
        }
    }

    private fun showProfileQr(link: String, name: String) {
        QRCodeDialog(link, name).showAllowingStateLoss(activity.supportFragmentManager)
    }

    private fun exportProfile(link: String) {
        val success = SagerNet.trySetPrimaryClip(link)
        activity.showDashboardMessage(
            if (success) R.string.action_export_msg else R.string.action_export_err,
        )
    }

    private fun renderDashboard(
        dashboardState: DashboardState = buildDashboardState(),
    ) {
        val next = DashboardComposeModel(
            state = dashboardState,
            profiles = latestProfileItems,
            scrollToTopSignal = scrollToTopSignal,
        )
        if (composeModel.value != next) {
            composeModel.value = next
        }
    }

    private fun buildDashboardState(
        connectedForMillis: Long = if (
            currentState == BaseService.State.Connected && connectionStartTime > 0L
        ) {
            (SystemClock.elapsedRealtime() - connectionStartTime).coerceAtLeast(0L)
        } else {
            0L
        },
    ): DashboardState {
        return DashboardStateAdapter.from(
            serviceState = currentState,
            profile = latestProfile,
            groupName = latestGroupName,
            txRate = latestTxRate,
            rxRate = latestRxRate,
            latencyMs = latestLatencyMs,
            connectedForMillis = connectedForMillis,
            nowMillis = SystemClock.elapsedRealtime(),
            connectionTestRunning = connectionTestRunning,
        )
    }

    private data class DashboardSummary(
        val profile: ProxyEntity?,
        val groupName: String,
        val isSubscriptionGroup: Boolean,
        val profiles: List<ProxyEntity>,
    )

    @Immutable
    private data class DashboardComposeModel(
        val state: DashboardState,
        val profiles: List<DashboardProfileUiItem>,
        val scrollToTopSignal: Int,
    )

    private fun List<ProxyEntity>.toDashboardComposeProfileItems(
        activeId: Long,
    ): List<DashboardProfileUiItem> {
        return map { profile ->
            DashboardProfileUiItem(
                id = profile.id,
                name = profile.displayName() ?: "",
                protocol = profile.displayType(),
                isActive = profile.id == activeId,
            )
        }
    }
}
