package com.yokhan.gates.app.ui

import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import com.yokhan.gates.app.auth.YokhanKeycloakAuth
import com.yokhan.gates.app.roomnet.RoomNetClient
import com.yokhan.gates.app.roomnet.RoomNetState
import com.yokhan.gates.shared.ui.route.YokhanButtonGrid
import com.yokhan.gates.shared.ui.route.YokhanCaption
import com.yokhan.gates.app.ui.compose.YokhanComposeRouteFragment
import com.yokhan.gates.shared.ui.route.YokhanDivider
import com.yokhan.gates.shared.ui.route.YokhanHeadline
import com.yokhan.gates.shared.ui.route.YokhanMonoValue
import com.yokhan.gates.shared.ui.route.YokhanRouteAction
import com.yokhan.gates.shared.ui.route.YokhanRouteCard
import com.yokhan.gates.shared.ui.route.YokhanRouteFrame
import com.yokhan.gates.shared.ui.route.YokhanStatPair
import com.yokhan.gates.app.ui.shell.YokhanManualProfileProtocol
import com.yokhan.gates.app.ui.shell.YokhanShellActivityRoute
import com.yokhan.gates.app.ui.shell.YokhanShellRoute
import com.yokhan.gates.app.ui.shell.YokhanShellSubRoute
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

