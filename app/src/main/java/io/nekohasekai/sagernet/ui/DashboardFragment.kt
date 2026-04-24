package io.nekohasekai.sagernet.ui

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutDashboardBinding
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class DashboardFragment : ToolbarFragment(R.layout.layout_dashboard) {

    private var _binding: LayoutDashboardBinding? = null
    private val binding get() = _binding!!

    private var latestProfiles: List<ProxyEntity> = emptyList()
    private var latestProfileId = 0L
    private var currentState = BaseService.State.Idle

    private var timerJob: Job? = null
    private var connectionStartTime = 0L
    private var orbMotionController: DashboardOrbMotionController? = null
    private var profileListRenderer: DashboardProfileListRenderer? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = LayoutDashboardBinding.bind(view)
        orbMotionController = DashboardOrbMotionController(binding)
        profileListRenderer = DashboardProfileListRenderer(binding.profilesContainer, ::colorOf)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.app_name)

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
                val group = DataStore.currentGroup().displayName()
                val profile = ProfileManager.getProfile(DataStore.selectedProxy)
                val serviceMode = if (DataStore.serviceMode == Key.MODE_VPN) {
                    getString(R.string.service_mode_vpn)
                } else {
                    getString(R.string.service_mode_proxy)
                }
                val profiles = SagerDatabase.proxyDao.getByGroup(groupId)
                DashboardSummary(profile, group, serviceMode, profiles)
            }

            val binding = _binding ?: return@launch
            latestProfiles = summary.profiles
            latestProfileId = summary.profile?.id ?: 0L

            binding.toolbarSysLabel.text = summary.serviceModeLabel.uppercase(Locale.getDefault())

            binding.currentProfileName.text =
                summary.profile?.displayName() ?: getString(R.string.dashboard_no_profile)
            binding.currentProfileInfo.text = buildString {
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
        val isConnected = state == BaseService.State.Connected
        val theme = dashboardTheme(isConnected)

        applySpaceTheme(binding, isConnected, theme)
        applyToolbarTheme(binding, theme)
        applyCardsTheme(binding, theme)
        applyTrafficTheme(binding, theme)
        applyOrbTheme(binding, isConnected, theme)
        applyStatusTheme(binding, state, isConnected, theme)
        applyTestButtonTheme(binding, isConnected, theme)
        profileListRenderer?.applyTheme(isConnected, theme.divider)
        orbMotionController?.update(isConnected)
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
        isConnected: Boolean,
        theme: DashboardTheme,
    ) {
        binding.dashboardRoot.setBackgroundColor(theme.background)
        binding.dashboardSpaceBackground.animate().alpha(if (isConnected) 1f else 0f)
            .setDuration(600L).start()
        binding.dashboardSpaceGlow.animate().alpha(if (isConnected) 1f else 0f)
            .setDuration(600L).start()
        binding.dashboardStars.setConnected(isConnected)
        binding.dashboardStars.animate().alpha(if (isConnected) 1f else 0f)
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
        binding.orbMarks.setConnected(isConnected)
        binding.connectCenterLabel.isVisible = !isConnected
        binding.connectCenterLabel.setTextColor(theme.secondary)
    }

    private fun applyStatusTheme(
        binding: LayoutDashboardBinding,
        state: BaseService.State,
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
        if (isConnected) startTimer() else stopTimer()
    }

    private fun applyTestButtonTheme(
        binding: LayoutDashboardBinding,
        isConnected: Boolean,
        theme: DashboardTheme,
    ) {
        binding.testConnectionButton.isEnabled = isConnected
        binding.testConnectionButton.alpha = if (isConnected) 1f else 0.55f
        binding.testConnectionButton.background = ContextCompat.getDrawable(
            requireContext(),
            if (isConnected) R.drawable.bg_dashboard_test_button_on
            else R.drawable.bg_dashboard_test_button_off
        )
        binding.testConnectionButton.setTextColor(theme.secondary)
        binding.testConnectionButton.compoundDrawablesRelative.forEach { drawable ->
            drawable?.setTint(theme.secondary)
        }
    }

    fun updateSpeeds(txRate: Long, rxRate: Long) {
        val binding = _binding ?: return
        binding.uploadSpeed.text = formatSpeed(txRate)
        binding.downloadSpeed.text = formatSpeed(rxRate)
    }

    private fun startTimer() {
        if (timerJob?.isActive == true) return
        connectionStartTime = SystemClock.elapsedRealtime()
        timerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val elapsed = SystemClock.elapsedRealtime() - connectionStartTime
                _binding?.connectionTimer?.text = formatElapsed(elapsed)
                delay(1000)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun themeCard(card: MaterialCardView, strokeColor: Int) {
        card.setCardBackgroundColor(Color.TRANSPARENT)
        card.strokeColor = strokeColor
    }

    private fun colorOf(colorRes: Int): Int {
        return ContextCompat.getColor(requireContext(), colorRes)
    }

    private fun formatElapsed(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatSpeed(rate: Long): String {
        return if (rate <= 0L) {
            getString(R.string.dashboard_speed_idle)
        } else {
            getString(R.string.speed, Formatter.formatFileSize(requireContext(), rate))
        }
    }

    private data class DashboardSummary(
        val profile: ProxyEntity?,
        val groupName: String,
        val serviceModeLabel: String,
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
