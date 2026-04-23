package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.os.SystemClock
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
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

class DashboardFragment : ToolbarFragment(R.layout.layout_dashboard) {

    private var _binding: LayoutDashboardBinding? = null
    private val binding get() = _binding!!

    private var latestProfileName: String? = null
    private var latestGroupName: String? = null
    private var latestTxRate = 0L
    private var latestRxRate = 0L

    private var timerJob: Job? = null
    private var connectionStartTime = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = LayoutDashboardBinding.bind(view)

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
                DashboardSummary(profile, group, serviceMode, profiles, groupId)
            }

            val binding = _binding ?: return@launch
            latestProfileName = summary.profile?.displayName()
            latestGroupName = summary.groupName

            // Current config card
            binding.currentProfileName.text =
                latestProfileName ?: getString(R.string.dashboard_no_profile)
            val profileInfo = buildString {
                summary.profile?.let {
                    append(it.displayType())
                    append(" · ")
                }
                append(summary.groupName)
            }
            binding.currentProfileInfo.text = profileInfo

            // Profile list
            populateProfileList(summary.profiles, summary.profile?.id ?: 0L)

            updateConnectionState(DataStore.serviceState)
        }
    }

    private fun populateProfileList(profiles: List<ProxyEntity>, activeId: Long) {
        val binding = _binding ?: return
        val container = binding.profilesContainer
        container.removeAllViews()

        val inflater = LayoutInflater.from(requireContext())
        for (profile in profiles) {
            val itemView = inflater.inflate(
                R.layout.item_dashboard_profile, container, false
            )
            val isActive = profile.id == activeId

            val nameView = itemView.findViewById<TextView>(R.id.profile_name)
            val protocolView = itemView.findViewById<TextView>(R.id.profile_protocol)
            val activeBar = itemView.findViewById<View>(R.id.profile_active_bar)
            val dot = itemView.findViewById<View>(R.id.profile_dot)
            val editBtn = itemView.findViewById<ImageButton>(R.id.profile_edit)
            val menuBtn = itemView.findViewById<ImageButton>(R.id.profile_menu)

            nameView.text = profile.displayName()
            protocolView.text = profile.displayType()

            val accentColor = if (isActive) R.color.buffit_orange else android.R.color.transparent
            activeBar.setBackgroundColor(
                ContextCompat.getColor(requireContext(), accentColor)
            )

            val dotColor = if (isActive) R.color.buffit_orange else R.color.buffit_workout_bar
            dot.background.setTint(ContextCompat.getColor(requireContext(), dotColor))

            editBtn.setOnClickListener {
                // TODO: open profile editor
            }
            menuBtn.setOnClickListener {
                // TODO: show profile menu
            }

            container.addView(itemView)
        }
    }

    fun updateConnectionState(state: BaseService.State) {
        val binding = _binding ?: return
        val ctx = context ?: return
        val isConnected = state == BaseService.State.Connected

        // Ring color
        val ringColor = if (isConnected) R.color.buffit_green else R.color.buffit_orange
        binding.connectRing.setColorFilter(ContextCompat.getColor(ctx, ringColor))

        // Icon: rocket on launchpad (idle) vs rocket in flight (connected)
        binding.connectIcon.setImageResource(
            if (isConnected) R.drawable.ic_rocket_flying else R.drawable.ic_rocket_idle
        )

        // Label
        binding.connectLabel.text = getString(
            when (state) {
                BaseService.State.Connected -> R.string.dashboard_connected_status
                BaseService.State.Connecting -> R.string.dashboard_state_connecting
                BaseService.State.Stopping -> R.string.dashboard_state_stopping
                else -> R.string.dashboard_connect_action
            }
        )
        binding.connectLabel.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (isConnected) R.color.buffit_green else R.color.buffit_contrast
            )
        )

        // Timer
        binding.connectionTimer.isVisible = isConnected
        if (isConnected) startTimer() else stopTimer()

        // Test button
        binding.testConnectionButton.isEnabled = isConnected

        // Arrow colors
        val arrowColor = ContextCompat.getColor(
            ctx, if (isConnected) R.color.buffit_green else R.color.buffit_orange
        )
        binding.uploadArrow.setTextColor(arrowColor)
        binding.downloadArrow.setTextColor(arrowColor)
    }

    fun updateSpeeds(txRate: Long, rxRate: Long) {
        latestTxRate = txRate
        latestRxRate = rxRate
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
        val groupId: Long,
    )
}
