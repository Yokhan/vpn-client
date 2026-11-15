package com.yokhan.gates.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.EditText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yokhan.gates.app.diagnostics.AndroidRecoveryDelivery
import com.yokhan.gates.app.diagnostics.YokhanBugReporter
import com.yokhan.gates.app.provider.YokhanNetworkProviderDetector
import com.yokhan.gates.app.provider.YokhanNetworkProviderStatusMapper
import com.yokhan.gates.app.routing.RuCompatibilityClassifier
import com.yokhan.gates.app.update.YokhanAppUpdater
import com.yokhan.gates.shared.ui.route.YokhanBody
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.utils.PackageCache
import com.yokhan.gates.shared.ui.route.YokhanActionRow
import com.yokhan.gates.shared.ui.route.YokhanButtonGrid
import com.yokhan.gates.shared.ui.route.YokhanCaption
import com.yokhan.gates.app.ui.compose.YokhanComposeRouteFragment
import com.yokhan.gates.shared.ui.route.YokhanDivider
import com.yokhan.gates.shared.ui.route.YokhanHeadline
import com.yokhan.gates.shared.ui.route.YokhanMonoValue
import com.yokhan.gates.shared.ui.route.YokhanRouteAction
import com.yokhan.gates.shared.ui.route.YokhanRouteButton
import com.yokhan.gates.shared.ui.route.YokhanRouteCard
import com.yokhan.gates.shared.ui.route.YokhanRouteFrame
import com.yokhan.gates.shared.ui.route.YokhanStatusPill
import com.yokhan.gates.shared.ui.route.YokhanToggleRow
import com.yokhan.gates.app.ui.shell.YokhanShellActivityRoute
import com.yokhan.gates.app.ui.shell.YokhanShellRoute
import com.yokhan.gates.app.ui.shell.YokhanShellSubRoute
import com.yokhan.gates.shared.domain.NetworkProviderState
import com.yokhan.gates.shared.domain.NetworkProviderSummary
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.snackbar
import org.json.JSONObject

