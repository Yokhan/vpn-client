package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.LayoutDashboardBinding
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DashboardFragment : ToolbarFragment(R.layout.layout_dashboard) {

    private var _binding: LayoutDashboardBinding? = null
    private val binding get() = _binding!!

    private var latestProfileName: String? = null
    private var latestGroupName: String? = null
    private var latestTxRate = 0L
    private var latestRxRate = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = LayoutDashboardBinding.bind(view)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.dashboard_title)

        binding.dashboardConnectButton.setOnClickListener {
            (activity as? MainActivity)?.toggleConnection()
        }
        binding.dashboardTestButton.setOnClickListener {
            (activity as? MainActivity)?.runConnectionTest()
        }

        binding.quickProfiles.setOnClickListener { navigate(R.id.nav_configuration) }
        binding.quickGroups.setOnClickListener { navigate(R.id.nav_group) }
        binding.quickRoutes.setOnClickListener { navigate(R.id.nav_route) }
        binding.quickTraffic.setOnClickListener { navigate(R.id.nav_traffic) }
        binding.quickTools.setOnClickListener { navigate(R.id.nav_tools) }
        binding.quickSettings.setOnClickListener { navigate(R.id.nav_settings) }

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
        _binding = null
        super.onDestroyView()
    }

    fun refreshDashboard() {
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                DataStore.ensureGroupsLoaded()
                val group = DataStore.currentGroup().displayName()
                val profile = ProfileManager.getProfile(DataStore.selectedProxy)?.displayName()
                val serviceMode = if (DataStore.serviceMode == Key.MODE_VPN) {
                    getString(R.string.service_mode_vpn)
                } else {
                    getString(R.string.service_mode_proxy)
                }
                DashboardSummary(profile, group, serviceMode)
            }

            val binding = _binding ?: return@launch
            latestProfileName = summary.profileName
            latestGroupName = summary.groupName
            binding.serviceModeValue.text = summary.serviceModeLabel
            binding.activeProfileValue.text =
                summary.profileName ?: getString(R.string.dashboard_no_profile)
            binding.activeGroupValue.text = summary.groupName
            updateConnectionState(DataStore.serviceState)
        }
    }

    fun updateConnectionState(state: BaseService.State) {
        val binding = _binding ?: return
        binding.connectionStateValue.text = getText(
            when (state) {
                BaseService.State.Connected -> R.string.vpn_connected
                BaseService.State.Connecting -> R.string.connecting
                BaseService.State.Stopping -> R.string.stopping
                else -> R.string.not_connected
            }
        )
        binding.connectionHeadline.text = getText(
            when (state) {
                BaseService.State.Connected -> R.string.dashboard_state_connected
                BaseService.State.Connecting -> R.string.dashboard_state_connecting
                BaseService.State.Stopping -> R.string.dashboard_state_stopping
                else -> R.string.dashboard_state_ready
            }
        )
        binding.connectionSummary.text = when (state) {
            BaseService.State.Connected -> getString(
                R.string.dashboard_using_profile_group,
                latestProfileName ?: getString(R.string.dashboard_no_profile),
                latestGroupName ?: getString(R.string.group_default)
            )
            BaseService.State.Connecting -> getString(R.string.dashboard_connecting_summary)
            BaseService.State.Stopping -> getString(R.string.dashboard_stopping_summary)
            else -> {
                if (latestProfileName.isNullOrBlank()) {
                    getString(R.string.dashboard_ready_without_profile)
                } else {
                    getString(
                        R.string.dashboard_ready_with_profile,
                        latestProfileName,
                        latestGroupName ?: getString(R.string.group_default)
                    )
                }
            }
        }
        binding.dashboardConnectButton.text = getText(
            if (state.canStop) R.string.stop else R.string.connect
        )
        binding.dashboardTestButton.isEnabled = state.connected
        binding.dashboardTestButton.isVisible = true
    }

    fun updateSpeeds(txRate: Long, rxRate: Long) {
        latestTxRate = txRate
        latestRxRate = rxRate
        val binding = _binding ?: return
        binding.uploadValue.text = formatSpeed(txRate)
        binding.downloadValue.text = formatSpeed(rxRate)
    }

    private fun navigate(itemId: Int) {
        (activity as? MainActivity)?.displayFragmentWithId(itemId)
    }

    private fun formatSpeed(rate: Long): String {
        return if (rate <= 0L) {
            getString(R.string.dashboard_speed_idle)
        } else {
            getString(R.string.speed, Formatter.formatFileSize(requireContext(), rate))
        }
    }

    private data class DashboardSummary(
        val profileName: String?,
        val groupName: String,
        val serviceModeLabel: String,
    )
}
