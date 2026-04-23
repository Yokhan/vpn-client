package io.nekohasekai.sagernet.ui

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.text.format.Formatter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.TextView
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

    private var ringAnimator: ObjectAnimator? = null
    private var flameLeftAnimator: ObjectAnimator? = null
    private var flameRightAnimator: ObjectAnimator? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = LayoutDashboardBinding.bind(view)

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

        binding.connectCircle.post {
            if (currentState != BaseService.State.Connected) {
                binding.rocketGroup.translationY = hiddenRocketTranslation()
            }
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
        cancelLoopingAnimations()
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
        val container = binding.profilesContainer
        container.removeAllViews()

        val inflater = LayoutInflater.from(requireContext())
        profiles.forEachIndexed { index, profile ->
            val itemView = inflater.inflate(
                R.layout.item_dashboard_profile, container, false
            )

            val isActive = profile.id == activeId
            val divider = itemView.findViewById<View>(R.id.profile_divider)
            val activeBar = itemView.findViewById<View>(R.id.profile_active_bar)
            val nameView = itemView.findViewById<TextView>(R.id.profile_name)
            val protocolView = itemView.findViewById<TextView>(R.id.profile_protocol)
            val editBtn = itemView.findViewById<ImageButton>(R.id.profile_edit)
            val menuBtn = itemView.findViewById<ImageButton>(R.id.profile_menu)

            divider.isVisible = index != 0
            nameView.text = profile.displayName()
            protocolView.text = profile.displayType()

            editBtn.setOnClickListener {
                // TODO: open profile editor
            }
            menuBtn.setOnClickListener {
                // TODO: show profile menu
            }

            itemView.setTag(R.id.profile_active_bar, isActive)
            itemView.setTag(R.id.profile_name, activeBar)
            itemView.setTag(R.id.profile_protocol, divider)
            applyProfileRowTheme(itemView, isActive, currentState == BaseService.State.Connected)

            container.addView(itemView)
        }
    }

    fun updateConnectionState(state: BaseService.State) {
        currentState = state
        val binding = _binding ?: return
        val ctx = context ?: return
        val isConnected = state == BaseService.State.Connected

        val primaryColor = colorOf(
            if (isConnected) R.color.dashboard_space_text_primary else R.color.dashboard_text_primary
        )
        val secondaryColor = colorOf(
            if (isConnected) R.color.dashboard_space_text_secondary else R.color.dashboard_text_secondary
        )
        val tertiaryColor = colorOf(
            if (isConnected) R.color.dashboard_space_text_secondary else R.color.dashboard_text_tertiary
        )
        val borderColor = colorOf(
            if (isConnected) R.color.dashboard_card_border_on else R.color.dashboard_card_border_off
        )
        val dividerColor = colorOf(
            if (isConnected) R.color.dashboard_divider_dark else R.color.dashboard_divider_light
        )
        val accentColor = colorOf(
            if (isConnected) R.color.dashboard_green else R.color.dashboard_accent
        )

        binding.dashboardRoot.setBackgroundColor(
            colorOf(if (isConnected) R.color.dashboard_space_bg else R.color.dashboard_bg_off)
        )
        binding.dashboardSpaceBackground.animate().alpha(if (isConnected) 1f else 0f).setDuration(600L)
            .start()
        binding.dashboardSpaceGlow.animate().alpha(if (isConnected) 1f else 0f).setDuration(600L)
            .start()

        toolbar.setTitleTextColor(primaryColor)
        toolbar.navigationIcon?.setTint(primaryColor)
        binding.toolbarSysLabel.setTextColor(secondaryColor)
        binding.toolbarSysDot.background.setTint(accentColor)

        themeCard(binding.currentConfigCard, borderColor)
        themeCard(binding.profilesCard, borderColor)

        binding.currentConfigLabel.setTextColor(tertiaryColor)
        binding.currentProfileName.setTextColor(primaryColor)
        binding.currentProfileInfo.setTextColor(secondaryColor)

        binding.signalBox.setBackgroundResource(
            if (isConnected) R.drawable.bg_dashboard_signal_box_on
            else R.drawable.bg_dashboard_signal_box_off
        )
        binding.signalBars.setColorFilter(primaryColor)
        binding.signalStatusDot.background.setTint(accentColor)

        binding.uploadSpeed.setTextColor(primaryColor)
        binding.downloadSpeed.setTextColor(primaryColor)
        binding.uploadArrow.setTextColor(accentColor)
        binding.downloadArrow.setTextColor(accentColor)
        binding.uploadWave.setColorFilter(accentColor)
        binding.downloadWave.setColorFilter(accentColor)

        binding.orbBackground.setBackgroundResource(
            if (isConnected) R.drawable.bg_dashboard_orb_on else R.drawable.bg_dashboard_orb_off
        )
        binding.connectRing.setBackgroundResource(
            if (isConnected) R.drawable.bg_dashboard_orb_ring_on
            else R.drawable.bg_dashboard_orb_ring_off
        )
        binding.connectCenterLabel.isVisible = !isConnected
        binding.connectCenterLabel.setTextColor(secondaryColor)

        binding.connectLabel.text = getString(
            when (state) {
                BaseService.State.Connected -> R.string.dashboard_connected_status
                BaseService.State.Connecting -> R.string.dashboard_state_connecting
                BaseService.State.Stopping -> R.string.dashboard_state_stopping
                else -> R.string.dashboard_tap_connect
            }
        )
        binding.connectLabel.setTextColor(if (isConnected) primaryColor else tertiaryColor)
        binding.connectLabel.gravity = if (isConnected) {
            Gravity.START or Gravity.CENTER_VERTICAL
        } else {
            Gravity.CENTER
        }

        binding.connectionTimer.isVisible = isConnected
        binding.connectionTimer.setTextColor(if (isConnected) primaryColor else tertiaryColor)
        if (isConnected) startTimer() else stopTimer()

        binding.testConnectionButton.isEnabled = isConnected
        binding.testConnectionButton.alpha = if (isConnected) 1f else 0.55f
        binding.testConnectionButton.background = ContextCompat.getDrawable(
            ctx,
            if (isConnected) R.drawable.bg_dashboard_test_button_on
            else R.drawable.bg_dashboard_test_button_off
        )
        binding.testConnectionButton.setTextColor(secondaryColor)
        binding.testConnectionButton.compoundDrawablesRelative.forEach { drawable ->
            drawable?.setTint(secondaryColor)
        }

        applyProfileContainerTheme(dividerColor)
        updateOrbMotion(isConnected)
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

    private fun updateOrbMotion(isConnected: Boolean) {
        val binding = _binding ?: return
        if (binding.connectCircle.height == 0) {
            binding.connectCircle.post { updateOrbMotion(isConnected) }
            return
        }

        if (isConnected) {
            startLoopingAnimations()
            binding.rocketGroup.animate().cancel()
            if (binding.rocketGroup.alpha == 0f) {
                binding.rocketGroup.translationY = hiddenRocketTranslation()
            }
            binding.rocketGroup.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(700L)
                .setInterpolator(DecelerateInterpolator())
                .start()
            binding.rocketFlameLeft.animate().alpha(1f).setDuration(240L).start()
            binding.rocketFlameRight.animate().alpha(1f).setDuration(240L).start()
        } else {
            cancelLoopingAnimations()
            binding.rocketGroup.animate().cancel()
            binding.rocketGroup.animate()
                .alpha(0f)
                .translationY(hiddenRocketTranslation())
                .setDuration(320L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
            binding.rocketFlameLeft.alpha = 0f
            binding.rocketFlameRight.alpha = 0f
        }
    }

    private fun startLoopingAnimations() {
        if (ringAnimator == null) {
            ringAnimator = ObjectAnimator.ofPropertyValuesHolder(
                binding.connectRing,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.04f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.04f, 1f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.8f, 1f, 0.8f),
            ).apply {
                duration = 1600L
                interpolator = AccelerateDecelerateInterpolator()
                repeatCount = ObjectAnimator.INFINITE
            }
        }
        if (flameLeftAnimator == null) {
            flameLeftAnimator = flameAnimator(binding.rocketFlameLeft)
        }
        if (flameRightAnimator == null) {
            flameRightAnimator = flameAnimator(binding.rocketFlameRight)
        }
        if (ringAnimator?.isStarted != true) ringAnimator?.start()
        if (flameLeftAnimator?.isStarted != true) flameLeftAnimator?.start()
        if (flameRightAnimator?.isStarted != true) flameRightAnimator?.start()
    }

    private fun flameAnimator(view: View): ObjectAnimator {
        return ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.15f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f, 1f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.82f, 1f, 0.82f),
        ).apply {
            duration = 420L
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ObjectAnimator.INFINITE
        }
    }

    private fun cancelLoopingAnimations() {
        ringAnimator?.cancel()
        ringAnimator = null
        flameLeftAnimator?.cancel()
        flameLeftAnimator = null
        flameRightAnimator?.cancel()
        flameRightAnimator = null

        val binding = _binding ?: return
        binding.connectRing.scaleX = 1f
        binding.connectRing.scaleY = 1f
        binding.connectRing.alpha = 1f
        binding.rocketFlameLeft.scaleX = 1f
        binding.rocketFlameLeft.scaleY = 1f
        binding.rocketFlameRight.scaleX = 1f
        binding.rocketFlameRight.scaleY = 1f
    }

    private fun hiddenRocketTranslation(): Float {
        return binding.connectCircle.height * 0.45f
    }

    private fun applyProfileContainerTheme(dividerColor: Int) {
        val isConnected = currentState == BaseService.State.Connected
        for (index in 0 until binding.profilesContainer.childCount) {
            val itemView = binding.profilesContainer.getChildAt(index)
            val isActive = itemView.getTag(R.id.profile_active_bar) as? Boolean ?: false
            val divider = itemView.getTag(R.id.profile_protocol) as? View
            divider?.setBackgroundColor(dividerColor)
            applyProfileRowTheme(itemView, isActive, isConnected)
        }
    }

    private fun applyProfileRowTheme(itemView: View, isActive: Boolean, isConnected: Boolean) {
        val primaryColor = colorOf(
            if (isConnected) R.color.dashboard_space_text_primary else R.color.dashboard_text_primary
        )
        val tertiaryColor = colorOf(
            if (isConnected) R.color.dashboard_space_text_secondary else R.color.dashboard_text_tertiary
        )
        val actionColor = colorOf(
            if (isConnected) R.color.dashboard_space_text_secondary else R.color.dashboard_text_quaternary
        )
        val activeColor = colorOf(
            if (isConnected) R.color.dashboard_green else R.color.dashboard_accent
        )

        val activeBar = itemView.getTag(R.id.profile_name) as? View ?: return
        val nameView = itemView.findViewById<TextView>(R.id.profile_name)
        val protocolView = itemView.findViewById<TextView>(R.id.profile_protocol)
        val editBtn = itemView.findViewById<ImageButton>(R.id.profile_edit)
        val menuBtn = itemView.findViewById<ImageButton>(R.id.profile_menu)

        activeBar.backgroundTintList = ColorStateList.valueOf(
            if (isActive) activeColor else Color.TRANSPARENT
        )
        nameView.setTextColor(primaryColor)
        protocolView.setTextColor(tertiaryColor)
        editBtn.imageTintList = ColorStateList.valueOf(actionColor)
        menuBtn.imageTintList = ColorStateList.valueOf(actionColor)
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
}
