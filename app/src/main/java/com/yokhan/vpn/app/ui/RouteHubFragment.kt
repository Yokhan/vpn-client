package com.yokhan.vpn.app.ui

import android.content.Intent
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import com.yokhan.vpn.app.ui.compose.YokhanButtonGrid
import com.yokhan.vpn.app.ui.compose.YokhanComposeRouteFragment
import com.yokhan.vpn.app.ui.compose.YokhanRouteAction
import com.yokhan.vpn.app.ui.compose.YokhanRouteCard
import com.yokhan.vpn.app.ui.compose.YokhanRouteFrame
import com.yokhan.vpn.app.ui.compose.YokhanStatPair
import com.yokhan.vpn.app.ui.shell.YokhanShellSubRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RouteHubFragment : YokhanComposeRouteFragment() {

    private val summaryState = mutableStateOf<RouteSummary?>(null)

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
            title = stringResource(R.string.route_hub_title),
            systemLabel = stringResource(R.string.dashboard_system_label),
            spaceActive = spaceActive,
            onMenuClick = { (activity as? MainActivity)?.openNavigationMenu() },
        ) { colors, typography ->
            YokhanRouteCard(
                title = stringResource(R.string.route_hub_overview_title),
                colors = colors,
                typography = typography,
            ) {
                YokhanStatPair(
                    firstValue = summary?.let {
                        pluralStringResource(
                            R.plurals.route_hub_rule_totals,
                            it.totalRules,
                            it.totalRules,
                            it.enabledRules,
                        )
                    } ?: "--",
                    firstLabel = stringResource(R.string.route_hub_rule_totals_title),
                    secondValue = summary?.let {
                        pluralStringResource(
                            R.plurals.route_hub_asset_totals,
                            it.assetCount,
                            it.assetCount,
                        )
                    } ?: "--",
                    secondLabel = stringResource(R.string.route_hub_asset_totals_title),
                    colors = colors,
                    typography = typography,
                )
            }

            YokhanRouteCard(
                title = stringResource(R.string.route_hub_actions_title),
                colors = colors,
                typography = typography,
            ) {
                YokhanButtonGrid(
                    actions = listOf(
                        YokhanRouteAction(
                            title = stringResource(R.string.route_hub_open_list),
                            primary = true,
                            onClick = { (activity as? MainActivity)?.openSubRoute(YokhanShellSubRoute.Routes) },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.route_add),
                            onClick = { startActivity(Intent(requireActivity(), RouteSettingsActivity::class.java)) },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.route_manage_assets),
                            onClick = { startActivity(Intent(requireActivity(), AssetsActivity::class.java)) },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.route_reset),
                            onClick = ::confirmResetRoutes,
                        ),
                    ),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }

    private fun refreshSummary() {
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                val rules = ProfileManager.getRules()
                val enabledRules = rules.count { it.enabled }
                RouteSummary(
                    totalRules = rules.size,
                    enabledRules = enabledRules,
                    assetCount = countRouteAssets()
                )
            }
            summaryState.value = summary
        }
    }

    private fun confirmResetRoutes() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm)
            .setMessage(R.string.clear_profiles_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    SagerDatabase.rulesDao.reset()
                    DataStore.rulesFirstCreate = false
                }
                refreshSummary()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun countRouteAssets(): Int {
        val filesDir = requireContext().filesDir
        var count = 0
        if (File(filesDir, "geoip.db").isFile) count++
        if (File(filesDir, "geosite.db").isFile) count++
        return count
    }

    private data class RouteSummary(
        val totalRules: Int,
        val enabledRules: Int,
        val assetCount: Int,
    )
}