class ConfigurationHubFragment : YokhanComposeRouteFragment(),
    OnPreferenceDataStoreChangeListener {

    private val summaryState = mutableStateOf<HubSummary?>(null)

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            if (file == null) return@registerForActivityResult
            runOnDefaultDispatcher {
                try {
                    val fileName = requireContext().contentResolver.query(
                        file, null, null, null, null
                    )?.use { cursor ->
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
                        zip.close()
                    } else {
                        val fileText =
                            requireContext().contentResolver.openInputStream(file)!!.use {
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        DataStore.configurationStore.registerChangeListener(this)
        refreshSummary()
    }

    override fun onResume() {
        super.onResume()
        refreshSummary()
    }

    override fun onDestroyView() {
        DataStore.configurationStore.unregisterChangeListener(this)
        summaryState.value = null
        super.onDestroyView()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (store !== DataStore.configurationStore) return
        val currentView = view ?: return
        when (key) {
            Key.PROFILE_ID, Key.PROFILE_GROUP, Key.SERVICE_MODE -> currentView.post {
                if (view === currentView) refreshSummary()
            }
        }
    }

    @Composable
    override fun Content(spaceActive: Boolean) {
        val summary = summaryState.value
        val roomStatus by RoomNetClient.status.collectAsState()
        val signedIn by YokhanKeycloakAuth.sessionAvailable.collectAsState()
        val authBusy by YokhanKeycloakAuth.busy.collectAsState()
        YokhanRouteFrame(
            title = stringResource(R.string.configuration_hub_title),
            systemLabel = stringResource(R.string.dashboard_system_label),
            spaceActive = spaceActive,
            onMenuClick = { (activity as? MainActivity)?.openNavigationMenu() },
        ) { colors, typography ->
            YokhanRouteCard(
                title = stringResource(R.string.configuration_hub_manage_title),
                colors = colors,
                typography = typography,
            ) {
                YokhanHeadline(
                    text = summary?.profileName ?: stringResource(R.string.dashboard_no_profile),
                    colors = colors,
                    typography = typography,
                )
                YokhanCaption(
                    text = stringResource(R.string.configuration_hub_current_profile),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 4.dp),
                )
                YokhanDivider(colors, Modifier.padding(vertical = YokhanSpacing.CompactDividerGap))
                YokhanMonoValue(
                    text = summary?.groupName ?: "--",
                    colors = colors,
                    typography = typography,
                    sizeSp = 15,
                )
                YokhanCaption(
                    text = summary?.groupTypeLabel() ?: stringResource(R.string.configuration_hub_group_type),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 4.dp),
                )
                YokhanStatPair(
                    firstValue = summary?.let {
                        stringResource(
                            R.string.configuration_hub_profile_count_value,
                            it.groupProfiles,
                            it.totalProfiles,
                        )
                    } ?: "--",
                    firstLabel = stringResource(R.string.configuration_hub_profile_count),
                    secondValue = summary?.let {
                        pluralStringResource(
                            R.plurals.configuration_hub_group_count,
                            it.groupCount,
                            it.groupCount,
                        )
                    } ?: "--",
                    secondLabel = stringResource(R.string.configuration_hub_group_count_label),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = YokhanSpacing.ActionTopGap),
                )
                YokhanButtonGrid(
                    actions = listOf(
                        YokhanRouteAction(
                            title = stringResource(R.string.configuration_hub_manage_profiles),
                            primary = true,
                            onClick = { (activity as? MainActivity)?.openSubRoute(YokhanShellSubRoute.Profiles) },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.configuration_hub_update_subscription),
                            enabled = summary?.groupType == GroupType.SUBSCRIPTION,
                            onClick = ::updateCurrentSubscription,
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.configuration_hub_manage_groups),
                            onClick = { (activity as? MainActivity)?.displayRoute(YokhanShellRoute.Group) },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.configuration_hub_manage_routes),
                            onClick = { (activity as? MainActivity)?.displayRoute(YokhanShellRoute.Route) },
                        ),
                    ),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = YokhanSpacing.ActionTopGap),
                )
            }

            if (roomStatus.room.isNotBlank() || roomStatus.state == RoomNetState.ERROR) {
                YokhanHeadline(
                    text = if (roomStatus.room.isBlank()) stringResource(R.string.gates_room_title)
                    else stringResource(R.string.gates_room_named_title, roomStatus.room),
                    colors = colors,
                    typography = typography,
                )
                YokhanCaption(
                    text = stringResource(when (roomStatus.state) {
                        RoomNetState.FETCHING, RoomNetState.CONNECTING -> R.string.gates_room_connecting
                        RoomNetState.CONNECTED -> R.string.gates_room_connected
                        RoomNetState.ERROR -> R.string.gates_room_unavailable
                        else -> R.string.gates_room_disconnected
                    }),
                    colors = colors,
                    typography = typography,
                )
                SelectionContainer {
                    roomStatus.self?.let { self ->
                        YokhanMonoValue(
                            text = stringResource(R.string.gates_room_self_address, self.address),
                            colors = colors,
                            typography = typography,
                        )
                    }
                }
                roomStatus.peers.forEach { peer ->
                    YokhanCaption(
                        text = stringResource(
                            R.string.gates_room_peer_label,
                            peer.username,
                            peer.device,
                            stringResource(if (peer.id in roomStatus.connectedPeerIds) R.string.gates_room_peer_online else R.string.gates_room_peer_offline),
                        ),
                        colors = colors,
                        typography = typography,
                    )
                    SelectionContainer {
                        YokhanMonoValue(text = peer.address, colors = colors, typography = typography)
                    }
                }
                YokhanDivider(colors)
            }

            YokhanRouteCard(
                title = stringResource(R.string.configuration_hub_import_title),
                colors = colors,
                typography = typography,
            ) {
                YokhanButtonGrid(
                    actions = listOf(
                        YokhanRouteAction(
                            title = stringResource(if (signedIn) R.string.yokhan_auth_refresh_action else R.string.yokhan_auth_login_action),
                            primary = true,
                            enabled = YokhanKeycloakAuth.isConfigured() && !authBusy,
                            onClick = { (activity as? MainActivity)?.startYokhanLogin() },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.add_profile_methods_scan_qr_code),
                            onClick = {
                                (activity as? MainActivity)?.openActivityRoute(
                                    YokhanShellActivityRoute.Scanner,
                                )
                            },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.action_import),
                            onClick = ::importFromClipboard,
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.action_import_file),
                            onClick = { startFilesForResult(importFile, "*/*") },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.configuration_hub_open_profiles),
                            onClick = { (activity as? MainActivity)?.openSubRoute(YokhanShellSubRoute.Profiles) },
                        ),
                    ),
                    colors = colors,
                    typography = typography,
                )
            }

            YokhanRouteCard(
                title = stringResource(R.string.configuration_hub_manual_title),
                colors = colors,
                typography = typography,
            ) {
                YokhanButtonGrid(
                    actions = listOf(
                        YokhanRouteAction(
                            title = stringResource(R.string.action_shadowsocks),
                            onClick = {
                                (activity as? MainActivity)?.openActivityRoute(
                                    YokhanShellActivityRoute.AddManualProfile(
                                        YokhanManualProfileProtocol.Shadowsocks,
                                    ),
                                )
                            },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.action_vmess),
                            onClick = {
                                (activity as? MainActivity)?.openActivityRoute(
                                    YokhanShellActivityRoute.AddManualProfile(
                                        YokhanManualProfileProtocol.VMess,
                                    ),
                                )
                            },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.action_wireguard),
                            onClick = {
                                (activity as? MainActivity)?.openActivityRoute(
                                    YokhanShellActivityRoute.AddManualProfile(
                                        YokhanManualProfileProtocol.WireGuard,
                                    ),
                                )
                            },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.configuration_hub_open_all_protocols),
                            onClick = {
                                ProfileAddController.showManualProfileChooser(
                                    this@ConfigurationHubFragment,
                                ) { protocol ->
                                    (activity as? MainActivity)?.openActivityRoute(
                                        YokhanShellActivityRoute.AddManualProfile(protocol),
                                    )
                                }
                            },
                        ),
                    ),
                    colors = colors,
                    typography = typography,
                )
            }
        }
    }

    private fun refreshSummary() {
        val owner = viewLifecycleOwnerLiveData.value ?: return
        owner.lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                val groups = DataStore.ensureGroupsLoaded()
                val currentGroup = DataStore.currentGroup()
                val currentProfile = ProfileManager.getProfile(DataStore.selectedProxy)
                val currentGroupCount = SagerDatabase.proxyDao.countByGroup(currentGroup.id)
                val totalProfiles = groups.sumOf { SagerDatabase.proxyDao.countByGroup(it.id) }
                HubSummary(
                    groupName = currentGroup.displayName(),
                    groupType = currentGroup.type,
                    profileName = currentProfile?.displayName(),
                    groupProfiles = currentGroupCount.toInt(),
                    totalProfiles = totalProfiles.toInt(),
                    groupCount = groups.size
                )
            }
            summaryState.value = summary
        }
    }

    private fun updateCurrentSubscription() {
        runOnDefaultDispatcher {
            val group = DataStore.ensureCurrentGroup()
            if (group.type != GroupType.SUBSCRIPTION) {
                onMainDispatcher {
                    snackbar(R.string.group_not_subscription).show()
                }
            } else {
                GroupUpdater.startUpdate(group, true)
            }
        }
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
        for (proxy in proxies) {
            ProfileManager.createProfile(targetId, proxy)
        }
        onMainDispatcher {
            DataStore.editingGroup = targetId
            refreshSummary()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.action_import_msg)
                .setMessage(
                    resources.getQuantityString(
                        R.plurals.configuration_hub_imported_profiles,
                        proxies.size,
                        proxies.size
                    )
                )
                .setPositiveButton(R.string.configuration_hub_open_profiles) { _, _ ->
                    (activity as? MainActivity)?.openSubRoute(YokhanShellSubRoute.Profiles)
                }
                .setNegativeButton(android.R.string.ok, null)
                .show()
        }
    }

    private data class HubSummary(
        val groupName: String,
        val groupType: Int,
        val profileName: String?,
        val groupProfiles: Int,
        val totalProfiles: Int,
        val groupCount: Int,
    )

    @Composable
    private fun HubSummary.groupTypeLabel(): String {
        return stringResource(
            when (groupType) {
                GroupType.SUBSCRIPTION -> R.string.configuration_hub_group_type_subscription
                else -> R.string.configuration_hub_group_type_local
            }
        )
    }
}
