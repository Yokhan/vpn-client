package com.yokhan.vpn.app.ui

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceDataStore
import com.yokhan.vpn.app.provider.YokhanNetworkProviderDetector
import com.yokhan.vpn.app.provider.YokhanNetworkProviderStatusMapper
import com.yokhan.vpn.app.ui.compose.YokhanBody
import com.yokhan.vpn.shared.ui.design.YokhanSpacing
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.needReload
import com.yokhan.vpn.app.ui.compose.YokhanButtonGrid
import com.yokhan.vpn.app.ui.compose.YokhanCaption
import com.yokhan.vpn.app.ui.compose.YokhanComposeRouteFragment
import com.yokhan.vpn.app.ui.compose.YokhanDivider
import com.yokhan.vpn.app.ui.compose.YokhanHeadline
import com.yokhan.vpn.app.ui.compose.YokhanMonoValue
import com.yokhan.vpn.app.ui.compose.YokhanRouteAction
import com.yokhan.vpn.app.ui.compose.YokhanRouteButton
import com.yokhan.vpn.app.ui.compose.YokhanRouteCard
import com.yokhan.vpn.app.ui.compose.YokhanRouteFrame
import com.yokhan.vpn.app.ui.compose.YokhanStatusPill
import com.yokhan.vpn.app.ui.compose.YokhanToggleRow
import com.yokhan.vpn.app.ui.shell.YokhanShellActivityRoute
import com.yokhan.vpn.app.ui.shell.YokhanShellRoute
import com.yokhan.vpn.app.ui.shell.YokhanShellSubRoute
import com.yokhan.vpn.shared.domain.NetworkProviderState
import com.yokhan.vpn.shared.domain.NetworkProviderSummary

