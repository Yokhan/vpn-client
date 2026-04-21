package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.preference.PreferenceDataStore
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutSettingsHubBinding
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.widget.ListListener

class SettingsFragment : ToolbarFragment(R.layout.layout_settings_hub),
    OnPreferenceDataStoreChangeListener {

    private var _binding: LayoutSettingsHubBinding? = null
    private val binding get() = _binding!!
    private var updatingUi = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = LayoutSettingsHubBinding.bind(view)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.settings)

        binding.serviceModeButton.setOnClickListener {
            DataStore.serviceMode = if (DataStore.serviceMode == Key.MODE_VPN) {
                Key.MODE_PROXY
            } else {
                Key.MODE_VPN
            }
            if (DataStore.serviceState.started) {
                SagerNet.stopServiceAsync()
            }
            refreshUi()
        }
        binding.manageAppsButton.setOnClickListener {
            startActivity(Intent(requireActivity(), AppManagerActivity::class.java))
        }
        binding.openRoutesButton.setOnClickListener {
            (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_route)
        }
        binding.advancedSettingsButton.setOnClickListener {
            (activity as? MainActivity)?.openSubFragment(SettingsAdvancedFragment())
        }

        binding.stealthSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            applyStealthMode(isChecked)
            needReload()
            refreshUi()
        }
        binding.directSpeedSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            DataStore.showDirectSpeed = isChecked
            needReload()
            refreshUi()
        }
        binding.autoConnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            DataStore.configurationStore.putBoolean(Key.PERSIST_ACROSS_REBOOT, isChecked)
            refreshUi()
        }

        DataStore.configurationStore.registerChangeListener(this)
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroyView() {
        DataStore.configurationStore.unregisterChangeListener(this)
        _binding = null
        super.onDestroyView()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (store !== DataStore.configurationStore) return
        when (key) {
            Key.SERVICE_MODE,
            Key.STEALTH_MODE,
            Key.SHOW_DIRECT_SPEED,
            Key.PERSIST_ACROSS_REBOOT,
            Key.PROXY_APPS,
            Key.REMOTE_DNS,
            Key.DIRECT_DNS,
            Key.ENABLE_DNS_ROUTING,
            Key.ENABLE_FAKEDNS,
            Key.ENABLE_LOCAL_INBOUND,
            Key.ENABLE_CLASH_API,
            Key.ALLOW_ACCESS -> view?.post { refreshUi() }
        }
    }

    private fun refreshUi() {
        val binding = _binding ?: return
        updatingUi = true

        val isVpnMode = DataStore.serviceMode == Key.MODE_VPN
        binding.serviceModeValue.text = getString(
            if (isVpnMode) R.string.service_mode_vpn else R.string.service_mode_proxy
        )
        binding.serviceModeButton.text = getString(
            if (isVpnMode) R.string.settings_switch_to_proxy else R.string.settings_switch_to_vpn
        )
        binding.serviceModeSummary.text = getString(
            if (isVpnMode) {
                R.string.settings_service_mode_vpn_summary
            } else {
                R.string.settings_service_mode_proxy_summary
            }
        )

        binding.stealthSwitch.isChecked = DataStore.stealthMode
        binding.directSpeedSwitch.isChecked = DataStore.showDirectSpeed
        binding.autoConnectSwitch.isChecked =
            DataStore.configurationStore.getBoolean(Key.PERSIST_ACROSS_REBOOT, false)

        binding.appRoutingValue.text = getString(
            if (DataStore.proxyApps) {
                R.string.settings_proxy_apps_enabled
            } else {
                R.string.settings_proxy_apps_disabled
            }
        )
        binding.dnsSummaryValue.text = getString(
            R.string.settings_dns_summary_value,
            DataStore.remoteDns,
            DataStore.directDns
        )
        binding.networkSummaryValue.text = getString(
            R.string.settings_network_summary_value,
            getString(if (DataStore.enableDnsRouting) R.string.on else R.string.off),
            getString(if (DataStore.enableFakeDns) R.string.on else R.string.off),
            getString(if (DataStore.allowAccess) R.string.on else R.string.off)
        )
        binding.advancedSummaryValue.text = getString(
            R.string.settings_advanced_summary_value,
            getString(if (DataStore.enableLocalInbound) R.string.on else R.string.off),
            getString(if (DataStore.enableClashAPI) R.string.on else R.string.off)
        )

        updatingUi = false
    }

    private fun applyStealthMode(enabled: Boolean) {
        DataStore.stealthMode = enabled
        if (enabled) {
            if (DataStore.enableLocalInbound) {
                DataStore.enableLocalInbound = false
            }
            if (DataStore.appendHttpProxy) {
                DataStore.appendHttpProxy = false
            }
            if (DataStore.allowAccess) {
                DataStore.allowAccess = false
            }
            if (DataStore.enableClashAPI) {
                DataStore.enableClashAPI = false
            }
        }
        (activity as? MainActivity)?.refreshNavMenu(DataStore.enableClashAPI && !enabled)
    }
}
