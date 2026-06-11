package com.yokhan.gates.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yokhan.gates.app.ui.shell.YokhanShellActivityRoute
import com.yokhan.gates.app.ui.form.arrayOptions
import com.yokhan.gates.app.ui.form.selectValue
import com.yokhan.gates.app.ui.form.switchValue
import com.yokhan.gates.app.ui.form.textValue
import com.yokhan.gates.app.ui.form.withVisible
import com.yokhan.gates.app.ui.profile.ConfigEditActivity
import com.yokhan.gates.shared.ui.form.YokhanFormAction
import com.yokhan.gates.shared.ui.form.YokhanFormEvent
import com.yokhan.gates.shared.ui.form.YokhanFormField
import com.yokhan.gates.shared.ui.form.YokhanFormScreen
import com.yokhan.gates.shared.ui.form.YokhanFormSection
import com.yokhan.gates.shared.ui.form.YokhanFormState
import com.yokhan.gates.shared.ui.form.YokhanFormValidation
import com.yokhan.gates.shared.ui.form.YokhanFormValidationState
import com.yokhan.gates.shared.ui.form.hasBlockingValidationErrors
import com.yokhan.gates.shared.ui.form.withFieldEvent
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ktx.needRestart
import io.nekohasekai.sagernet.utils.Theme

class SettingsAdvancedFragment : ToolbarFragment() {

