package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutGroupHubBinding
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroupHubFragment : ToolbarFragment(R.layout.layout_group_hub) {

    private var _binding: LayoutGroupHubBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = LayoutGroupHubBinding.bind(view)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.group_hub_title)

        binding.openGroupListButton.setOnClickListener {
            (activity as? MainActivity)?.openSubFragment(GroupFragment())
        }
        binding.newGroupButton.setOnClickListener {
            startActivity(Intent(requireActivity(), GroupSettingsActivity::class.java))
        }
        binding.updateSubscriptionsButton.setOnClickListener {
            runOnDefaultDispatcher {
                val groups = SagerDatabase.groupDao.allGroups()
                    .filter { it.type == GroupType.SUBSCRIPTION }
                if (groups.isEmpty()) {
                    onMainDispatcher {
                        snackbar(R.string.group_hub_no_subscription_groups).show()
                    }
                } else {
                    groups.forEach { GroupUpdater.startUpdate(it, true) }
                }
            }
        }
        binding.openProfilesButton.setOnClickListener {
            (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_configuration)
        }

        refreshSummary()
    }

    override fun onResume() {
        super.onResume()
        refreshSummary()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun refreshSummary() {
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                val groups = DataStore.ensureGroupsLoaded()
                val subscriptions = groups.count { it.type == GroupType.SUBSCRIPTION }
                val localGroups = groups.count { it.type != GroupType.SUBSCRIPTION }
                val activeGroup = DataStore.currentGroup()
                val totalProfiles = groups.sumOf { SagerDatabase.proxyDao.countByGroup(it.id) }
                GroupSummary(
                    activeGroupName = activeGroup.displayName(),
                    activeGroupType = activeGroup.type,
                    groupCount = groups.size,
                    subscriptionCount = subscriptions,
                    localCount = localGroups,
                    totalProfiles = totalProfiles.toInt()
                )
            }

            val binding = _binding ?: return@launch
            binding.activeGroupValue.text = summary.activeGroupName
            binding.activeGroupTypeValue.text = getString(
                if (summary.activeGroupType == GroupType.SUBSCRIPTION) {
                    R.string.configuration_hub_group_type_subscription
                } else {
                    R.string.configuration_hub_group_type_local
                }
            )
            binding.groupTotalsValue.text = resources.getQuantityString(
                R.plurals.group_hub_group_totals,
                summary.groupCount,
                summary.groupCount,
                summary.localCount,
                summary.subscriptionCount
            )
            binding.profileTotalsValue.text = resources.getQuantityString(
                R.plurals.group_hub_profile_totals,
                summary.totalProfiles,
                summary.totalProfiles
            )
            binding.updateSubscriptionsButton.isEnabled = summary.subscriptionCount > 0
        }
    }

    private data class GroupSummary(
        val activeGroupName: String,
        val activeGroupType: Int,
        val groupCount: Int,
        val subscriptionCount: Int,
        val localCount: Int,
        val totalProfiles: Int,
    )
}
