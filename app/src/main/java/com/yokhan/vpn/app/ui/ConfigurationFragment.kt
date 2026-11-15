package com.yokhan.gates.app.ui

import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yokhan.gates.app.auth.YokhanBootstrapAccess
import com.yokhan.gates.app.auth.YokhanKeycloakAuth
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.plugin.PluginManager
import com.yokhan.gates.shared.ui.route.YokhanActionRow
import com.yokhan.gates.shared.ui.route.YokhanBody
import com.yokhan.gates.shared.ui.route.YokhanButtonGrid
import com.yokhan.gates.shared.ui.route.YokhanCaption
import com.yokhan.gates.shared.ui.route.YokhanDivider
import com.yokhan.gates.shared.ui.route.YokhanMonoValue
import com.yokhan.gates.shared.ui.route.YokhanRouteAction
import com.yokhan.gates.shared.ui.route.YokhanRouteButton
import com.yokhan.gates.shared.ui.route.YokhanRouteCard
import com.yokhan.gates.shared.ui.route.YokhanRouteLazyFrame
import com.yokhan.gates.shared.ui.route.YokhanRouteStatusDot
import com.yokhan.gates.shared.ui.route.YokhanSectionLabel
import com.yokhan.gates.app.ui.compose.YokhanActionSheetDialog
import com.yokhan.gates.app.ui.compose.YokhanActionSheetItem
import com.yokhan.gates.app.ui.compose.YokhanActionSheetState
import com.yokhan.gates.app.ui.compose.YokhanComposeRouteFragment
import com.yokhan.gates.shared.ui.route.yokhanOutlinedSurface
import com.yokhan.gates.app.ui.profile.ConfigSettingActivity
import com.yokhan.gates.app.ui.shell.YokhanShellActivityRoute
import io.nekohasekai.sagernet.widget.QRCodeDialog
import com.yokhan.gates.shared.ui.design.YokhanGreen
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanRadius
import com.yokhan.gates.shared.ui.design.YokhanRed
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import com.yokhan.gates.shared.ui.design.YokhanTypography
import com.yokhan.gates.shared.ui.design.yokhanPalette
import com.yokhan.gates.shared.ui.design.yokhanTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import okhttp3.internal.closeQuietly
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipInputStream

