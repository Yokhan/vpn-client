package com.yokhan.gates.app.ui

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.snackbar
import com.yokhan.gates.shared.ui.route.YokhanButtonGrid
import com.yokhan.gates.shared.ui.route.YokhanCaption
import com.yokhan.gates.app.ui.compose.YokhanComposeRouteFragment
import com.yokhan.gates.shared.ui.route.YokhanHeadline
import com.yokhan.gates.shared.ui.route.YokhanRouteAction
import com.yokhan.gates.shared.ui.route.YokhanRouteCard
import com.yokhan.gates.shared.ui.route.YokhanRouteFrame
import com.yokhan.gates.shared.ui.route.YokhanStatPair
import com.yokhan.gates.app.ui.shell.YokhanShellActivityRoute
import com.yokhan.gates.app.ui.shell.YokhanShellRoute
import com.yokhan.gates.app.ui.shell.YokhanShellSubRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroupHubFragment : YokhanComposeRouteFragment() {

    private val summaryState = mutableStateOf<GroupSummary?>(null)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refreshSummary()
    }

    override fun onResume() {
        super.onResume()
        refreshSummary()
    }

    override fun onDestroyView() {
        summaryState.value = null
        super.onDestroyView()
    }

    @Composable
    override fun Content(spaceActive: Boolean) {
        val summary = summaryState.value
        YokhanRouteFrame(
            title = stringResource(R.string.group_hub_title),
            systemLabel = stringResource(R.string.dashboard_system_label),
            spaceActive = spaceActive,
            onMenuClick = { (activity as? MainActivity)?.openNavigationMenu() },
        ) { colors, typography ->
            YokhanRouteCard(
                title = stringResource(R.string.group_hub_overview_title),
                colors = colors,
                typography = typography,
            ) {
                YokhanHeadline(
                    text = summary?.activeGroupName ?: "--",
                    colors = colors,
                    typography = typography,
                )
                YokhanCaption(
                    text = summary?.activeGroupTypeLabel() ?: stringResource(R.string.configuration_hub_group_type),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 4.dp),
                )
                YokhanStatPair(
                    firstValue = summary?.let {
                        pluralStringResource(
                            R.plurals.group_hub_group_totals,
                            it.groupCount,
                            it.groupCount,
                            it.localCount,
                            it.subscriptionCount,
                        )
                    } ?: "--",
                    firstLabel = stringResource(R.string.group_hub_totals_title),
                    secondValue = summary?.let {
                        pluralStringResource(
                            R.plurals.group_hub_profile_totals,
                            it.totalProfiles,
                            it.totalProfiles,
                        )
                    } ?: "--",
                    secondLabel = stringResource(R.string.group_hub_profile_inventory),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            YokhanRouteCard(
                title = stringResource(R.string.group_hub_actions_title),
                colors = colors,
                typography = typography,
            ) {
                YokhanButtonGrid(
                    actions = listOf(
                        YokhanRouteAction(
                            title = stringResource(R.string.group_hub_open_list),
                            primary = true,
                            onClick = { (activity as? MainActivity)?.openSubRoute(YokhanShellSubRoute.Groups) },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.group_create),
                            onClick = {
                                (activity as? MainActivity)?.openActivityRoute(
                                    YokhanShellActivityRoute.GroupCreate,
                                )
                            },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.group_hub_update_all),
                            enabled = (summary?.subscriptionCount ?: 0) > 0,
                            onClick = ::updateSubscriptions,
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.group_hub_open_profiles),
                            onClick = { (activity as? MainActivity)?.displayRoute(YokhanShellRoute.Configuration) },
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
            summaryState.value = summary
        }
    }

    private fun updateSubscriptions() {
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

    private data class GroupSummary(
        val activeGroupName: String,
        val activeGroupType: Int,
        val groupCount: Int,
        val subscriptionCount: Int,
        val localCount: Int,
        val totalProfiles: Int,
    )

    @Composable
    private fun GroupSummary.activeGroupTypeLabel(): String {
        return stringResource(
            if (activeGroupType == GroupType.SUBSCRIPTION) {
                R.string.configuration_hub_group_type_subscription
            } else {
                R.string.configuration_hub_group_type_local
            }
        )
    }
}