class SettingsFragment : YokhanComposeRouteFragment(),
    OnPreferenceDataStoreChangeListener {

    private val settingsState = mutableStateOf<SettingsUiState?>(null)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        DataStore.configurationStore.registerChangeListener(this)
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroyView() {
        DataStore.configurationStore.unregisterChangeListener(this)
        settingsState.value = null
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

    @Composable
    override fun Content(spaceActive: Boolean) {
        val state = settingsState.value
        YokhanRouteFrame(
            title = stringResource(R.string.settings),
            systemLabel = stringResource(R.string.dashboard_system_label),
            spaceActive = spaceActive,
            onMenuClick = { (activity as? MainActivity)?.openNavigationMenu() },
        ) { colors, typography ->
            YokhanRouteCard(
                title = stringResource(R.string.settings_service_mode_card_title),
                colors = colors,
                typography = typography,
            ) {
                YokhanHeadline(
                    text = state?.serviceModeValue ?: "--",
                    colors = colors,
                    typography = typography,
                )
                com.yokhan.vpn.app.ui.compose.YokhanBody(
                    text = state?.serviceModeSummary ?: "",
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 5.dp),
                    maxLines = 4,
                )
                YokhanRouteButton(
                    text = state?.serviceModeButton ?: stringResource(R.string.settings_switch_to_proxy),
                    primary = true,
                    enabled = true,
                    colors = colors,
                    typography = typography,
                    onClick = ::toggleServiceMode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                )
            }

            YokhanRouteCard(
                title = stringResource(R.string.settings_provider_card_title),
                colors = colors,
                typography = typography,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    YokhanMonoValue(
                        text = state?.providerTitle ?: "--",
                        colors = colors,
                        typography = typography,
                        modifier = Modifier.weight(1f),
                        sizeSp = 13,
                        maxLines = 2,
                    )
                    YokhanStatusPill(
                        text = state?.providerStateLabel ?: "--",
                        active = state?.providerCanUse == true,
                        colors = colors,
                        typography = typography,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                YokhanBody(
                    text = state?.providerSummary ?: "",
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 8.dp),
                    maxLines = 4,
                )
                YokhanMonoValue(
                    text = state?.providerDetails ?: "--",
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 13.dp),
                    sizeSp = 11,
                    maxLines = 3,
                )
                YokhanCaption(
                    text = stringResource(R.string.settings_provider_details_label),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 4.dp),
                )
                YokhanRouteButton(
                    text = stringResource(R.string.settings_provider_recheck),
                    primary = false,
                    enabled = true,
                    colors = colors,
                    typography = typography,
                    onClick = ::refreshUi,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                )
            }

            YokhanRouteCard(
                title = stringResource(R.string.settings_quick_switches_title),
                colors = colors,
                typography = typography,
            ) {
                YokhanToggleRow(
                    title = stringResource(R.string.settings_stealth_title),
                    summary = stringResource(R.string.settings_stealth_summary),
                    checked = state?.stealthMode == true,
                    colors = colors,
                    typography = typography,
                    onCheckedChange = {
                        applyStealthMode(it)
                        needReload()
                        refreshUi()
                    },
                )
                YokhanDivider(colors, Modifier.padding(vertical = 10.dp))
                YokhanToggleRow(
                    title = stringResource(R.string.settings_direct_speed_title),
                    summary = stringResource(R.string.settings_direct_speed_summary),
                    checked = state?.directSpeed == true,
                    colors = colors,
                    typography = typography,
                    onCheckedChange = {
                        DataStore.showDirectSpeed = it
                        needReload()
                        refreshUi()
                    },
                )
                YokhanDivider(colors, Modifier.padding(vertical = 10.dp))
                YokhanToggleRow(
                    title = stringResource(R.string.settings_auto_connect_title),
                    summary = stringResource(R.string.settings_auto_connect_summary),
                    checked = state?.autoConnect == true,
                    colors = colors,
                    typography = typography,
                    onCheckedChange = {
                        DataStore.configurationStore.putBoolean(Key.PERSIST_ACROSS_REBOOT, it)
                        refreshUi()
                    },
                )
            }

            YokhanRouteCard(
                title = stringResource(R.string.settings_runtime_title),
                colors = colors,
                typography = typography,
            ) {
                YokhanMonoValue(state?.appRoutingValue ?: "--", colors, typography, sizeSp = 13)
                YokhanCaption(
                    text = stringResource(R.string.settings_apps_title),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 4.dp),
                )
                YokhanMonoValue(
                    text = state?.dnsSummaryValue ?: "--",
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 13.dp),
                    sizeSp = 12,
                    maxLines = 3,
                )
                YokhanCaption(
                    text = stringResource(R.string.settings_dns_title),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 4.dp),
                )
                YokhanMonoValue(
                    text = state?.networkSummaryValue ?: "--",
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 13.dp),
                    sizeSp = 12,
                    maxLines = 2,
                )
                YokhanCaption(
                    text = stringResource(R.string.settings_network_title),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            YokhanRouteCard(
                title = stringResource(R.string.settings_advanced_title_card),
                colors = colors,
                typography = typography,
            ) {
                YokhanMonoValue(
                    text = state?.advancedSummaryValue ?: "--",
                    colors = colors,
                    typography = typography,
                    sizeSp = 12,
                    maxLines = 2,
                )
                YokhanCaption(
                    text = stringResource(R.string.settings_advanced_title),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 4.dp),
                )
                YokhanButtonGrid(
                    actions = listOf(
                        YokhanRouteAction(
                            title = stringResource(R.string.settings_manage_apps),
                            onClick = {
                                (activity as? MainActivity)?.openActivityRoute(
                                    YokhanShellActivityRoute.AppManager,
                                )
                            },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.settings_open_routes),
                            onClick = { (activity as? MainActivity)?.displayRoute(YokhanShellRoute.Route) },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.settings_open_advanced),
                            primary = true,
                            onClick = {
                                (activity as? MainActivity)?.openSubRoute(
                                    YokhanShellSubRoute.AdvancedSettings,
                                )
                            },
                        ),
                    ),
                    colors = colors,
                    typography = typography,
                        modifier = Modifier.padding(top = YokhanSpacing.ActionTopGap),
                )
            }
        }
    }

    private fun toggleServiceMode() {
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

    private fun refreshUi() {
        val context = context ?: return
        val isVpnMode = DataStore.serviceMode == Key.MODE_VPN
        val provider = YokhanNetworkProviderStatusMapper.toSummary(
            YokhanNetworkProviderDetector(
                packageManager = context.packageManager,
                shellPackageName = context.packageName,
            ).detect(),
        )
        settingsState.value = SettingsUiState(
            serviceModeValue = getString(
                if (isVpnMode) R.string.service_mode_vpn else R.string.service_mode_proxy
            ),
            serviceModeButton = getString(
                if (isVpnMode) R.string.settings_switch_to_proxy else R.string.settings_switch_to_vpn
            ),
            serviceModeSummary = getString(
                if (isVpnMode) {
                    R.string.settings_service_mode_vpn_summary
                } else {
                    R.string.settings_service_mode_proxy_summary
                }
            ),
            providerTitle = provider.titleText(),
            providerStateLabel = provider.stateLabel(),
            providerSummary = provider.summaryText(),
            providerDetails = provider.detailsText(),
            providerCanUse = provider.canUseProvider,
            stealthMode = DataStore.stealthMode,
            directSpeed = DataStore.showDirectSpeed,
            autoConnect = DataStore.configurationStore.getBoolean(Key.PERSIST_ACROSS_REBOOT, false),
            appRoutingValue = getString(
                if (DataStore.proxyApps) {
                    R.string.settings_proxy_apps_enabled
                } else {
                    R.string.settings_proxy_apps_disabled
                }
            ),
            dnsSummaryValue = getString(
                R.string.settings_dns_summary_value,
                DataStore.remoteDns,
                DataStore.directDns
            ),
            networkSummaryValue = getString(
                R.string.settings_network_summary_value,
                getString(if (DataStore.enableDnsRouting) R.string.on else R.string.off),
                getString(if (DataStore.enableFakeDns) R.string.on else R.string.off),
                getString(if (DataStore.allowAccess) R.string.on else R.string.off)
            ),
            advancedSummaryValue = getString(
                R.string.settings_advanced_summary_value,
                getString(if (DataStore.enableLocalInbound) R.string.on else R.string.off),
                getString(if (DataStore.enableClashAPI) R.string.on else R.string.off)
            ),
        )
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

    private fun NetworkProviderSummary.titleText(): String {
        return when (state) {
            NetworkProviderState.CurrentMonolith -> getString(R.string.settings_provider_title_monolith)
            NetworkProviderState.ExternalReady -> getString(R.string.settings_provider_title_ready)
            NetworkProviderState.Missing -> getString(R.string.settings_provider_title_missing)
            NetworkProviderState.Untrusted -> getString(R.string.settings_provider_title_untrusted)
            NetworkProviderState.Outdated -> getString(R.string.settings_provider_title_outdated)
        }
    }

    private fun NetworkProviderSummary.stateLabel(): String {
        return when (state) {
            NetworkProviderState.CurrentMonolith -> getString(R.string.settings_provider_state_monolith)
            NetworkProviderState.ExternalReady -> getString(R.string.settings_provider_state_ready)
            NetworkProviderState.Missing -> getString(R.string.settings_provider_state_missing)
            NetworkProviderState.Untrusted -> getString(R.string.settings_provider_state_untrusted)
            NetworkProviderState.Outdated -> getString(R.string.settings_provider_state_outdated)
        }
    }

    private fun NetworkProviderSummary.summaryText(): String {
        return when (state) {
            NetworkProviderState.CurrentMonolith -> getString(R.string.settings_provider_summary_monolith)
            NetworkProviderState.ExternalReady -> getString(
                R.string.settings_provider_summary_ready,
                packageName.orEmpty(),
            )
            NetworkProviderState.Missing -> getString(
                R.string.settings_provider_summary_missing,
                checkedPackages.joinToString(),
            )
            NetworkProviderState.Untrusted -> getString(
                R.string.settings_provider_summary_untrusted,
                packageName.orEmpty(),
            )
            NetworkProviderState.Outdated -> getString(
                R.string.settings_provider_summary_outdated,
                packageName.orEmpty(),
            )
        }
    }

    private fun NetworkProviderSummary.detailsText(): String {
        val packageLine = packageName ?: checkedPackages.joinToString()
        val versionLine = versionName?.let { "$it ($versionCode)" } ?: "--"
        val certLine = certificateSha256?.take(16)?.let { "$it..." } ?: "--"
        return getString(R.string.settings_provider_details_value, packageLine, versionLine, certLine)
    }

    private data class SettingsUiState(
        val serviceModeValue: String,
        val serviceModeButton: String,
        val serviceModeSummary: String,
        val providerTitle: String,
        val providerStateLabel: String,
        val providerSummary: String,
        val providerDetails: String,
        val providerCanUse: Boolean,
        val stealthMode: Boolean,
        val directSpeed: Boolean,
        val autoConnect: Boolean,
        val appRoutingValue: String,
        val dnsSummaryValue: String,
        val networkSummaryValue: String,
        val advancedSummaryValue: String,
    )
}
