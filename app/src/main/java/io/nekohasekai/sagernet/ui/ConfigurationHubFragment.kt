package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
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
import io.nekohasekai.sagernet.databinding.LayoutConfigurationHubBinding
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
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import io.nekohasekai.sagernet.ui.profile.WireGuardSettingsActivity
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

class ConfigurationHubFragment : ToolbarFragment(R.layout.layout_configuration_hub),
    OnPreferenceDataStoreChangeListener {

    private var _binding: LayoutConfigurationHubBinding? = null
    private val binding get() = _binding!!

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
        _binding = LayoutConfigurationHubBinding.bind(view)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.configuration_hub_title)

        binding.manageProfilesButton.setOnClickListener {
            (activity as? MainActivity)?.openSubFragment(ConfigurationFragment())
        }
        binding.manageGroupsButton.setOnClickListener {
            (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_group)
        }
        binding.manageRoutesButton.setOnClickListener {
            (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_route)
        }
        binding.updateSubscriptionButton.setOnClickListener {
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

        binding.scanQrButton.setOnClickListener {
            startActivity(Intent(requireActivity(), ScannerActivity::class.java))
        }
        binding.importClipboardButton.setOnClickListener {
            importFromClipboard()
        }
        binding.importFileButton.setOnClickListener {
            startFilesForResult(importFile, "*/*")
        }
        binding.openFullProfilesButton.setOnClickListener {
            (activity as? MainActivity)?.openSubFragment(ConfigurationFragment())
        }

        binding.manualShadowsocksButton.setOnClickListener {
            startActivity(Intent(requireActivity(), ShadowsocksSettingsActivity::class.java))
        }
        binding.manualVmessButton.setOnClickListener {
            startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java))
        }
        binding.manualWireguardButton.setOnClickListener {
            startActivity(Intent(requireActivity(), WireGuardSettingsActivity::class.java))
        }
        binding.manualConfigButton.setOnClickListener {
            ProfileAddController.showManualProfileChooser(this)
        }

        DataStore.configurationStore.registerChangeListener(this)
        refreshSummary()
    }

    override fun onResume() {
        super.onResume()
        refreshSummary()
    }

    override fun onDestroyView() {
        DataStore.configurationStore.unregisterChangeListener(this)
        _binding = null
        super.onDestroyView()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (store !== DataStore.configurationStore) return
        when (key) {
            Key.PROFILE_ID, Key.PROFILE_GROUP, Key.SERVICE_MODE -> view?.post { refreshSummary() }
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

            val binding = _binding ?: return@launch
            binding.currentProfileValue.text =
                summary.profileName ?: getString(R.string.dashboard_no_profile)
            binding.currentGroupValue.text = summary.groupName
            binding.profileCountValue.text = getString(
                R.string.configuration_hub_profile_count_value,
                summary.groupProfiles,
                summary.totalProfiles
            )
            binding.groupCountValue.text = resources.getQuantityString(
                R.plurals.configuration_hub_group_count,
                summary.groupCount,
                summary.groupCount
            )
            binding.groupTypeValue.text = getString(
                when (summary.groupType) {
                    GroupType.SUBSCRIPTION -> R.string.configuration_hub_group_type_subscription
                    else -> R.string.configuration_hub_group_type_local
                }
            )
            binding.updateSubscriptionButton.isEnabled = summary.groupType == GroupType.SUBSCRIPTION
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
}