    private val formState = mutableStateOf<YokhanFormState?>(null)
    private var configBeforeEditor: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return requireContext().createYokhanSettingsHost(useComposeContainer = true)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.settings_advanced_title)
        DataStore.initGlobal()
        ensureAdvancedDefaults()
        formState.value = validateSettingsForm(buildSettingsForm())
        mountComposeForm(view)
    }

    override fun onResume() {
        super.onResume()
        if (formState.value == null) {
            formState.value = validateSettingsForm(buildSettingsForm())
        }
    }

    private fun mountComposeForm(root: View) {
        root.findViewById<ViewGroup>(R.id.settings).apply {
            removeAllViews()
            addView(
                ComposeView(requireContext()).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setContent {
                        formState.value?.let { state ->
                            YokhanFormScreen(state = state, onEvent = ::handleFormEvent)
                        }
                    }
                },
            )
        }
    }

    private fun handleFormEvent(event: YokhanFormEvent) {
        when (event) {
            YokhanFormEvent.Save -> applySettings()
            YokhanFormEvent.Cancel,
            YokhanFormEvent.Reset,
            -> parentFragmentManager.popBackStack()

            is YokhanFormEvent.ActionInvoked -> when (event.id) {
                FieldGlobalConfig -> openGlobalConfig()
                FieldProxyAppsManager -> {
                    (activity as? MainActivity)?.openActivityRoute(YokhanShellActivityRoute.AppManager)
                }
                FieldLogBufferSize -> showLogBufferDialog()
                else -> Unit
            }

            else -> updateForm(event)
        }
    }

    private fun updateForm(event: YokhanFormEvent) {
        val updated = formState.value?.withFieldEvent(event)?.let(::validateSettingsForm) ?: return
        formState.value = updated
    }

    private fun buildSettingsForm(dirty: Boolean = false): YokhanFormState {
        return YokhanFormState(
            title = getString(R.string.settings_advanced_title),
            subtitle = getString(R.string.settings),
            dirty = dirty,
            primaryAction = YokhanFormAction.Apply,
            sections = listOf(
                YokhanFormSection(
                    id = "general",
                    title = getString(R.string.general_settings).uppercase(),
                    fields = listOf(
                        YokhanFormField.Switch(
                            id = Key.PERSIST_ACROSS_REBOOT,
                            label = getString(R.string.auto_connect),
                            helper = getString(R.string.auto_connect_summary),
                            checked = DataStore.configurationStore.getBoolean(Key.PERSIST_ACROSS_REBOOT, false),
                        ),
                        YokhanFormField.Select(
                            id = Key.NIGHT_THEME,
                            label = getString(R.string.night_mode),
                            options = arrayOptions(R.array.night_mode, R.array.int_array_4),
                            selectedOptionId = DataStore.nightTheme.toString(),
                        ),
                        YokhanFormField.Select(
                            id = Key.SERVICE_MODE,
                            label = getString(R.string.service_mode),
                            options = arrayOptions(R.array.service_modes, R.array.service_mode_values),
                            selectedOptionId = DataStore.serviceMode,
                        ),
                        YokhanFormField.Select(
                            id = Key.TUN_IMPLEMENTATION,
                            label = getString(R.string.tun_implementation),
                            options = arrayOptions(R.array.tun_implementation, R.array.int_array_3),
                            selectedOptionId = DataStore.tunImplementation.toString(),
                        ),
                        YokhanFormField.Number(
                            id = Key.MTU,
                            label = getString(R.string.mtu),
                            value = DataStore.mtu.toString(),
                            helper = getString(R.string.mtu_help),
                        ),
                        YokhanFormField.Select(
                            id = Key.SPEED_INTERVAL,
                            label = getString(R.string.speed_interval),
                            options = arrayOptions(R.array.notification_entry, R.array.notification_value),
                            selectedOptionId = DataStore.speedInterval.toString(),
                        ),
                        YokhanFormField.Switch(
                            id = Key.PROFILE_TRAFFIC_STATISTICS,
                            label = getString(R.string.profile_traffic_statistics),
                            helper = getString(R.string.profile_traffic_statistics_summary),
                            checked = DataStore.profileTrafficStatistics,
                        ),
                        YokhanFormField.Switch(
                            id = Key.SHOW_DIRECT_SPEED,
                            label = getString(R.string.show_direct_speed),
                            helper = getString(R.string.show_direct_speed_sum),
                            checked = DataStore.showDirectSpeed,
                        ),
                        YokhanFormField.Switch(
                            id = FieldShowGroupInNotification,
                            label = getString(R.string.show_group_in_notification),
                            checked = DataStore.showGroupInNotification,
                        ),
                        YokhanFormField.Switch(
                            id = Key.ALWAYS_SHOW_ADDRESS,
                            label = getString(R.string.always_show_address),
                            helper = getString(R.string.always_show_address_sum),
                            checked = DataStore.alwaysShowAddress,
                        ),
                        YokhanFormField.Switch(
                            id = Key.METERED_NETWORK,
                            label = getString(R.string.metered),
                            helper = getString(R.string.metered_summary),
                            checked = DataStore.meteredNetwork,
                            visible = Build.VERSION.SDK_INT >= 28,
                        ),
                        YokhanFormField.Switch(
                            id = Key.ACQUIRE_WAKE_LOCK,
                            label = getString(R.string.acquire_wake_lock),
                            helper = getString(R.string.acquire_wake_lock_summary),
                            checked = DataStore.acquireWakeLock,
                        ),
                        YokhanFormField.Select(
                            id = Key.LOG_LEVEL,
                            label = getString(R.string.log_level),
                            helper = getString(R.string.log_level_help),
                            options = arrayOptions(R.array.log_level, R.array.int_array_5),
                            selectedOptionId = DataStore.logLevel.toString(),
                        ),
                        YokhanFormField.Action(
                            id = FieldLogBufferSize,
                            label = "Log buffer size",
                            value = "${effectiveLogBufferSize()} KB",
                            actionLabel = "EDIT",
                        ),
                        YokhanFormField.Action(
                            id = FieldGlobalConfig,
                            label = getString(R.string.custom_config),
                            value = configSummary(),
                            actionLabel = "EDIT",
                        ),
                    ),
                ),
                YokhanFormSection(
                    id = "route",
                    title = getString(R.string.cag_route).uppercase(),
                    fields = listOf(
                        YokhanFormField.Switch(
                            id = Key.PROXY_APPS,
                            label = getString(R.string.proxied_apps),
                            helper = getString(R.string.proxied_apps_summary),
                            checked = DataStore.proxyApps,
                        ),
                        YokhanFormField.Action(
                            id = FieldProxyAppsManager,
                            label = getString(R.string.settings_manage_apps),
                            value = getString(R.string.proxied_apps_summary),
                            actionLabel = "OPEN",
                        ),
                        YokhanFormField.Switch(
                            id = Key.STEALTH_MODE,
                            label = getString(R.string.stealth_mode),
                            helper = getString(R.string.stealth_mode_summary),
                            checked = DataStore.stealthMode,
                        ),
                        YokhanFormField.Switch(
                            id = Key.BYPASS_LAN,
                            label = getString(R.string.route_opt_bypass_lan),
                            checked = DataStore.bypassLan,
                        ),
                        YokhanFormField.Switch(
                            id = Key.BYPASS_LAN_IN_CORE,
                            label = getString(R.string.bypass_lan_in_core),
                            checked = DataStore.bypassLanInCore,
                        ),
                        YokhanFormField.Select(
                            id = Key.TRAFFIC_SNIFFING,
                            label = getString(R.string.traffic_sniffing),
                            options = arrayOptions(R.array.traffic_sniffing_values, R.array.int_array_3),
                            selectedOptionId = DataStore.trafficSniffing.toString(),
                        ),
                        YokhanFormField.Switch(
                            id = Key.RESOLVE_DESTINATION,
                            label = getString(R.string.resolve_destination),
                            helper = getString(R.string.resolve_destination_summary),
                            checked = DataStore.resolveDestination,
                        ),
                        YokhanFormField.Select(
                            id = Key.IPV6_MODE,
                            label = getString(R.string.ipv6),
                            options = arrayOptions(R.array.ipv6_mode, R.array.int_array_4),
                            selectedOptionId = DataStore.ipv6Mode.toString(),
                        ),
                        YokhanFormField.Select(
                            id = Key.RULES_PROVIDER,
                            label = getString(R.string.route_rules_provider),
                            options = arrayOptions(R.array.rules_dat_provider, R.array.int_array_4),
                            selectedOptionId = DataStore.rulesProvider.toString(),
                        ),
                    ),
                ),
                YokhanFormSection(
                    id = "dns",
                    title = getString(R.string.cag_dns).uppercase(),
                    fields = listOf(
                        YokhanFormField.Text(
                            id = Key.REMOTE_DNS,
                            label = getString(R.string.remote_dns),
                            value = DataStore.remoteDns,
                        ),
                        YokhanFormField.Select(
                            id = FieldDomainStrategyRemote,
                            label = getString(R.string.domain_strategy_for_remote),
                            options = arrayOptions(R.array.dns_network_select),
                            selectedOptionId = configString(FieldDomainStrategyRemote, "auto"),
                        ),
                        YokhanFormField.Text(
                            id = Key.DIRECT_DNS,
                            label = getString(R.string.direct_dns),
                            value = DataStore.directDns,
                        ),
                        YokhanFormField.Select(
                            id = FieldDomainStrategyDirect,
                            label = getString(R.string.domain_strategy_for_direct),
                            options = arrayOptions(R.array.dns_network_select),
                            selectedOptionId = configString(FieldDomainStrategyDirect, "auto"),
                        ),
                        YokhanFormField.Select(
                            id = FieldDomainStrategyServer,
                            label = getString(R.string.domain_strategy_for_server),
                            options = arrayOptions(R.array.dns_network_select),
                            selectedOptionId = configString(FieldDomainStrategyServer, "auto"),
                        ),
                        YokhanFormField.Switch(
                            id = Key.ENABLE_DNS_ROUTING,
                            label = getString(R.string.enable_dns_routing),
                            helper = getString(R.string.dns_routing_message),
                            checked = DataStore.enableDnsRouting,
                        ),
                        YokhanFormField.Switch(
                            id = Key.ENABLE_FAKEDNS,
                            label = getString(R.string.enable_fakedns),
                            helper = getString(R.string.fakedns_message),
                            checked = DataStore.enableFakeDns,
                        ),
                    ),
                ),
                YokhanFormSection(
                    id = "inbound",
                    title = getString(R.string.inbound_settings).uppercase(),
                    fields = listOf(
                        YokhanFormField.Switch(
                            id = Key.ENABLE_LOCAL_INBOUND,
                            label = getString(R.string.enable_local_inbound),
                            helper = getString(R.string.enable_local_inbound_sum),
                            checked = DataStore.enableLocalInbound,
                        ),
                        YokhanFormField.Port(
                            id = Key.MIXED_PORT,
                            label = getString(R.string.port_proxy),
                            value = DataStore.mixedPort.toString(),
                        ),
                        YokhanFormField.Switch(
                            id = Key.APPEND_HTTP_PROXY,
                            label = getString(R.string.append_http_proxy),
                            helper = getString(R.string.append_http_proxy_sum),
                            checked = DataStore.appendHttpProxy,
                        ),
                        YokhanFormField.Switch(
                            id = Key.ALLOW_ACCESS,
                            label = getString(R.string.allow_access),
                            helper = getString(R.string.allow_access_sum),
                            checked = DataStore.allowAccess,
                        ),
                    ),
                ),
                YokhanFormSection(
                    id = "misc",
                    title = getString(R.string.cag_misc).uppercase(),
                    fields = listOf(
                        YokhanFormField.Text(
                            id = Key.CONNECTION_TEST_URL,
                            label = getString(R.string.connection_test_url),
                            value = DataStore.connectionTestURL,
                        ),
                        YokhanFormField.Switch(
                            id = Key.ENABLE_CLASH_API,
                            label = getString(R.string.enable_clash_api),
                            helper = getString(R.string.enable_clash_api_summary),
                            checked = DataStore.enableClashAPI,
                        ),
                        YokhanFormField.Switch(
                            id = Key.NETWORK_CHANGE_RESET_CONNECTIONS,
                            label = getString(R.string.network_change_reset_connections),
                            checked = DataStore.networkChangeResetConnections,
                        ),
                        YokhanFormField.Switch(
                            id = Key.WAKE_RESET_CONNECTIONS,
                            label = getString(R.string.wake_reset_connections),
                            checked = DataStore.wakeResetConnections,
                        ),
                        YokhanFormField.Switch(
                            id = Key.GLOBAL_ALLOW_INSECURE,
                            label = getString(R.string.global_allow_insecure),
                            checked = DataStore.globalAllowInsecure,
                        ),
                        YokhanFormField.Switch(
                            id = Key.ALLOW_INSECURE_ON_REQUEST,
                            label = getString(R.string.allow_insecure_on_request_sum),
                            checked = DataStore.allowInsecureOnRequest,
                        ),
                        YokhanFormField.Select(
                            id = Key.APP_TLS_VERSION,
                            label = getString(R.string.app_tls_version),
                            options = arrayOptions(R.array.app_tls_version),
                            selectedOptionId = DataStore.appTLSVersion.ifBlank { "1.2" },
                        ),
                        YokhanFormField.Switch(
                            id = Key.SHOW_BOTTOM_BAR,
                            label = getString(R.string.show_bottom_bar),
                            checked = DataStore.showBottomBar,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun validateSettingsForm(state: YokhanFormState): YokhanFormState {
        val stealth = state.switchValue(Key.STEALTH_MODE)
        val localInbound = !stealth && state.switchValue(Key.ENABLE_LOCAL_INBOUND)
        val speedEnabled = state.selectValue(Key.SPEED_INTERVAL) != "0"
        return state.copy(
            sections = state.sections.map { section ->
                section.copy(
                    fields = section.fields.map { field ->
                        var updated = field
                        if (field.id == Key.METERED_NETWORK) {
                            updated = updated.withVisible(Build.VERSION.SDK_INT >= 28)
                        }
                        updated = when (updated) {
                            is YokhanFormField.Switch -> when (updated.id) {
                                Key.ENABLE_LOCAL_INBOUND -> updated.copy(
                                    checked = if (stealth) false else updated.checked,
                                    enabled = !stealth,
                                )

                                Key.APPEND_HTTP_PROXY,
                                Key.ALLOW_ACCESS,
                                -> updated.copy(
                                    checked = if (stealth) false else updated.checked,
                                    enabled = localInbound,
                                )

                                Key.ENABLE_CLASH_API -> updated.copy(
                                    checked = if (stealth) false else updated.checked,
                                    enabled = !stealth,
                                )

                                Key.PROFILE_TRAFFIC_STATISTICS -> updated.copy(enabled = speedEnabled)
                                else -> updated
                            }

                            is YokhanFormField.Port -> if (updated.id == Key.MIXED_PORT) {
                                val port = updated.value.toIntOrNull()
                                updated.copy(
                                    enabled = localInbound,
                                    validation = if (port == null || port !in 1..65535) {
                                        YokhanFormValidation(
                                            state = YokhanFormValidationState.Error,
                                            message = "Port must be 1-65535",
                                        )
                                    } else {
                                        YokhanFormValidation()
                                    },
                                )
                            } else {
                                updated
                            }

                            is YokhanFormField.Number -> if (updated.id == Key.MTU) {
                                val mtu = updated.value.toIntOrNull()
                                updated.copy(
                                    validation = if (mtu == null || mtu < 128) {
                                        YokhanFormValidation(
                                            state = YokhanFormValidationState.Error,
                                            message = "MTU must be 128 or higher",
                                        )
                                    } else {
                                        YokhanFormValidation()
                                    },
                                )
                            } else {
                                updated
                            }

                            else -> updated
                        }
                        updated
                    },
                )
            },
        )
    }

    private fun applySettings() {
        val state = formState.value?.let(::validateSettingsForm) ?: return
        formState.value = state
        if (state.hasBlockingValidationErrors()) return

        val before = SettingsSnapshot.capture()
        writeSettings(state)
        val after = SettingsSnapshot.capture()

        if (before.nightTheme != after.nightTheme) {
            Theme.currentNightMode = after.nightTheme
            Theme.applyNightTheme()
        }
        if (before.serviceMode != after.serviceMode && DataStore.serviceState.started) {
            SagerNet.stopServiceAsync()
        }
        if (before.logLevel != after.logLevel) {
            needRestart()
        }
        if (before.requiresReload(after)) {
            needReload()
        }
        (activity as? MainActivity)?.refreshNavMenu(DataStore.enableClashAPI && !DataStore.stealthMode)
        formState.value = validateSettingsForm(buildSettingsForm(dirty = false))
    }

    private fun writeSettings(state: YokhanFormState) {
        DataStore.configurationStore.putBoolean(Key.PERSIST_ACROSS_REBOOT, state.switchValue(Key.PERSIST_ACROSS_REBOOT))
        DataStore.nightTheme = state.selectValue(Key.NIGHT_THEME).toIntOrNull() ?: 0
        DataStore.serviceMode = state.selectValue(Key.SERVICE_MODE).ifBlank { Key.MODE_VPN }
        DataStore.tunImplementation = state.selectValue(Key.TUN_IMPLEMENTATION).toIntOrNull() ?: 0
        DataStore.mtu = state.textValue(Key.MTU).toIntOrNull() ?: 9000
        DataStore.speedInterval = state.selectValue(Key.SPEED_INTERVAL).toIntOrNull() ?: 1000
        DataStore.profileTrafficStatistics = state.switchValue(Key.PROFILE_TRAFFIC_STATISTICS)
        DataStore.showDirectSpeed = state.switchValue(Key.SHOW_DIRECT_SPEED)
        DataStore.showGroupInNotification = state.switchValue(FieldShowGroupInNotification)
        DataStore.alwaysShowAddress = state.switchValue(Key.ALWAYS_SHOW_ADDRESS)
        DataStore.meteredNetwork = state.switchValue(Key.METERED_NETWORK)
        DataStore.acquireWakeLock = state.switchValue(Key.ACQUIRE_WAKE_LOCK)
        DataStore.logLevel = state.selectValue(Key.LOG_LEVEL).toIntOrNull() ?: 0

        DataStore.proxyApps = state.switchValue(Key.PROXY_APPS)
        DataStore.stealthMode = state.switchValue(Key.STEALTH_MODE)
        DataStore.bypassLan = state.switchValue(Key.BYPASS_LAN)
        DataStore.bypassLanInCore = state.switchValue(Key.BYPASS_LAN_IN_CORE)
        DataStore.trafficSniffing = state.selectValue(Key.TRAFFIC_SNIFFING).toIntOrNull() ?: 1
        DataStore.resolveDestination = state.switchValue(Key.RESOLVE_DESTINATION)
        DataStore.ipv6Mode = state.selectValue(Key.IPV6_MODE).toIntOrNull() ?: 0
        DataStore.rulesProvider = state.selectValue(Key.RULES_PROVIDER).toIntOrNull() ?: 0

        DataStore.remoteDns = state.textValue(Key.REMOTE_DNS)
        DataStore.configurationStore.putString(FieldDomainStrategyRemote, state.selectValue(FieldDomainStrategyRemote).ifBlank { "auto" })
        DataStore.directDns = state.textValue(Key.DIRECT_DNS)
        DataStore.configurationStore.putString(FieldDomainStrategyDirect, state.selectValue(FieldDomainStrategyDirect).ifBlank { "auto" })
        DataStore.configurationStore.putString(FieldDomainStrategyServer, state.selectValue(FieldDomainStrategyServer).ifBlank { "auto" })
        DataStore.enableDnsRouting = state.switchValue(Key.ENABLE_DNS_ROUTING)
        DataStore.enableFakeDns = state.switchValue(Key.ENABLE_FAKEDNS)

        val stealth = DataStore.stealthMode
        DataStore.enableLocalInbound = !stealth && state.switchValue(Key.ENABLE_LOCAL_INBOUND)
        DataStore.mixedPort = state.textValue(Key.MIXED_PORT).toIntOrNull() ?: DataStore.mixedPort
        DataStore.appendHttpProxy = !stealth && state.switchValue(Key.APPEND_HTTP_PROXY)
        DataStore.allowAccess = !stealth && state.switchValue(Key.ALLOW_ACCESS)

        DataStore.connectionTestURL = state.textValue(Key.CONNECTION_TEST_URL)
        DataStore.enableClashAPI = !stealth && state.switchValue(Key.ENABLE_CLASH_API)
        DataStore.networkChangeResetConnections = state.switchValue(Key.NETWORK_CHANGE_RESET_CONNECTIONS)
        DataStore.wakeResetConnections = state.switchValue(Key.WAKE_RESET_CONNECTIONS)
        DataStore.globalAllowInsecure = state.switchValue(Key.GLOBAL_ALLOW_INSECURE)
        DataStore.allowInsecureOnRequest = state.switchValue(Key.ALLOW_INSECURE_ON_REQUEST)
        DataStore.appTLSVersion = state.selectValue(Key.APP_TLS_VERSION).ifBlank { "1.2" }
        DataStore.showBottomBar = state.switchValue(Key.SHOW_BOTTOM_BAR)
    }

    private fun openGlobalConfig() {
        configBeforeEditor = DataStore.globalCustomConfig
        editConfig.launch(
            Intent(requireActivity(), ConfigEditActivity::class.java).apply {
                putExtra("useConfigStore", "1")
                putExtra("key", Key.GLOBAL_CUSTOM_CONFIG)
            },
        )
    }

    private val editConfig = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (configBeforeEditor != DataStore.globalCustomConfig) {
            needReload()
            updateActionValue(FieldGlobalConfig, configSummary())
        }
    }

    private fun showLogBufferDialog() {
        val input = EditText(requireContext()).apply {
            inputType = EditorInfo.TYPE_CLASS_NUMBER
            setText(effectiveLogBufferSize().toString())
            selectAll()
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Log buffer size (KB)")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DataStore.logBufSize = input.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: 50
                needRestart()
                updateActionValue(FieldLogBufferSize, "${effectiveLogBufferSize()} KB")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateActionValue(id: String, value: String) {
        val state = formState.value ?: return
        formState.value = state.copy(
            sections = state.sections.map { section ->
                section.copy(
                    fields = section.fields.map { field ->
                        if (field is YokhanFormField.Action && field.id == id) {
                            field.copy(value = value)
                        } else {
                            field
                        }
                    },
                )
            },
        )
    }

    private fun configSummary(): String {
        val config = DataStore.globalCustomConfig
        return if (config.isBlank()) {
            getString(androidx.preference.R.string.not_set)
        } else {
            getString(R.string.lines, config.split('\n').size)
        }
    }

    private fun effectiveLogBufferSize(): Int {
        return DataStore.logBufSize.takeIf { it > 0 } ?: 50
    }

    private fun configString(key: String, default: String): String {
        return DataStore.configurationStore.getString(key) ?: default
    }

    private fun arrayOptions(entriesResId: Int, valuesResId: Int = entriesResId) =
        requireContext().arrayOptions(entriesResId, valuesResId)

    private fun ensureAdvancedDefaults() {
        if (DataStore.configurationStore.getString(Key.SPEED_INTERVAL) == null) {
            DataStore.speedInterval = 1000
        }
        if (DataStore.configurationStore.getString(Key.APP_TLS_VERSION) == null) {
            DataStore.appTLSVersion = "1.2"
        }
        if (DataStore.configurationStore.getString(FieldDomainStrategyRemote) == null) {
            DataStore.configurationStore.putString(FieldDomainStrategyRemote, "auto")
        }
        if (DataStore.configurationStore.getString(FieldDomainStrategyDirect) == null) {
            DataStore.configurationStore.putString(FieldDomainStrategyDirect, "auto")
        }
        if (DataStore.configurationStore.getString(FieldDomainStrategyServer) == null) {
            DataStore.configurationStore.putString(FieldDomainStrategyServer, "auto")
        }
    }

    private data class SettingsSnapshot(
        val nightTheme: Int,
        val serviceMode: String,
        val logLevel: Int,
        val reloadHash: Int,
    ) {
        fun requiresReload(other: SettingsSnapshot): Boolean = reloadHash != other.reloadHash

        companion object {
            fun capture(): SettingsSnapshot {
                return SettingsSnapshot(
                    nightTheme = DataStore.nightTheme,
                    serviceMode = DataStore.serviceMode,
                    logLevel = DataStore.logLevel,
                    reloadHash = listOf(
                        DataStore.mixedPort,
                        DataStore.appendHttpProxy,
                        DataStore.showDirectSpeed,
                        DataStore.trafficSniffing,
                        DataStore.bypassLan,
                        DataStore.bypassLanInCore,
                        DataStore.mtu,
                        DataStore.enableFakeDns,
                        DataStore.remoteDns,
                        DataStore.directDns,
                        DataStore.enableDnsRouting,
                        DataStore.ipv6Mode,
                        DataStore.allowAccess,
                        DataStore.resolveDestination,
                        DataStore.tunImplementation,
                        DataStore.acquireWakeLock,
                        DataStore.globalCustomConfig,
                        DataStore.stealthMode,
                        DataStore.enableLocalInbound,
                        DataStore.enableClashAPI,
                        DataStore.configurationStore.getString(FieldDomainStrategyRemote),
                        DataStore.configurationStore.getString(FieldDomainStrategyDirect),
                        DataStore.configurationStore.getString(FieldDomainStrategyServer),
                    ).hashCode(),
                )
            }
        }
    }

    private companion object {
        const val FieldShowGroupInNotification = "showGroupInNotification"
        const val FieldDomainStrategyRemote = "domain_strategy_for_remote"
        const val FieldDomainStrategyDirect = "domain_strategy_for_direct"
        const val FieldDomainStrategyServer = "domain_strategy_for_server"
        const val FieldProxyAppsManager = "proxyAppsManager"
        const val FieldGlobalConfig = Key.GLOBAL_CUSTOM_CONFIG
        const val FieldLogBufferSize = "logBufferSize"
    }
}