class SettingsFragment : YokhanComposeRouteFragment(),
    OnPreferenceDataStoreChangeListener {

    private val settingsState = mutableStateOf<SettingsUiState?>(null)

    private companion object {
        const val TAG = "GatesBugReport"
    }

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
            Key.ENABLE_RU_BYPASS,
            Key.WHOLE_TRAFFIC_MODE,
            Key.ALLOW_ACCESS -> view?.post { refreshUi() }
        }
    }

    @Composable
    override fun Content(spaceActive: Boolean) {
        val state = settingsState.value
        var diagnosticsExpanded by remember { mutableStateOf(false) }
        YokhanRouteFrame(
            title = stringResource(R.string.settings),
            systemLabel = stringResource(R.string.system_route_label, "05"),
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
                com.yokhan.gates.shared.ui.route.YokhanBody(
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
                title = stringResource(R.string.settings_ru_bypass_card_title),
                colors = colors,
                typography = typography,
            ) {
                YokhanToggleRow(
                    title = stringResource(R.string.settings_ru_bypass_toggle_title),
                    summary = stringResource(R.string.settings_ru_bypass_toggle_summary),
                    checked = state?.ruBypassEnabled == true,
                    colors = colors,
                    typography = typography,
                    onCheckedChange = { enabled ->
                        DataStore.enableRuBypass = enabled
                        if (enabled && DataStore.wholeTrafficMode) {
                            DataStore.wholeTrafficMode = false
                        }
                        needReload()
                        refreshUi()
                    },
                )
                YokhanDivider(colors, Modifier.padding(vertical = 8.dp))
                YokhanMonoValue(
                    text = if (state?.ruBypassEnabled == true) {
                        state.ruCompatibilityValue
                    } else {
                        stringResource(R.string.settings_ru_bypass_disabled_hint)
                    },
                    colors = colors,
                    typography = typography,
                    sizeSp = 12,
                    maxLines = 2,
                )
                YokhanDivider(colors, Modifier.padding(top = 10.dp, bottom = 4.dp))
                YokhanActionRow(
                    title = stringResource(R.string.settings_ru_bypass_open_list),
                    colors = colors,
                    typography = typography,
                    onClick = {
                        (activity as? MainActivity)?.openActivityRoute(
                            YokhanShellActivityRoute.RuCompatibility,
                        )
                    },
                    accent = true,
                )
            }

            YokhanRouteCard(
                title = stringResource(R.string.app_name_long),
                colors = colors,
                typography = typography,
            ) {
                YokhanMonoValue(
                    text = SagerNet.appVersionNameForDisplay,
                    colors = colors,
                    typography = typography,
                    sizeSp = 13,
                )
                YokhanCaption(
                    text = stringResource(R.string.app_version),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 4.dp),
                )
                YokhanDivider(colors, Modifier.padding(top = 14.dp, bottom = 4.dp))
                YokhanActionRow(
                    title = stringResource(R.string.bug_report_title),
                    colors = colors,
                    typography = typography,
                    onClick = ::showBugReportDialog,
                    accent = true,
                )
                YokhanDivider(colors, Modifier.padding(vertical = 4.dp))
                YokhanActionRow(
                    title = stringResource(R.string.check_update_release),
                    colors = colors,
                    typography = typography,
                    onClick = { YokhanAppUpdater.checkManually(requireContext(), checkPreview = false) },
                )
                YokhanDivider(colors, Modifier.padding(vertical = 4.dp))
                YokhanActionRow(
                    title = "История соединения",
                    colors = colors,
                    typography = typography,
                    onClick = {
                        (activity as? MainActivity)?.displayFragment(
                            ToolsFragment.incidentHistory(), addToBackStack = true,
                        )
                    },
                )
                YokhanDivider(colors, Modifier.padding(vertical = 4.dp))
                YokhanActionRow(
                    title = stringResource(R.string.menu_tools),
                    subtitle = "STUN-проверка, резервные копии, экспорт",
                    colors = colors,
                    typography = typography,
                    onClick = {
                        (activity as? MainActivity)?.displayFragment(
                            ToolsFragment(), addToBackStack = true,
                        )
                    },
                )
                if (BuildConfig.DEBUG) {
                    YokhanDivider(colors, Modifier.padding(vertical = 4.dp))
                    YokhanActionRow(
                        title = stringResource(R.string.menu_log),
                        colors = colors,
                        typography = typography,
                        onClick = {
                            (activity as? MainActivity)?.displayFragment(
                                LogcatFragment(), addToBackStack = true,
                            )
                        },
                    )
                }
                YokhanDivider(colors, Modifier.padding(vertical = 4.dp))
                YokhanActionRow(
                    title = stringResource(R.string.menu_about),
                    colors = colors,
                    typography = typography,
                    onClick = {
                        (activity as? MainActivity)?.displayFragment(
                            AboutFragment(), addToBackStack = true,
                        )
                    },
                )
            }

            if (BuildConfig.DEBUG) {
                YokhanRouteCard(
                    title = stringResource(R.string.settings_provider_card_title),
                    colors = colors,
                    typography = typography,
                ) {
                    YokhanActionRow(
                        title = stringResource(
                            if (diagnosticsExpanded) {
                                R.string.settings_dev_diagnostics_hide
                            } else {
                                R.string.settings_dev_diagnostics_show
                            },
                        ),
                        subtitle = state?.providerTitle ?: "--",
                        trailingText = if (diagnosticsExpanded) "▴" else "▾",
                        colors = colors,
                        typography = typography,
                        onClick = { diagnosticsExpanded = !diagnosticsExpanded },
                    )
                    AnimatedVisibility(visible = diagnosticsExpanded) {
                        Column(Modifier.fillMaxWidth()) {
                            YokhanDivider(colors, Modifier.padding(vertical = 10.dp))
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
                            YokhanDivider(colors, Modifier.padding(vertical = 10.dp))
                            YokhanMonoValue(state?.appRoutingValue ?: "--", colors, typography, sizeSp = 12)
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
                                modifier = Modifier.padding(top = 10.dp),
                                sizeSp = 11,
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
                                modifier = Modifier.padding(top = 10.dp),
                                sizeSp = 11,
                                maxLines = 2,
                            )
                            YokhanCaption(
                                text = stringResource(R.string.settings_network_title),
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
                    }
                }
            }

            YokhanRouteCard(
                title = stringResource(R.string.settings_quick_switches_title),
                colors = colors,
                typography = typography,
            ) {
                YokhanToggleRow(
                    title = stringResource(R.string.whole_traffic_mode),
                    summary = stringResource(R.string.whole_traffic_mode_summary),
                    checked = state?.wholeTrafficMode == true,
                    colors = colors,
                    typography = typography,
                    onCheckedChange = { enabled ->
                        DataStore.wholeTrafficMode = enabled
                        if (enabled) {
                            DataStore.enableRuBypass = false
                        }
                        needReload()
                        refreshUi()
                    },
                )
                YokhanDivider(colors, Modifier.padding(vertical = 10.dp))
                if (BuildConfig.DEBUG) {
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
                }
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
                YokhanDivider(colors, Modifier.padding(vertical = 10.dp))
                YokhanToggleRow(
                    title = stringResource(R.string.settings_automatic_recovery_reports_title),
                    summary = stringResource(R.string.settings_automatic_recovery_reports_summary),
                    checked = state?.automaticRecoveryReports == true,
                    colors = colors,
                    typography = typography,
                    onCheckedChange = {
                        DataStore.automaticRecoveryReports = it
                        AndroidRecoveryDelivery.settingsChanged(requireContext())
                        refreshUi()
                    },
                )
            }

            YokhanRouteCard(
                title = stringResource(R.string.settings_protection_card_title),
                colors = colors,
                typography = typography,
            ) {
                YokhanHeadline(
                    text = state?.protectionStatusTitle ?: stringResource(R.string.settings_protection_off),
                    colors = colors,
                    typography = typography,
                )
                YokhanBody(
                    text = stringResource(R.string.settings_protection_summary),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 6.dp),
                    maxLines = 4,
                )
                YokhanDivider(colors, Modifier.padding(top = 12.dp, bottom = 4.dp))
                YokhanActionRow(
                    title = stringResource(R.string.settings_protection_open_system),
                    colors = colors,
                    typography = typography,
                    onClick = ::openSystemVpnSettings,
                    accent = true,
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
                YokhanDivider(colors, Modifier.padding(top = 14.dp, bottom = 4.dp))
                YokhanActionRow(
                    title = stringResource(R.string.settings_manage_apps),
                    colors = colors,
                    typography = typography,
                    onClick = {
                        (activity as? MainActivity)?.openActivityRoute(
                            YokhanShellActivityRoute.AppManager,
                        )
                    },
                )
                YokhanDivider(colors, Modifier.padding(vertical = 4.dp))
                YokhanActionRow(
                    title = stringResource(R.string.settings_usb_bridge),
                    colors = colors,
                    typography = typography,
                    onClick = {
                        (activity as? MainActivity)?.openSubRoute(
                            YokhanShellSubRoute.UsbBridge,
                        )
                    },
                )
                YokhanDivider(colors, Modifier.padding(vertical = 4.dp))
                YokhanActionRow(
                    title = stringResource(R.string.settings_open_advanced),
                    colors = colors,
                    typography = typography,
                    onClick = {
                        (activity as? MainActivity)?.openSubRoute(
                            YokhanShellSubRoute.AdvancedSettings,
                        )
                    },
                    accent = true,
                )
            }
        }
    }

    private fun openSystemVpnSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        }.onFailure { error ->
            snackbar(error.readableMessage).show()
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
        val initialCompatibilitySummary = settingsState.value?.ruCompatibilityValue ?: "--"
        val alwaysOnPkg = runCatching {
            Settings.Secure.getString(context.contentResolver, "always_on_vpn_app").orEmpty()
        }.getOrDefault("")
        val alwaysOnVpnLockdown = runCatching {
            Settings.Secure.getInt(context.contentResolver, "always_on_vpn_lockdown", 0) == 1
        }.getOrDefault(false)
        val protectionTitle = when {
            alwaysOnPkg == context.packageName && alwaysOnVpnLockdown -> getString(R.string.settings_protection_lockdown_on)
            alwaysOnPkg == context.packageName -> getString(R.string.settings_protection_always_on)
            else -> getString(R.string.settings_protection_off)
        }
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
            ruBypassEnabled = DataStore.enableRuBypass && !DataStore.wholeTrafficMode,
            protectionStatusTitle = protectionTitle,
            wholeTrafficMode = DataStore.wholeTrafficMode,
            stealthMode = DataStore.stealthMode,
            directSpeed = DataStore.showDirectSpeed,
            autoConnect = DataStore.configurationStore.getBoolean(Key.PERSIST_ACROSS_REBOOT, false),
            automaticRecoveryReports = DataStore.automaticRecoveryReports,
            appRoutingValue = getString(
                if (DataStore.proxyApps) {
                    R.string.settings_proxy_apps_enabled
                } else {
                    R.string.settings_proxy_apps_disabled
                }
            ),
            ruCompatibilityValue = initialCompatibilitySummary,
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
        val appContext = context.applicationContext
        runOnDefaultDispatcher {
            val resolvedSummary = compatibilitySummary(appContext)
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                settingsState.value = settingsState.value?.copy(ruCompatibilityValue = resolvedSummary)
            }
        }
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

    private fun showBugReportDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.bug_report_hint)
            minLines = 3
            maxLines = 5
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bug_report_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.bug_report_send) { _, _ ->
                val message = input.text?.toString().orEmpty()
                    .ifBlank { getString(R.string.bug_report_default_message) }
                sendBugReport(message)
            }
            .show()
    }

    private fun sendBugReport(message: String) {
        val appContext = requireContext().applicationContext
        snackbar(getString(R.string.bug_report_sending)).show()
        Log.i(TAG, "bug_report_send_start version=${BuildConfig.VERSION_NAME}")
        runOnDefaultDispatcher {
            runCatching {
                val diagnosticsJson = buildQuickBugDiagnostics()
                YokhanBugReporter.send(appContext, message, diagnosticsJson)
            }.onSuccess { bugResult ->
                if (bugResult.uploaded) {
                    Log.i(TAG, "bug_report_uploaded id=${bugResult.reportId.ifBlank { "ok" }}")
                } else {
                    Log.w(TAG, "bug_report_saved file=${bugResult.localFile.name} details=${bugResult.details.take(300)}")
                }
                onMainDispatcher {
                    if (!isAdded) return@onMainDispatcher
                    val snackbarText = if (bugResult.uploaded) {
                        getString(R.string.bug_report_sent, bugResult.reportId.ifBlank { "ok" })
                    } else {
                        getString(R.string.bug_report_saved, bugResult.localFile.name)
                    }
                    snackbar(snackbarText).show()
                }
            }.onFailure { error ->
                Log.e(TAG, "bug_report_failed", error)
                onMainDispatcher {
                    if (!isAdded) return@onMainDispatcher
                    snackbar(error.readableMessage).show()
                }
            }
        }
    }

    private fun buildQuickBugDiagnostics(): String {
        return JSONObject()
            .put("captured_at_millis", System.currentTimeMillis())
            .put("source", "settings")
            .put(
                "app",
                JSONObject()
                    .put("package", BuildConfig.APPLICATION_ID)
                    .put("version_name", BuildConfig.VERSION_NAME)
                    .put("version_code", BuildConfig.VERSION_CODE)
                    .put("sdk", android.os.Build.VERSION.SDK_INT),
            )
            .put(
                "runtime",
                JSONObject()
                    .put("service_state", DataStore.serviceState.name)
                    .put("service_mode", DataStore.serviceMode)
                    .put("selected_proxy_id", DataStore.selectedProxy)
                    .put("selected_group_id", DataStore.selectedGroup)
                    .put("mixed_port", DataStore.mixedPort)
                    .put("stealth_mode", DataStore.stealthMode)
                    .put("ru_bypass_enabled", DataStore.enableRuBypass)
                    .put("boot_elapsed_realtime_ms", SystemClock.elapsedRealtime()),
            )
            .toString(2)
    }

    private fun compatibilitySummary(context: android.content.Context): String {
        val classifier = RuCompatibilityClassifier(context)
        val ruleCount = classifier.countRules()
        val installedPackages = runCatching {
            PackageCache.installedPackages.keys.filterNot { it == context.packageName }
        }.getOrDefault(emptyList())
        val installers = if (installedPackages.isEmpty()) {
            emptyMap()
        } else {
            RuCompatibilityClassifier.resolveInstallers(context.packageManager, installedPackages)
        }
        val directCount = if (installedPackages.isEmpty()) {
            0
        } else {
            classifier.directPackages(
                installedPackages = installedPackages,
                installerPackages = installers,
            ).size
        }
        return context.getString(
            R.string.settings_ru_compatibility_summary,
            directCount,
            installedPackages.size,
            ruleCount.exactDirect,
            ruleCount.prefixDirect,
        )
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
        val protectionStatusTitle: String,
        val ruBypassEnabled: Boolean,
        val wholeTrafficMode: Boolean,
        val stealthMode: Boolean,
        val directSpeed: Boolean,
        val autoConnect: Boolean,
        val automaticRecoveryReports: Boolean,
        val appRoutingValue: String,
        val ruCompatibilityValue: String,
        val dnsSummaryValue: String,
        val networkSummaryValue: String,
        val advancedSummaryValue: String,
    )
}
