package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.yokhan.vpn.shared.domain.DashboardState
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutDashboardBinding
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ui.dashboard.compose.DashboardComposeActions
import io.nekohasekai.sagernet.ui.dashboard.compose.DashboardComposeScreen
import io.nekohasekai.sagernet.ui.dashboard.compose.DashboardProfileUiItem
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.QRCodeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DashboardFragment : ToolbarFragment(R.layout.layout_dashboard) {

    private var _binding: LayoutDashboardBinding? = null
    private val binding get() = _binding!!

    private var latestProfiles: List<ProxyEntity> = emptyList()
    private var latestProfile: ProxyEntity? = null
    private var latestProfileId = 0L
    private var latestGroupName = ""
    private var latestTxRate = 0L
    private var latestRxRate = 0L
    private var latestLatencyMs: Long? = null
    private var currentGroupIsSubscription = false
    private var currentState = BaseService.State.Idle

    private var timerJob: Job? = null
    private var connectionTestJob: Job? = null
    private var connectionStartTime = 0L
    private var orbMotionController: DashboardOrbMotionController? = null
    private var profileListRenderer: DashboardProfileListRenderer? = null
    private var viewRenderer: DashboardViewRenderer? = null
    private val composeModel = mutableStateOf<DashboardComposeModel?>(null)
    private var composeContentInstalled = false
    private val useComposeDashboard: Boolean
        get() = BuildConfig.ENABLE_COMPOSE_DASHBOARD

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = LayoutDashboardBinding.bind(view)
        configureDashboardMode()

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.dashboard_title)

        if (!useComposeDashboard && currentState != BaseService.State.Connected) {
            orbMotionController?.initializeDisconnected()
        }

        updateConnectionState(DataStore.serviceState)
        updateSpeeds(0L, 0L)
        refreshDashboard()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
        updateConnectionState(DataStore.serviceState)
    }

    override fun onDestroyView() {
        timerJob?.cancel()
        connectionTestJob?.cancel()
        orbMotionController?.release()
        orbMotionController = null
        profileListRenderer = null
        viewRenderer = null
        composeContentInstalled = false
        composeModel.value = null
        _binding = null
        super.onDestroyView()
    }

    private fun configureDashboardMode() {
        binding.dashboardXmlContent.isVisible = !useComposeDashboard
        binding.dashboardStars.isVisible = !useComposeDashboard
        binding.dashboardComposeContent.isVisible = useComposeDashboard

        if (useComposeDashboard) {
            binding.dashboardComposeContent.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            renderComposeDashboard()
            return
        }

        orbMotionController = DashboardOrbMotionController(binding)
        viewRenderer = DashboardViewRenderer(requireContext(), binding, toolbar)
        profileListRenderer = DashboardProfileListRenderer(
            binding.profilesContainer,
            ::colorOf,
            DashboardProfileListRenderer.Actions(
                onSelect = ::selectProfileById,
                onEdit = ::editProfileById,
                onMenu = ::showProfileMenuById,
            ),
        )
        viewRenderer?.bindActions(
            DashboardViewRenderer.Actions(
                onToggleConnection = { dashboardHost()?.toggleDashboardConnection() },
                onRunConnectionTest = { dashboardHost()?.runDashboardConnectionTest() },
                onOpenConfiguration = { dashboardHost()?.openDashboardConfiguration() },
            ),
        )
    }

    fun refreshDashboard() {
        viewLifecycleOwner.lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                DataStore.ensureGroupsLoaded()
                val groupId = DataStore.currentGroupId()
                val groupEntity = DataStore.currentGroup()
                val group = groupEntity.displayName()
                val profile = ProfileManager.getProfile(DataStore.selectedProxy)
                val profiles = SagerDatabase.proxyDao.getByGroup(groupId)
                DashboardSummary(
                    profile,
                    group,
                    groupEntity.type == GroupType.SUBSCRIPTION,
                    profiles,
                )
            }

            if (_binding == null) return@launch
            latestProfiles = summary.profiles
            latestProfile = summary.profile
            latestProfileId = summary.profile?.id ?: 0L
            latestGroupName = summary.groupName
            currentGroupIsSubscription = summary.isSubscriptionGroup
            val dashboardState = buildDashboardState()

            if (useComposeDashboard) {
                renderComposeDashboard(dashboardState)
            } else {
                viewRenderer?.renderProfileHeader(
                    summary.toDashboardProfileHeader(dashboardState),
                )
                populateProfileList(summary.profiles, latestProfileId)
            }
            dashboardHost()?.updateDashboardProtocol(
                summary.profile?.displayType() ?: "--"
            )

            updateConnectionState(currentState)
        }
    }

    private fun populateProfileList(profiles: List<ProxyEntity>, activeId: Long) {
        profileListRenderer?.render(
            profiles.toDashboardProfileItems(activeId),
            currentState == BaseService.State.Connected,
        )
    }

    fun updateConnectionState(state: BaseService.State) {
        currentState = state
        if (!state.connected) {
            latestLatencyMs = null
        }
        val dashboardState = buildDashboardState()
        if (useComposeDashboard) {
            renderComposeDashboard(dashboardState)
            if (dashboardState.connectionStatus == com.yokhan.vpn.shared.domain.ConnectionStatus.Connected) {
                startTimer()
            } else {
                stopTimer()
            }
            return
        }
        val renderResult = viewRenderer?.renderConnectionState(state, dashboardState) ?: return
        profileListRenderer?.applyTheme(renderResult.isConnected, renderResult.dividerColor)
        orbMotionController?.update(renderResult.isStarted)
        if (renderResult.isConnected) startTimer() else stopTimer()
    }

    fun updateSpeeds(txRate: Long, rxRate: Long) {
        val binding = _binding ?: return
        latestTxRate = txRate
        latestRxRate = rxRate
        val dashboardState = buildDashboardState()
        if (useComposeDashboard) {
            renderComposeDashboard(dashboardState)
            return
        }
        binding.uploadSpeed.text = DashboardTelemetryFormatter.speed(
            requireContext(),
            dashboardState.uploadRate,
        )
        binding.downloadSpeed.text = DashboardTelemetryFormatter.speed(
            requireContext(),
            dashboardState.downloadRate,
        )
    }

    private fun startTimer() {
        if (timerJob?.isActive == true) return
        connectionStartTime = SystemClock.elapsedRealtime()
        timerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val elapsed = SystemClock.elapsedRealtime() - connectionStartTime
                _binding?.connectionTimer?.text = buildDashboardState(elapsed).timerLabel
                if (useComposeDashboard) renderComposeDashboard(buildDashboardState(elapsed))
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
        val dashboardState = buildDashboardState()
        if (useComposeDashboard) {
            renderComposeDashboard(dashboardState)
        } else {
            viewRenderer?.renderProfileHeader(
                DashboardViewRenderer.ProfileHeader(
                    name = dashboardState.activeProfileName,
                    subtitle = dashboardState.activeProfileSubtitle,
                )
            )
            populateProfileList(latestProfiles, latestProfileId)
        }
        dashboardHost()?.updateDashboardProtocol(profile.displayType())

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val previous = DataStore.selectedProxy
            DataStore.selectedProxy = profile.id
            DataStore.currentProfile = profile.id
            ProfileManager.postUpdate(previous, true)
            ProfileManager.postUpdate(profile.id, true)
            if (DataStore.serviceState.canStop) {
                SagerNet.reloadService()
            }
            withContext(Dispatchers.Main) {
                if (_binding != null) refreshDashboard()
            }
        }
    }

    private fun selectProfileById(profileId: Long) {
        latestProfiles.firstOrNull { it.id == profileId }?.let(::selectProfile)
    }

    private fun editProfile(profile: ProxyEntity) {
        startActivity(profile.settingIntent(requireContext(), currentGroupIsSubscription))
    }

    private fun editProfileById(profileId: Long) {
        latestProfiles.firstOrNull { it.id == profileId }?.let(::editProfile)
    }

    private fun showProfileMenu(anchor: View, profile: ProxyEntity) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)

        if (!profile.haveStandardLink()) {
            popup.menu.findItem(R.id.action_group_qr).subMenu?.removeItem(R.id.action_standard_qr)
            popup.menu.findItem(R.id.action_group_clipboard).subMenu?.removeItem(
                R.id.action_standard_clipboard
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
                R.id.action_config_export_file
            )
        }

        popup.setOnMenuItemClickListener { item -> handleProfileMenuItem(profile, item) }
        popup.show()
    }

    private fun showProfileMenuById(anchor: View, profileId: Long) {
        latestProfiles.firstOrNull { it.id == profileId }?.let { profile ->
            showProfileMenu(anchor, profile)
        }
    }

    private fun handleProfileMenuItem(profile: ProxyEntity, item: MenuItem): Boolean {
        return try {
            val name = profile.displayName() ?: ""
            when (item.itemId) {
                R.id.action_standard_qr -> showProfileQr(profile.toStdLink(), name)
                R.id.action_standard_clipboard -> exportProfile(profile.toStdLink())
                R.id.action_universal_qr -> showProfileQr(profile.requireBean().toUniversalLink(), name)
                R.id.action_universal_clipboard -> exportProfile(
                    profile.requireBean().toUniversalLink()
                )
                R.id.action_config_export_clipboard -> exportProfile(profile.exportConfig().first)
            }
            true
        } catch (e: Exception) {
            dashboardHost()?.showDashboardMessage(e.readableMessage)
            true
        }
    }

    private fun showProfileQr(link: String, name: String) {
        QRCodeDialog(link, name).showAllowingStateLoss(parentFragmentManager)
    }

    private fun exportProfile(link: String) {
        val success = SagerNet.trySetPrimaryClip(link)
        dashboardHost()?.showDashboardMessage(
            if (success) R.string.action_export_msg else R.string.action_export_err
        )
    }

    fun runConnectionTest() {
        if (!DataStore.serviceState.connected) {
            dashboardHost()?.showDashboardMessage(R.string.not_connected)
            return
        }
        if (connectionTestJob?.isActive == true) return

        connectionTestJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val elapsed = withContext(Dispatchers.IO) {
                    (requireActivity() as MainActivity).urlTest()
                }
                latestLatencyMs = elapsed.toLong()
                renderComposeDashboard()
            } catch (e: Exception) {
                dashboardHost()?.showDashboardMessage(
                    getString(R.string.connection_test_error, e.readableMessage)
                )
            }
        }
    }

    private fun dashboardHost(): DashboardHost? {
        return activity as? DashboardHost
    }

    private fun renderComposeDashboard(
        dashboardState: DashboardState = buildDashboardState(),
    ) {
        val binding = _binding ?: return
        if (!useComposeDashboard) return

        if (!composeContentInstalled) {
            composeContentInstalled = true
            binding.dashboardComposeContent.setContent {
                val model = composeModel.value ?: return@setContent
                DashboardComposeScreen(
                    state = model.state,
                    profiles = model.profiles,
                    actions = DashboardComposeActions(
                        onMenuClick = { dashboardHost()?.openDashboardMenu() },
                        onToggleConnection = { dashboardHost()?.toggleDashboardConnection() },
                        onRunConnectionTest = ::runConnectionTest,
                        onAddProfile = { dashboardHost()?.openDashboardConfiguration() },
                        onSelectProfile = ::selectProfileById,
                        onEditProfile = ::editProfileById,
                        onOpenProfileMenu = { profileId ->
                            _binding?.dashboardComposeContent?.let { anchor ->
                                showProfileMenuById(anchor, profileId)
                            }
                        },
                    ),
                )
            }
        }
        composeModel.value = DashboardComposeModel(
            state = dashboardState,
            profiles = latestProfiles.toDashboardComposeProfileItems(latestProfileId),
        )
    }

    private fun colorOf(colorRes: Int): Int {
        return ContextCompat.getColor(requireContext(), colorRes)
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
        )
    }

    private data class DashboardSummary(
        val profile: ProxyEntity?,
        val groupName: String,
        val isSubscriptionGroup: Boolean,
        val profiles: List<ProxyEntity>,
    )

    private data class DashboardComposeModel(
        val state: DashboardState,
        val profiles: List<DashboardProfileUiItem>,
    )

    private fun DashboardSummary.toDashboardProfileHeader(
        dashboardState: DashboardState,
    ): DashboardViewRenderer.ProfileHeader {
        return DashboardViewRenderer.ProfileHeader(
            name = profile?.let { dashboardState.activeProfileName },
            subtitle = profile?.let { dashboardState.activeProfileSubtitle } ?: groupName,
        )
    }

    private fun List<ProxyEntity>.toDashboardProfileItems(
        activeId: Long,
    ): List<DashboardProfileListRenderer.Item> {
        return map { profile ->
            DashboardProfileListRenderer.Item(
                id = profile.id,
                name = profile.displayName() ?: "",
                protocol = profile.displayType(),
                isActive = profile.id == activeId,
            )
        }
    }

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
