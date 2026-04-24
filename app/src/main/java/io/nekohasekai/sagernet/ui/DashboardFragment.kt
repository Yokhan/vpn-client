package io.nekohasekai.sagernet.ui

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.yokhan.vpn.shared.domain.ConnectionStatus
import com.yokhan.vpn.shared.domain.DashboardState
import com.google.android.material.card.MaterialCardView
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
    private var currentGroupIsSubscription = false
    private var currentState = BaseService.State.Idle

    private var timerJob: Job? = null
    private var connectionStartTime = 0L
    private var orbMotionController: DashboardOrbMotionController? = null
    private var profileListRenderer: DashboardProfileListRenderer? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = LayoutDashboardBinding.bind(view)
        orbMotionController = DashboardOrbMotionController(binding)
        profileListRenderer = DashboardProfileListRenderer(
            binding.profilesContainer,
            ::colorOf,
            DashboardProfileListRenderer.Actions(
                onSelect = ::selectProfile,
                onEdit = ::editProfile,
                onMenu = ::showProfileMenu,
            ),
        )

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.dashboard_title)

        binding.connectCircle.setOnClickListener {
            (activity as? MainActivity)?.toggleConnection()
        }
        binding.testConnectionButton.setOnClickListener {
            (activity as? MainActivity)?.runConnectionTest()
        }
        binding.addConfig.setOnClickListener {
            (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_configuration)
        }

        if (currentState != BaseService.State.Connected) {
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
        orbMotionController?.release()
        orbMotionController = null
        profileListRenderer = null
        _binding = null
        super.onDestroyView()
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

            val binding = _binding ?: return@launch
            latestProfiles = summary.profiles
            latestProfile = summary.profile
            latestProfileId = summary.profile?.id ?: 0L
            latestGroupName = summary.groupName
            currentGroupIsSubscription = summary.isSubscriptionGroup
            val dashboardState = buildDashboardState()

            binding.toolbarSysLabel.text = getString(R.string.dashboard_system_label)

            binding.currentProfileName.text =
                summary.profile?.let { dashboardState.activeProfileName }
                    ?: getString(R.string.dashboard_no_profile)
            binding.currentProfileInfo.text =
                summary.profile?.let { dashboardState.activeProfileSubtitle } ?: buildString {
                summary.profile?.displayType()?.takeIf { it.isNotBlank() }?.let {
                    append(it)
                    append(" · ")
                }
                append(summary.groupName)
            }

            populateProfileList(summary.profiles, latestProfileId)
            (activity as? MainActivity)?.binding?.stats?.updateProtocol(
                summary.profile?.displayType() ?: "--"
            )

            updateConnectionState(currentState)
        }
    }

    private fun populateProfileList(profiles: List<ProxyEntity>, activeId: Long) {
        profileListRenderer?.render(
            profiles,
            activeId,
            currentState == BaseService.State.Connected,
        )
    }

    fun updateConnectionState(state: BaseService.State) {
        currentState = state
        val binding = _binding ?: return
        context ?: return
        val dashboardState = buildDashboardState()
        val isConnected = dashboardState.connectionStatus == ConnectionStatus.Connected
        val isStarted = dashboardState.shouldAnimateSpace
        val theme = dashboardTheme(isConnected)

        applySpaceTheme(binding, isStarted, theme)
        applyToolbarTheme(binding, theme)
        applyCardsTheme(binding, theme)
        applyTrafficTheme(binding, theme)
        applyOrbTheme(binding, isConnected, isStarted, theme)
        applyStatusTheme(binding, state, dashboardState, isConnected, theme)
        applyTestButtonTheme(binding, dashboardState.canRunConnectionTest, theme)
        profileListRenderer?.applyTheme(isConnected, theme.divider)
        orbMotionController?.update(isStarted)
    }

    private fun dashboardTheme(isConnected: Boolean): DashboardTheme {
        return DashboardTheme(
            primary = colorOf(
                if (isConnected) R.color.dashboard_space_text_primary
                else R.color.dashboard_text_primary
            ),
            secondary = colorOf(
                if (isConnected) R.color.dashboard_space_text_secondary
                else R.color.dashboard_text_secondary
            ),
            tertiary = colorOf(
                if (isConnected) R.color.dashboard_space_text_secondary
                else R.color.dashboard_text_tertiary
            ),
            border = colorOf(
                if (isConnected) R.color.dashboard_card_border_on
                else R.color.dashboard_card_border_off
            ),
            divider = colorOf(
                if (isConnected) R.color.dashboard_divider_dark
                else R.color.dashboard_divider_light
            ),
            accent = colorOf(
                if (isConnected) R.color.dashboard_green
                else R.color.dashboard_accent
            ),
            background = colorOf(
                if (isConnected) R.color.dashboard_space_bg
                else R.color.dashboard_bg_off
            ),
        )
    }

    private fun applySpaceTheme(
        binding: LayoutDashboardBinding,
        isStarted: Boolean,
        theme: DashboardTheme,
    ) {
        binding.dashboardRoot.setBackgroundColor(theme.background)
        binding.dashboardSpaceBackground.animate().alpha(if (isStarted) 1f else 0f)
            .setDuration(600L).start()
        binding.dashboardSpaceGlow.animate().alpha(if (isStarted) 1f else 0f)
            .setDuration(600L).start()
        binding.dashboardStars.setConnected(isStarted)
        binding.dashboardStars.animate().alpha(if (isStarted) 1f else 0f)
            .setDuration(800L).start()
    }

    private fun applyToolbarTheme(binding: LayoutDashboardBinding, theme: DashboardTheme) {
        toolbar.setTitleTextColor(theme.primary)
        toolbar.navigationIcon?.setTint(theme.primary)
        binding.toolbarSysLabel.setTextColor(theme.secondary)
        binding.toolbarSysDot.background.setTint(theme.accent)
    }

    private fun applyCardsTheme(binding: LayoutDashboardBinding, theme: DashboardTheme) {
        themeCard(binding.currentConfigCard, theme.border)
        themeCard(binding.profilesCard, theme.border)

        binding.currentConfigLabel.setTextColor(theme.tertiary)
        binding.currentProfileName.setTextColor(theme.primary)
        binding.currentProfileInfo.setTextColor(theme.secondary)

        binding.signalBars.setColorFilter(theme.primary)
        binding.signalStatusDot.background.setTint(theme.accent)
    }

    private fun applyTrafficTheme(binding: LayoutDashboardBinding, theme: DashboardTheme) {
        binding.uploadSpeed.setTextColor(theme.primary)
        binding.downloadSpeed.setTextColor(theme.primary)
        binding.uploadArrow.setTextColor(theme.accent)
        binding.downloadArrow.setTextColor(theme.accent)
        binding.uploadWave.setColorFilter(theme.accent)
        binding.downloadWave.setColorFilter(theme.accent)
    }

    private fun applyOrbTheme(
        binding: LayoutDashboardBinding,
        isConnected: Boolean,
        isStarted: Boolean,
        theme: DashboardTheme,
    ) {
        binding.signalBox.setBackgroundResource(
            if (isConnected) R.drawable.bg_dashboard_signal_box_on
            else R.drawable.bg_dashboard_signal_box_off
        )
        binding.orbBackground.setBackgroundResource(
            if (isConnected) R.drawable.bg_dashboard_orb_on else R.drawable.bg_dashboard_orb_off
        )
        binding.connectRing.setBackgroundResource(
            if (isConnected) R.drawable.bg_dashboard_orb_ring_on
            else R.drawable.bg_dashboard_orb_ring_off
        )
        binding.orbMarks.setConnected(isStarted)
        binding.connectCenterLabel.isVisible = !isStarted
        binding.connectCenterLabel.setTextColor(theme.secondary)
    }

    private fun applyStatusTheme(
        binding: LayoutDashboardBinding,
        state: BaseService.State,
        dashboardState: DashboardState,
        isConnected: Boolean,
        theme: DashboardTheme,
    ) {
        binding.connectLabel.text = getString(
            when (state) {
                BaseService.State.Connected -> R.string.dashboard_connected_status
                BaseService.State.Connecting -> R.string.dashboard_state_connecting
                BaseService.State.Stopping -> R.string.dashboard_state_stopping
                else -> R.string.dashboard_tap_connect
            }
        )
        binding.connectLabel.setTextColor(if (isConnected) theme.primary else theme.tertiary)
        binding.connectLabel.gravity = if (isConnected) {
            Gravity.START or Gravity.CENTER_VERTICAL
        } else {
            Gravity.CENTER
        }

        binding.connectionTimer.isVisible = isConnected
        binding.connectionTimer.setTextColor(if (isConnected) theme.primary else theme.tertiary)
        binding.connectionTimer.text = dashboardState.timerLabel
        if (isConnected) startTimer() else stopTimer()
    }

    private fun applyTestButtonTheme(
        binding: LayoutDashboardBinding,
        canRunConnectionTest: Boolean,
        theme: DashboardTheme,
    ) {
        binding.testConnectionButton.isEnabled = canRunConnectionTest
        binding.testConnectionButton.alpha = if (canRunConnectionTest) 1f else 0.55f
        binding.testConnectionButton.background = ContextCompat.getDrawable(
            requireContext(),
            if (canRunConnectionTest) R.drawable.bg_dashboard_test_button_on
            else R.drawable.bg_dashboard_test_button_off
        )
        binding.testConnectionButton.setTextColor(theme.secondary)
        binding.testConnectionButton.compoundDrawablesRelative.forEach { drawable ->
            drawable?.setTint(theme.secondary)
        }
    }

    fun updateSpeeds(txRate: Long, rxRate: Long) {
        val binding = _binding ?: return
        latestTxRate = txRate
        latestRxRate = rxRate
        val dashboardState = buildDashboardState()
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

    private fun editProfile(profile: ProxyEntity) {
        startActivity(profile.settingIntent(requireContext(), currentGroupIsSubscription))
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
            (activity as? MainActivity)?.snackbar(e.readableMessage)?.show()
            true
        }
    }

    private fun showProfileQr(link: String, name: String) {
        QRCodeDialog(link, name).showAllowingStateLoss(parentFragmentManager)
    }

    private fun exportProfile(link: String) {
        val success = SagerNet.trySetPrimaryClip(link)
        (activity as? MainActivity)
            ?.snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
            ?.show()
    }

    private fun themeCard(card: MaterialCardView, strokeColor: Int) {
        card.setCardBackgroundColor(Color.TRANSPARENT)
        card.strokeColor = strokeColor
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

    private data class DashboardTheme(
        val primary: Int,
        val secondary: Int,
        val tertiary: Int,
        val border: Int,
        val divider: Int,
        val accent: Int,
        val background: Int,
    )
}