class ConfigurationFragment @JvmOverloads constructor(
    private val select: Boolean = false,
    private val selectedItem: ProxyEntity? = null,
    private val titleRes: Int = 0,
) : YokhanComposeRouteFragment(),
    ProfileManager.Listener,
    GroupManager.Listener,
    OnPreferenceDataStoreChangeListener {

    interface SelectCallback {
        fun returnProfile(profileId: Long)
    }

    private val uiState = mutableStateOf(ConfigurationUiState())
    private val actionSheetState = mutableStateOf<YokhanActionSheetState?>(null)
    private val profileAccess = Mutex()
    private val reloadAccess = Mutex()

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            if (file != null) runOnDefaultDispatcher {
                try {
                    val fileName = requireContext().contentResolver.query(file, null, null, null, null)
                        ?.use { cursor ->
                            cursor.moveToFirst()
                            cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                                .let(cursor::getString)
                        }
                    val proxies = mutableListOf<AbstractBean>()
                    if (fileName != null && fileName.endsWith(".zip", ignoreCase = true)) {
                        val zip = ZipInputStream(requireContext().contentResolver.openInputStream(file)!!)
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.isDirectory) continue
                            val fileText = zip.bufferedReader().readText()
                            RawUpdater.parseRaw(fileText, entry.name)?.let(proxies::addAll)
                            zip.closeEntry()
                        }
                        zip.closeQuietly()
                    } else {
                        val fileText = requireContext().contentResolver.openInputStream(file)!!.use {
                            it.bufferedReader().readText()
                        }
                        RawUpdater.parseRaw(fileText, fileName ?: "")?.let(proxies::addAll)
                    }
                    if (proxies.isEmpty()) {
                        onMainDispatcher {
                            snackbar(getString(R.string.no_proxies_found_in_file)).show()
                        }
                    } else {
                        import(proxies)
                    }
                } catch (e: SubscriptionFoundException) {
                    onMainDispatcher {
                        (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
                    }
                }
            }
        }

    private val exportConfig =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    try {
                        requireActivity().contentResolver.openOutputStream(data)!!
                            .bufferedWriter()
                            .use { it.write(DataStore.serverConfig) }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ProfileManager.addListener(this)
        GroupManager.addListener(this)
        DataStore.profileCacheStore.registerChangeListener(this)
        reloadData(preferredGroupId = selectedItem?.groupId)
    }

    override fun onResume() {
        super.onResume()
        reloadData(preferredGroupId = uiState.value.selectedGroupId.takeIf { it > 0L })
    }

    override fun onDestroyView() {
        ProfileManager.removeListener(this)
        GroupManager.removeListener(this)
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroyView()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (store !== DataStore.profileCacheStore) return
        if (key == Key.PROFILE_GROUP) {
            val targetId = DataStore.editingGroup
            if (targetId > 0L) {
                reloadData(preferredGroupId = targetId)
            }
        }
    }

    @Composable
    override fun Content(spaceActive: Boolean) {
        val state = uiState.value
        val signedIn by YokhanKeycloakAuth.sessionAvailable.collectAsState()
        val authBusy by YokhanKeycloakAuth.busy.collectAsState()
        var toolsExpanded by remember { mutableStateOf(false) }
        val title = if (select && titleRes != 0) {
            stringResource(titleRes)
        } else {
            stringResource(R.string.menu_configuration)
        }
        val visibleProfiles = state.visibleProfiles()
        val currentGroup = state.currentGroup()

        YokhanRouteLazyFrame(
            title = title,
            systemLabel = stringResource(R.string.system_route_label, "02"),
            spaceActive = spaceActive,
            onMenuClick = {
                if (select) {
                    activity?.finish()
                } else {
                    (activity as? MainActivity)?.openNavigationMenu()
                }
            },
        ) { colors, typography ->
            if (!select) {
                item(key = "quick-access") {
                    ConfigurationQuickAccessCard(
                        signedIn = signedIn,
                        authBusy = authBusy,
                        colors = colors,
                        typography = typography,
                    )
                }
            }

            val showManageCard = state.groups.size > 1 ||
                state.profiles.isNotEmpty() ||
                state.query.isNotEmpty()
            if (showManageCard) {
                item(key = "groups") {
                    ConfigurationGroupsCard(
                        state = state,
                        showToolsToggle = !select,
                        toolsExpanded = toolsExpanded,
                        onToggleTools = { toolsExpanded = !toolsExpanded },
                        colors = colors,
                        typography = typography,
                        onQueryChange = { uiState.value = uiState.value.copy(query = it) },
                        onGroupSelected = ::selectGroup,
                    )
                }
            }

            if (!select && showManageCard) {
                item(key = "tools-panel") {
                    AnimatedVisibility(visible = toolsExpanded) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ConfigurationActionsCard(
                                group = currentGroup,
                                colors = colors,
                                typography = typography,
                            )
                            ConfigurationOrderCard(
                                group = currentGroup,
                                colors = colors,
                                typography = typography,
                            )
                        }
                    }
                }
            }

            if (visibleProfiles.isEmpty()) {
                item(key = "empty") {
                    YokhanRouteCard(
                        title = stringResource(R.string.dashboard_configurations),
                        colors = colors,
                        typography = typography,
                    ) {
                        YokhanBody(
                            text = stringResource(R.string.dashboard_no_profile),
                            colors = colors,
                            typography = typography,
                        )
                        if (!select) {
                            YokhanCaption(
                                text = stringResource(R.string.config_empty_hint),
                                colors = colors,
                                typography = typography,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            } else {
                items(
                    items = visibleProfiles,
                    key = { it.id },
                ) { profile ->
                    ProfileCard(
                        profile = profile,
                        group = currentGroup,
                        selected = (selectedItem?.id ?: state.selectedProxyId) == profile.id,
                        colors = colors,
                        typography = typography,
                    )
                }
            }
        }
        YokhanActionSheetDialog(
            state = actionSheetState.value,
            colors = yokhanPalette(spaceActive),
            typography = yokhanTypography(),
            onDismiss = { actionSheetState.value = null },
        )
    }

    @Composable
    private fun ConfigurationQuickAccessCard(
        signedIn: Boolean,
        authBusy: Boolean,
        colors: YokhanPalette,
        typography: YokhanTypography,
    ) {
        YokhanRouteCard(
            title = stringResource(R.string.configuration_hub_import_title),
            colors = colors,
            typography = typography,
        ) {
            YokhanBody(
                text = stringResource(R.string.config_sync_subtitle),
                colors = colors,
                typography = typography,
                maxLines = 2,
            )
            if (YokhanKeycloakAuth.isConfigured()) {
                YokhanRouteButton(
                    text = stringResource(
                        when {
                            authBusy -> R.string.yokhan_auth_connecting
                            signedIn -> R.string.yokhan_auth_refresh_action
                            else -> R.string.yokhan_auth_login_action
                        },
                    ),
                    primary = true,
                    enabled = !authBusy,
                    colors = colors,
                    typography = typography,
                    onClick = { (activity as? MainActivity)?.startYokhanLogin() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = YokhanSpacing.CardTitleGap),
                )
                if (signedIn) {
                    YokhanRouteButton(
                        text = stringResource(R.string.yokhan_auth_restart_action),
                        primary = false,
                        enabled = !authBusy,
                        colors = colors,
                        typography = typography,
                        onClick = { (activity as? MainActivity)?.startYokhanForceLogin() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = YokhanSpacing.CompactGap),
                    )
                    YokhanRouteButton(
                        text = stringResource(R.string.yokhan_auth_sign_out_action),
                        primary = false,
                        enabled = !authBusy,
                        colors = colors,
                        typography = typography,
                        onClick = { (activity as? MainActivity)?.signOutYokhan() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = YokhanSpacing.CompactGap),
                    )
                }
                YokhanDivider(colors, Modifier.padding(vertical = 10.dp))
            }
            if (BuildConfig.YOKHAN_APP_API_BASE_URL.isNotBlank()) {
                WiseTreeBootstrapSection(
                    authBusy = authBusy,
                    colors = colors,
                    typography = typography,
                )
                YokhanDivider(colors, Modifier.padding(vertical = 10.dp))
            }
        YokhanActionRow(
                title = stringResource(R.string.config_add_other_title),
                subtitle = stringResource(R.string.config_add_other_subtitle),
                colors = colors,
                typography = typography,
                onClick = ::showAddProfileMethodsDialog,
                modifier = if (YokhanKeycloakAuth.isConfigured() || BuildConfig.YOKHAN_APP_API_BASE_URL.isNotBlank()) Modifier else Modifier.padding(top = YokhanSpacing.CardTitleGap),
            )
        }
    }

    @Composable
    private fun WiseTreeBootstrapSection(
        authBusy: Boolean,
        colors: YokhanPalette,
        typography: YokhanTypography,
    ) {
        val scope = rememberCoroutineScope()
        var expanded by rememberSaveable { mutableStateOf(false) }
        var code by rememberSaveable { mutableStateOf("") }
        var secretVisible by rememberSaveable { mutableStateOf(false) }
        var bootstrapBusy by remember { mutableStateOf(false) }
        var statusMessage by rememberSaveable { mutableStateOf("") }
        var bootstrapTick by remember { mutableIntStateOf(0) }
        val remainingMinutes = remember(bootstrapTick) {
            activity?.let { YokhanBootstrapAccess.activeRemainingMinutes(it) }
        }
        if (remainingMinutes != null) {
            YokhanCaption(
                text = stringResource(R.string.wise_tree_active_badge, remainingMinutes),
                colors = colors,
                typography = typography,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        YokhanRouteButton(
            text = stringResource(R.string.wise_tree_trigger),
            primary = false,
            enabled = !authBusy && !bootstrapBusy,
            colors = colors,
            typography = typography,
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
        )
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                YokhanBody(
                    text = stringResource(R.string.wise_tree_title),
                    colors = colors,
                    typography = typography,
                )
                YokhanBody(
                    text = stringResource(R.string.wise_tree_hint),
                    colors = colors,
                    typography = typography,
                )
                YokhanCaption(
                    text = stringResource(R.string.wise_tree_scope_note),
                    colors = colors,
                    typography = typography,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    YokhanCaption(
                        text = stringResource(R.string.wise_tree_code_hint),
                        colors = colors,
                        typography = typography,
                    )
                    YokhanRouteButton(
                        text = if (secretVisible) "Скрыть" else "Показать",
                        primary = false,
                        enabled = !bootstrapBusy,
                        colors = colors,
                        typography = typography,
                        onClick = { secretVisible = !secretVisible },
                    )
                }
                BasicTextField(
                    value = code,
                    onValueChange = { code = it },
                    enabled = !bootstrapBusy,
                    singleLine = true,
                    visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    cursorBrush = SolidColor(colors.accent),
                    textStyle = TextStyle(
                        color = colors.primary,
                        fontFamily = typography.mono,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    ),
                    decorationBox = { innerTextField ->
                        Box(Modifier.fillMaxWidth()) {
                            if (code.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.wise_tree_code_hint),
                                    color = colors.tertiary,
                                    fontFamily = typography.mono,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                )
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .yokhanOutlinedSurface(colors)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
                if (statusMessage.isNotBlank()) {
                    YokhanCaption(
                        text = statusMessage,
                        colors = colors,
                        typography = typography,
                    )
                }
                YokhanRouteButton(
                    text = if (bootstrapBusy) stringResource(R.string.wise_tree_checking) else stringResource(R.string.wise_tree_submit),
                    primary = true,
                    enabled = !authBusy && !bootstrapBusy && code.isNotBlank(),
                    colors = colors,
                    typography = typography,
                    onClick = {
                        val hostActivity = activity ?: return@YokhanRouteButton
                        val trimmed = code.trim()
                        if (trimmed.isBlank()) {
                            statusMessage = hostActivity.getString(R.string.wise_tree_empty_code)
                            return@YokhanRouteButton
                        }
                        bootstrapBusy = true
                        statusMessage = hostActivity.getString(R.string.wise_tree_checking)
                        scope.launch {
                            val result = YokhanBootstrapAccess.claim(
                                context = hostActivity.applicationContext,
                                code = trimmed,
                                ensureEmbeddedTunnelConnected = {
                                    (hostActivity as? MainActivity)?.ensureBootstrapTunnelConnected() ?: false
                                },
                                stopEmbeddedTunnel = {
                                    SagerNet.stopServiceAsync()
                                },
                            )
                            bootstrapBusy = false
                            bootstrapTick += 1
                            result.onSuccess {
                                code = ""
                                expanded = false
                                statusMessage = ""
                                (hostActivity as? MainActivity)?.onYokhanBootstrapComplete()
                            }.onFailure { error ->
                                statusMessage = error.message ?: hostActivity.getString(R.string.yokhan_auth_failed)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    private fun showAddProfileMethodsDialog() {
        actionSheetState.value = YokhanActionSheetState(
            title = getString(R.string.config_add_other_title),
            items = listOf(
                YokhanActionSheetItem(
                    title = getString(R.string.add_profile_methods_scan_qr_code),
                    onClick = { (activity as? MainActivity)?.openActivityRoute(YokhanShellActivityRoute.Scanner) },
                ),
                YokhanActionSheetItem(
                    title = getString(R.string.action_import),
                    onClick = ::importFromClipboard,
                ),
                YokhanActionSheetItem(
                    title = getString(R.string.action_import_file),
                    onClick = { startFilesForResult(importFile, "*/*") },
                ),
                YokhanActionSheetItem(
                    title = getString(R.string.add_profile_methods_manual_settings),
                    onClick = {
                        ProfileAddController.showManualProfileChooser(this) { protocol ->
                            (activity as? MainActivity)?.openActivityRoute(
                                YokhanShellActivityRoute.AddManualProfile(protocol),
                            )
                        }
                    },
                ),
            ),
        )
    }

    @Composable
    private fun ConfigurationGroupsCard(
        state: ConfigurationUiState,
        showToolsToggle: Boolean,
        toolsExpanded: Boolean,
        onToggleTools: () -> Unit,
        colors: YokhanPalette,
        typography: YokhanTypography,
        onQueryChange: (String) -> Unit,
        onGroupSelected: (Long) -> Unit,
    ) {
        YokhanRouteCard(
            title = stringResource(R.string.configuration_hub_manage_title),
            colors = colors,
            typography = typography,
        ) {
            if (state.profiles.size >= 4 || state.query.isNotEmpty()) {
                SearchField(
                    value = state.query,
                    placeholder = stringResource(R.string.config_search_hint),
                    colors = colors,
                    typography = typography,
                    onValueChange = onQueryChange,
                )
            }
            if (state.groups.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = if (state.profiles.size >= 4 || state.query.isNotEmpty()) 12.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.groups.forEach { group ->
                        GroupChip(
                            text = if (group.ungrouped) {
                                stringResource(R.string.group_ungrouped_title)
                            } else {
                                group.displayName()
                            },
                            selected = group.id == state.selectedGroupId,
                            colors = colors,
                            typography = typography,
                            onClick = { onGroupSelected(group.id) },
                        )
                    }
                }
            }
            val group = state.currentGroup()
            if (group != null || showToolsToggle) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (group != null) {
                        Column(Modifier.fillMaxWidth()) {
                            YokhanMonoValue(
                                text = if (group.ungrouped) {
                                    stringResource(R.string.group_ungrouped_title)
                                } else {
                                    group.displayName()
                                },
                                colors = colors,
                                typography = typography,
                                sizeSp = 14,
                            )
                            YokhanCaption(
                                text = if (group.type == GroupType.SUBSCRIPTION) {
                                    stringResource(R.string.configuration_hub_group_type_subscription)
                                } else {
                                    stringResource(R.string.configuration_hub_group_type_local)
                                },
                                colors = colors,
                                typography = typography,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    if (showToolsToggle) {
                        GroupChip(
                            text = stringResource(
                                if (toolsExpanded) R.string.config_tools_hide else R.string.config_tools_show,
                            ),
                            selected = toolsExpanded,
                            colors = colors,
                            typography = typography,
                            onClick = onToggleTools,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ConfigurationActionsCard(
        group: ProxyGroup?,
        colors: YokhanPalette,
        typography: YokhanTypography,
    ) {
        YokhanRouteCard(
            title = stringResource(R.string.connection_test),
            colors = colors,
            typography = typography,
        ) {
            YokhanActionRow(
                title = stringResource(R.string.connection_test_url_test),
                colors = colors,
                typography = typography,
                onClick = { runConnectionTest(urlMode = true) },
                accent = true,
            )
            YokhanDivider(colors, Modifier.padding(vertical = 4.dp))
            YokhanActionRow(
                title = stringResource(R.string.connection_test_tcp_ping),
                colors = colors,
                typography = typography,
                onClick = { runConnectionTest(urlMode = false) },
            )
            if (group?.type == GroupType.SUBSCRIPTION) {
                YokhanDivider(colors, Modifier.padding(vertical = 4.dp))
                YokhanActionRow(
                    title = stringResource(R.string.update_current_subscription),
                    colors = colors,
                    typography = typography,
                    onClick = ::updateCurrentSubscription,
                )
            }
            YokhanDivider(colors, Modifier.padding(vertical = 4.dp))
            YokhanActionRow(
                title = stringResource(R.string.remove_duplicate),
                colors = colors,
                typography = typography,
                onClick = ::removeDuplicateProfiles,
            )
            YokhanDivider(colors, Modifier.padding(vertical = 4.dp))
            YokhanActionRow(
                title = stringResource(R.string.connection_test_delete_unavailable),
                colors = colors,
                typography = typography,
                onClick = ::deleteUnavailableProfiles,
            )
            YokhanDivider(colors, Modifier.padding(vertical = 4.dp))
            YokhanActionRow(
                title = stringResource(R.string.connection_test_clear_results),
                colors = colors,
                typography = typography,
                onClick = ::clearConnectionTestResults,
            )
            YokhanDivider(colors, Modifier.padding(vertical = 4.dp))
            YokhanActionRow(
                title = stringResource(R.string.clear_traffic_statistics),
                colors = colors,
                typography = typography,
                onClick = ::clearTrafficStatistics,
            )
        }
    }

    @Composable
    private fun ConfigurationOrderCard(
        group: ProxyGroup?,
        colors: YokhanPalette,
        typography: YokhanTypography,
    ) {
        if (group == null) return
        YokhanRouteCard(
            title = stringResource(R.string.group_order),
            colors = colors,
            typography = typography,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OrderButton(
                    text = stringResource(R.string.group_order_origin),
                    selected = group.order == GroupOrder.ORIGIN,
                    colors = colors,
                    typography = typography,
                    onClick = { updateGroupOrder(GroupOrder.ORIGIN) },
                    modifier = Modifier.weight(1f),
                )
                OrderButton(
                    text = stringResource(R.string.group_order_by_name),
                    selected = group.order == GroupOrder.BY_NAME,
                    colors = colors,
                    typography = typography,
                    onClick = { updateGroupOrder(GroupOrder.BY_NAME) },
                    modifier = Modifier.weight(1f),
                )
                OrderButton(
                    text = stringResource(R.string.group_order_by_delay),
                    selected = group.order == GroupOrder.BY_DELAY,
                    colors = colors,
                    typography = typography,
                    onClick = { updateGroupOrder(GroupOrder.BY_DELAY) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    @Composable
    private fun OrderButton(
        text: String,
        selected: Boolean,
        colors: YokhanPalette,
        typography: YokhanTypography,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        YokhanRouteButton(
            text = text,
            primary = selected,
            enabled = true,
            colors = colors,
            typography = typography,
            onClick = onClick,
            modifier = modifier,
        )
    }

    @Composable
    private fun ProfileCard(
        profile: ProxyEntity,
        group: ProxyGroup?,
        selected: Boolean,
        colors: YokhanPalette,
        typography: YokhanTypography,
    ) {
        val context = requireContext()
        val showTraffic = profile.rx + profile.tx != 0L
        val traffic = if (showTraffic) {
            stringResource(
                R.string.traffic,
                Formatter.formatFileSize(context, profile.tx),
                Formatter.formatFileSize(context, profile.rx),
            )
        } else {
            ""
        }
        var address = profile.displayAddress()
        if (showTraffic && address.length >= 30) address = address.take(27) + "..."
        if (profile.requireBean().name.isBlank() || !DataStore.alwaysShowAddress) {
            address = ""
        }
        val statusText = profileStatusText(profile, traffic)
        val statusColor = when (profile.status) {
            1 -> YokhanGreen
            2, 3 -> YokhanRed
            else -> colors.secondary
        }
        val canEdit = !select &&
            !(selected && DataStore.serviceState.started && DataStore.currentProfile == profile.id)
        val canShare = !select && profile.type != ProxyEntity.TYPE_CHAIN && profile.nekoBean == null

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .yokhanOutlinedSurface(colors)
                .clickable { onProfileSelected(profile) },
        ) {
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(if (selected) colors.accent else Color.Transparent),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = profile.displayName(),
                                color = colors.primary,
                                fontFamily = typography.text,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                modifier = Modifier.padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = profile.displayType(),
                                    color = Color(context.getProtocolColor(profile.type)),
                                    fontFamily = typography.mono,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                )
                                if (statusText.isNotBlank()) {
                                    Text(
                                        text = statusText,
                                        color = statusColor,
                                        fontFamily = typography.mono,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = if (profile.status == 3) {
                                            Modifier.clickable {
                                                alert(profile.error ?: "<?>").tryToShow()
                                            }
                                        } else {
                                            Modifier
                                        },
                                    )
                                }
                            }
                        }
                        if (selected) {
                            YokhanRouteStatusDot(
                                active = true,
                                colors = colors,
                                modifier = Modifier
                                    .padding(top = 3.dp),
                            )
                        }
                    }
                    if (address.isNotBlank()) {
                        YokhanCaption(
                            text = address,
                            colors = colors,
                            typography = typography,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (!select) {
                        YokhanDivider(colors, Modifier.padding(vertical = 8.dp))
                        YokhanActionRow(
                            title = stringResource(R.string.profile_actions_title),
                            colors = colors,
                            typography = typography,
                            onClick = {
                                showProfileActionsDialog(
                                    profile = profile,
                                    group = group,
                                    selected = selected,
                                    canEdit = canEdit,
                                    canShare = canShare,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    private fun showProfileActionsDialog(
        profile: ProxyEntity,
        group: ProxyGroup?,
        selected: Boolean,
        canEdit: Boolean,
        canShare: Boolean,
    ) {
        val entries = mutableListOf<YokhanActionSheetItem>()
        if (!selected) {
            entries += YokhanActionSheetItem(
                title = getString(R.string.profile_select_action),
                accent = true,
                onClick = { onProfileSelected(profile) },
            )
        }
        if (canEdit) {
            entries += YokhanActionSheetItem(
                title = getString(R.string.edit),
                onClick = { editProfile(profile, group) },
            )
        }
        if (canShare) {
            entries += YokhanActionSheetItem(
                title = getString(R.string.share),
                onClick = { showShareDialog(profile) },
            )
        }
        if (canEdit) {
            entries += YokhanActionSheetItem(
                title = getString(R.string.delete),
                onClick = { confirmDeleteProfile(profile) },
            )
        }
        if (entries.isEmpty()) return
        actionSheetState.value = YokhanActionSheetState(
            title = profile.displayName(),
            items = entries,
        )
    }

    @Composable
    private fun SearchField(
        value: String,
        placeholder: String,
        colors: YokhanPalette,
        typography: YokhanTypography,
        onValueChange: (String) -> Unit,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = colors.primary,
                fontFamily = typography.mono,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = YokhanSpacing.ControlMinHeight)
                        .yokhanOutlinedSurface(colors, radius = YokhanRadius.Pill)
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isBlank()) {
                            Text(
                                text = placeholder,
                                color = colors.secondary,
                                fontFamily = typography.mono,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        inner()
                    }
                    if (value.isNotEmpty()) {
                        Text(
                            text = "×",
                            color = colors.secondary,
                            fontFamily = typography.mono,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { onValueChange("") }
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
            },
        )
    }

    @Composable
    private fun GroupChip(
        text: String,
        selected: Boolean,
        colors: YokhanPalette,
        typography: YokhanTypography,
        onClick: () -> Unit,
    ) {
        Box(
            modifier = Modifier
                .yokhanOutlinedSurface(
                    colors = colors,
                    radius = YokhanRadius.Pill,
                    borderColor = if (selected) colors.accent.copy(alpha = 0.7f) else colors.border,
                    backgroundColor = if (selected) colors.accent.copy(alpha = 0.22f) else Color.Transparent,
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = text,
                color = if (selected) colors.primary else colors.secondary,
                fontFamily = typography.mono,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }

    private fun reloadData(preferredGroupId: Long? = null) {
        runOnDefaultDispatcher {
            var groups = ArrayList(SagerDatabase.groupDao.allGroups())
            if (groups.isEmpty()) {
                SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
                groups = ArrayList(SagerDatabase.groupDao.allGroups())
            }
            groups.find { it.ungrouped }?.let {
                if (SagerDatabase.proxyDao.countByGroup(it.id) == 0L && groups.size > 1) {
                    groups.remove(it)
                }
            }
            DataStore.cacheGroups(groups)

            val selectedGroupId = resolveSelectedGroupId(groups, preferredGroupId)
            if (selectedGroupId > 0L) DataStore.selectedGroup = selectedGroupId
            val group = groups.firstOrNull { it.id == selectedGroupId }
            val profiles = group?.let { loadProfiles(it) }.orEmpty()

            onMainDispatcher {
                uiState.value = uiState.value.copy(
                    groups = groups,
                    selectedGroupId = selectedGroupId,
                    profiles = profiles,
                    selectedProxyId = selectedItem?.id ?: DataStore.selectedProxy,
                    loading = false,
                )
            }
        }
    }

    private fun resolveSelectedGroupId(groups: List<ProxyGroup>, preferredGroupId: Long?): Long {
        val preferred = listOfNotNull(
            preferredGroupId?.takeIf { it > 0L },
            selectedItem?.groupId?.takeIf { it > 0L },
            DataStore.currentGroupId().takeIf { it > 0L },
            groups.firstOrNull()?.id,
        )
        return preferred.firstOrNull { id -> groups.any { it.id == id } } ?: 0L
    }

    private fun loadProfiles(group: ProxyGroup): List<ProxyEntity> {
        var profiles = SagerDatabase.proxyDao.getByGroup(group.id)
        profiles = when (group.order) {
            GroupOrder.BY_NAME -> profiles.sortedBy { it.displayName() }
            GroupOrder.BY_DELAY -> profiles.sortedBy { if (it.status == 1) it.ping else 114514 }
            else -> profiles
        }
        return profiles
    }

    private fun selectGroup(groupId: Long) {
        uiState.value = uiState.value.copy(selectedGroupId = groupId, profiles = emptyList())
        reloadData(preferredGroupId = groupId)
    }

    private fun onProfileSelected(profile: ProxyEntity) {
        if (select) {
            (requireActivity() as SelectCallback).returnProfile(profile.id)
            return
        }
        runOnDefaultDispatcher {
            profileAccess.withLock {
                val previous = DataStore.selectedProxy
                val changed = previous != profile.id
                DataStore.selectedProxy = profile.id
                DataStore.currentProfile = profile.id
                onMainDispatcher {
                    uiState.value = uiState.value.copy(selectedProxyId = profile.id)
                }
                if (changed) {
                    ProfileManager.postUpdate(previous)
                    ProfileManager.postUpdate(profile.id)
                    if (DataStore.serviceState.canStop && reloadAccess.tryLock()) {
                        try {
                            SagerNet.reloadService()
                        } finally {
                            reloadAccess.unlock()
                        }
                    }
                } else if (SagerNet.isTv) {
                    if (DataStore.serviceState.started) {
                        SagerNet.stopService()
                    } else {
                        SagerNet.startService()
                    }
                }
            }
        }
    }

    private fun editProfile(profile: ProxyEntity, group: ProxyGroup?) {
        (activity as? MainActivity)?.openActivityRoute(
            YokhanShellActivityRoute.EditProfile(
                profile = profile,
                isSubscription = group?.type == GroupType.SUBSCRIPTION,
            ),
        )
    }

    private fun confirmDeleteProfile(profile: ProxyEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm)
            .setMessage(profile.displayName())
            .setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    ProfileManager.deleteProfile(profile.groupId, profile.id)
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun showShareDialog(profile: ProxyEntity) {
        val actions = buildList {
            if (profile.haveStandardLink()) {
                add(getString(R.string.standard) + " QR" to { showProfileQr(profile.toStdLink(), profile) })
                add(getString(R.string.standard) + " " + getString(R.string.action_export_clipboard) to {
                    exportToClipboard(profile.toStdLink())
                })
            }
            if (profile.haveLink()) {
                add("SN Link QR" to { showProfileQr(profile.requireBean().toUniversalLink(), profile) })
                add("SN Link " + getString(R.string.action_export_clipboard) to {
                    exportToClipboard(profile.requireBean().toUniversalLink())
                })
            }
            if (profile.nekoBean == null) {
                add(getString(R.string.menu_configuration) + " " + getString(R.string.action_export_clipboard) to {
                    exportToClipboard(profile.exportConfig().first)
                })
                add(getString(R.string.menu_configuration) + " " + getString(R.string.action_export_file) to {
                    val cfg = profile.exportConfig()
                    DataStore.serverConfig = cfg.first
                    startFilesForResult(exportConfig, cfg.second)
                })
            }
        }
        if (actions.isEmpty()) {
            snackbar(R.string.action_export_err).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(profile.displayName())
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .show()
    }

    private fun showProfileQr(link: String, profile: ProxyEntity) {
        QRCodeDialog(link, profile.displayName()).showAllowingStateLoss(parentFragmentManager)
    }

    private fun exportToClipboard(link: String) {
        val success = SagerNet.trySetPrimaryClip(link)
        snackbar(if (success) R.string.action_export_msg else R.string.action_export_err).show()
    }

    private fun importFromClipboard() {
        val text = SagerNet.getClipboardText()
        if (text.isBlank()) {
            snackbar(getString(R.string.clipboard_empty)).show()
            return
        }
        runOnDefaultDispatcher {
            try {
                val proxies = RawUpdater.parseRaw(text)
                if (proxies.isNullOrEmpty()) {
                    onMainDispatcher {
                        snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                    }
                } else {
                    import(proxies)
                }
            } catch (e: SubscriptionFoundException) {
                onMainDispatcher {
                    (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                }
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    snackbar(e.readableMessage).show()
                }
            }
        }
    }

    private suspend fun import(proxies: List<AbstractBean>) {
        val targetId = DataStore.ensureSelectedGroupForImport()
        var firstCreatedId = 0L
        for (proxy in proxies) {
            val created = ProfileManager.createProfile(targetId, proxy)
            if (firstCreatedId == 0L) {
                firstCreatedId = created.id
            }
        }
        if (DataStore.selectedProxy == 0L && firstCreatedId != 0L) {
            DataStore.selectedProxy = firstCreatedId
        }
        onMainDispatcher {
            DataStore.editingGroup = targetId
            reloadData(preferredGroupId = targetId)
            snackbar(
                resources.getQuantityString(R.plurals.added, proxies.size, proxies.size)
            ).show()
        }
    }

    private fun updateCurrentSubscription() {
        val groupId = uiState.value.selectedGroupId
        runOnDefaultDispatcher {
            val group = SagerDatabase.groupDao.getById(groupId) ?: return@runOnDefaultDispatcher
            if (group.type != GroupType.SUBSCRIPTION) {
                onMainDispatcher {
                    snackbar(R.string.group_not_subscription).show()
                }
            } else {
                GroupUpdater.startUpdate(group, true)
            }
        }
    }

    private fun updateGroupOrder(order: Int) {
        val group = uiState.value.currentGroup() ?: return
        if (group.order == order) return
        runOnDefaultDispatcher {
            group.order = order
            GroupManager.updateGroup(group)
            reloadData(preferredGroupId = group.id)
        }
    }

    private fun clearTrafficStatistics() {
        val groupId = uiState.value.selectedGroupId
        runOnDefaultDispatcher {
            val profiles = SagerDatabase.proxyDao.getByGroup(groupId)
                .filter { it.tx != 0L || it.rx != 0L }
                .map { it.copy(tx = 0L, rx = 0L) }
            if (profiles.isNotEmpty()) ProfileManager.updateProfile(profiles)
        }
    }

    private fun clearConnectionTestResults() {
        val groupId = uiState.value.selectedGroupId
        runOnDefaultDispatcher {
            val profiles = SagerDatabase.proxyDao.getByGroup(groupId)
                .filter { it.status != 0 }
                .map { it.copy(status = 0, ping = 0, error = null) }
            if (profiles.isNotEmpty()) ProfileManager.updateProfile(profiles)
        }
    }

    private fun deleteUnavailableProfiles() {
        val groupId = uiState.value.selectedGroupId
        runOnDefaultDispatcher {
            val unavailable = SagerDatabase.proxyDao.getByGroup(groupId)
                .filter { it.status != 0 && it.status != 1 }
            if (unavailable.isEmpty()) return@runOnDefaultDispatcher
            onMainDispatcher {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.confirm)
                    .setMessage(
                        getString(R.string.delete_confirm_prompt) + "\n" +
                            unavailable.take(20).joinToString("\n") { it.displayName() }
                    )
                    .setPositiveButton(R.string.yes) { _, _ ->
                        runOnDefaultDispatcher {
                            unavailable.forEach { ProfileManager.deleteProfile(it.groupId, it.id) }
                        }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }
        }
    }

    private fun removeDuplicateProfiles() {
        val groupId = uiState.value.selectedGroupId
        runOnDefaultDispatcher {
            val profiles = SagerDatabase.proxyDao.getByGroup(groupId)
            val duplicate = mutableListOf<ProxyEntity>()
            val unique = LinkedHashSet<Protocols.Deduplication>()
            profiles.forEach { profile ->
                val key = Protocols.Deduplication(profile.requireBean(), profile.displayType())
                if (!unique.add(key)) duplicate += profile
            }
            if (duplicate.isEmpty()) return@runOnDefaultDispatcher
            onMainDispatcher {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.confirm)
                    .setMessage(
                        getString(R.string.delete_confirm_prompt) + "\n" +
                            duplicate.take(20).joinToString("\n") { it.displayName() }
                    )
                    .setPositiveButton(R.string.yes) { _, _ ->
                        runOnDefaultDispatcher {
                            duplicate.forEach { ProfileManager.deleteProfile(it.groupId, it.id) }
                        }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }
        }
    }

    private fun runConnectionTest(urlMode: Boolean) {
        if (DataStore.runningTest) return
        DataStore.runningTest = true
        snackbar(R.string.connection_test_testing).show()
        val groupId = uiState.value.selectedGroupId

        runOnDefaultDispatcher {
            try {
                val profiles = ConcurrentLinkedQueue(SagerDatabase.proxyDao.getByGroup(groupId))
                val jobs = mutableListOf<Job>()
                repeat(DataStore.connectionTestConcurrent.coerceAtLeast(1)) {
                    jobs.add(launch(Dispatchers.IO) {
                        val tester = if (urlMode) UrlTest() else null
                        while (isActive) {
                            val profile = profiles.poll() ?: break
                            val tested = if (urlMode) {
                                testUrlProfile(profile, tester!!)
                            } else {
                                testTcpProfile(profile)
                            }
                            ProfileManager.updateProfile(tested)
                        }
                    })
                }
                jobs.joinAll()
                onMainDispatcher {
                    snackbar(R.string.connection_test).show()
                }
            } finally {
                DataStore.runningTest = false
            }
        }
    }

    private suspend fun testUrlProfile(profile: ProxyEntity, tester: UrlTest): ProxyEntity {
        return try {
            profile.copy(status = 1, ping = tester.doTest(profile), error = null)
        } catch (e: PluginManager.PluginNotFoundException) {
            profile.copy(status = 2, error = e.readableMessage)
        } catch (e: Exception) {
            profile.copy(status = 3, error = e.readableMessage)
        }
    }

    private fun testTcpProfile(profile: ProxyEntity): ProxyEntity {
        var address = profile.requireBean().serverAddress
        if (!address.isIpAddress()) {
            try {
                val resolved = SagerNet.underlyingNetwork?.getAllByName(address).orEmpty()
                if (resolved.isNotEmpty()) address = resolved[0].hostAddress
            } catch (_: UnknownHostException) {
            }
        }
        if (!address.isIpAddress()) {
            return profile.copy(
                status = 2,
                error = getString(R.string.connection_test_domain_not_found),
            )
        }

        return try {
            val socket = SagerNet.underlyingNetwork?.socketFactory?.createSocket() ?: Socket()
            try {
                socket.soTimeout = 3000
                socket.bind(InetSocketAddress(0))
                val start = SystemClock.elapsedRealtime()
                socket.connect(InetSocketAddress(address, profile.requireBean().serverPort), 3000)
                profile.copy(
                    status = 1,
                    ping = (SystemClock.elapsedRealtime() - start).toInt(),
                    error = null,
                )
            } finally {
                socket.closeQuietly()
            }
        } catch (e: Exception) {
            val message = e.readableMessage
            when {
                !message.contains("failed:") -> profile.copy(
                    status = 2,
                    error = getString(R.string.connection_test_timeout),
                )
                message.contains("ECONNREFUSED") -> profile.copy(
                    status = 2,
                    error = getString(R.string.connection_test_refused),
                )
                message.contains("ENETUNREACH") -> profile.copy(
                    status = 2,
                    error = getString(R.string.connection_test_unreachable),
                )
                else -> profile.copy(status = 3, error = message)
            }
        }
    }

    private fun profileStatusText(profile: ProxyEntity, traffic: String): String {
        return when {
            profile.status <= 0 -> traffic
            profile.status == 1 -> getString(R.string.available, profile.ping)
            profile.status == 2 -> profile.error.orEmpty()
            profile.status == 3 -> {
                val err = profile.error ?: "<?>"
                val msg = Protocols.genFriendlyMsg(err)
                if (msg != err) msg else getString(R.string.unavailable)
            }
            else -> ""
        }
    }

    private fun updateProfileInState(profile: ProxyEntity) {
        val state = uiState.value
        if (profile.groupId != state.selectedGroupId) return
        uiState.value = state.copy(
            profiles = state.profiles.map { if (it.id == profile.id) profile else it },
            selectedProxyId = selectedItem?.id ?: DataStore.selectedProxy,
        )
    }

    override suspend fun onAdd(profile: ProxyEntity) {
        onMainDispatcher {
            if (profile.groupId == uiState.value.selectedGroupId) {
                reloadData(preferredGroupId = profile.groupId)
            } else {
                reloadData(preferredGroupId = uiState.value.selectedGroupId)
            }
        }
    }

    override suspend fun onUpdated(data: TrafficData) {
        onMainDispatcher {
            val state = uiState.value
            uiState.value = state.copy(
                profiles = state.profiles.map {
                    if (it.id == data.id) it.copy(tx = data.tx, rx = data.rx) else it
                },
            )
        }
    }

    override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
        onMainDispatcher {
            updateProfileInState(profile)
        }
    }

    override suspend fun onRemoved(groupId: Long, profileId: Long) {
        onMainDispatcher {
            val state = uiState.value
            if (groupId != state.selectedGroupId) return@onMainDispatcher
            uiState.value = state.copy(
                profiles = state.profiles.filterNot { it.id == profileId },
                selectedProxyId = selectedItem?.id ?: DataStore.selectedProxy,
            )
        }
    }

    override suspend fun groupAdd(group: ProxyGroup) {
        onMainDispatcher { reloadData(preferredGroupId = group.id) }
    }

    override suspend fun groupUpdated(group: ProxyGroup) {
        onMainDispatcher { reloadData(preferredGroupId = group.id) }
    }

    override suspend fun groupRemoved(groupId: Long) {
        onMainDispatcher { reloadData() }
    }

    override suspend fun groupUpdated(groupId: Long) {
        onMainDispatcher { reloadData(preferredGroupId = groupId) }
    }

    private data class ConfigurationUiState(
        val groups: List<ProxyGroup> = emptyList(),
        val selectedGroupId: Long = 0L,
        val profiles: List<ProxyEntity> = emptyList(),
        val selectedProxyId: Long = 0L,
        val query: String = "",
        val loading: Boolean = true,
    ) {
        fun currentGroup(): ProxyGroup? = groups.firstOrNull { it.id == selectedGroupId }

        fun visibleProfiles(): List<ProxyEntity> {
            val normalized = query.trim().lowercase()
            if (normalized.isEmpty()) return profiles
            return profiles.filter {
                it.displayName().lowercase().contains(normalized) ||
                    it.displayType().lowercase().contains(normalized) ||
                    it.displayAddress().lowercase().contains(normalized)
            }
        }
    }
}
