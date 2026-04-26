package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import io.nekohasekai.sagernet.ui.compose.YokhanButtonGrid
import io.nekohasekai.sagernet.ui.compose.YokhanCaption
import io.nekohasekai.sagernet.ui.compose.YokhanComposeRouteFragment
import io.nekohasekai.sagernet.ui.compose.YokhanDivider
import io.nekohasekai.sagernet.ui.compose.YokhanHeadline
import io.nekohasekai.sagernet.ui.compose.YokhanMonoValue
import io.nekohasekai.sagernet.ui.compose.YokhanRouteAction
import io.nekohasekai.sagernet.ui.compose.YokhanRouteCard
import io.nekohasekai.sagernet.ui.compose.YokhanRouteFrame
import io.nekohasekai.sagernet.ui.compose.YokhanStatPair
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import io.nekohasekai.sagernet.ui.profile.WireGuardSettingsActivity
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
        when (key) {
            Key.PROFILE_ID, Key.PROFILE_GROUP, Key.SERVICE_MODE -> view?.post { refreshSummary() }
        }
    }

    @Composable
    override fun Content(spaceActive: Boolean) {
        val summary = summaryState.value
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
                YokhanDivider(colors, Modifier.padding(vertical = 12.dp))
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
                    modifier = Modifier.padding(top = 14.dp),
                )
                YokhanButtonGrid(
                    actions = listOf(
                        YokhanRouteAction(
                            title = stringResource(R.string.configuration_hub_manage_profiles),
                            primary = true,
                            onClick = { (activity as? MainActivity)?.openSubFragment(ConfigurationFragment()) },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.configuration_hub_update_subscription),
                            enabled = summary?.groupType == GroupType.SUBSCRIPTION,
                            onClick = ::updateCurrentSubscription,
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.configuration_hub_manage_groups),
                            onClick = { (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_group) },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.configuration_hub_manage_routes),
                            onClick = { (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_route) },
                        ),
                    ),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }

            YokhanRouteCard(
                title = stringResource(R.string.configuration_hub_import_title),
                colors = colors,
                typography = typography,
            ) {
                YokhanButtonGrid(
                    actions = listOf(
                        YokhanRouteAction(
                            title = stringResource(R.string.add_profile_methods_scan_qr_code),
                            onClick = { startActivity(Intent(requireActivity(), ScannerActivity::class.java)) },
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
                            onClick = { (activity as? MainActivity)?.openSubFragment(ConfigurationFragment()) },
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
                            onClick = { startActivity(Intent(requireActivity(), ShadowsocksSettingsActivity::class.java)) },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.action_vmess),
                            onClick = { startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java)) },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.action_wireguard),
                            onClick = { startActivity(Intent(requireActivity(), WireGuardSettingsActivity::class.java)) },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.configuration_hub_open_all_protocols),
                            onClick = { ProfileAddController.showManualProfileChooser(this@ConfigurationHubFragment) },
                        ),
                    ),
                    colors = colors,
                    typography = typography,
                )
            }
        }
    }

    private fun refreshSummary() {
        val owner = viewLifecycleOwner
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
                    (activity as? MainActivity)?.openSubFragment(ConfigurationFragment())
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
