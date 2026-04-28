package com.yokhan.vpn.app.ui

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yokhan.vpn.shared.ui.design.YokhanPalette
import com.yokhan.vpn.shared.ui.design.YokhanTypography
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import com.yokhan.vpn.app.ui.compose.YokhanBody
import com.yokhan.vpn.app.ui.compose.YokhanButtonGrid
import com.yokhan.vpn.app.ui.compose.YokhanCaption
import com.yokhan.vpn.app.ui.compose.YokhanComposeRouteFragment
import com.yokhan.vpn.app.ui.compose.YokhanDivider
import com.yokhan.vpn.app.ui.compose.YokhanMonoValue
import com.yokhan.vpn.app.ui.compose.YokhanRouteAction
import com.yokhan.vpn.app.ui.compose.YokhanRouteCard
import com.yokhan.vpn.app.ui.compose.YokhanRouteLazyFrame
import com.yokhan.vpn.app.ui.compose.YokhanStatusPill
import com.yokhan.vpn.app.ui.compose.YokhanToggleRow
import com.yokhan.vpn.app.ui.compose.yokhanOutlinedSurface
import com.yokhan.vpn.app.ui.shell.YokhanShellActivityRoute

class RouteFragment : YokhanComposeRouteFragment(), ProfileManager.RuleListener {

    private val uiState = mutableStateOf(RouteUiState())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ProfileManager.addListener(this)
        reloadRules()
    }

    override fun onResume() {
        super.onResume()
        reloadRules()
    }

    override fun onDestroyView() {
        ProfileManager.removeListener(this)
        super.onDestroyView()
    }

    @Composable
    override fun Content(spaceActive: Boolean) {
        val state = uiState.value
        YokhanRouteLazyFrame(
            title = stringResource(R.string.menu_route),
            systemLabel = stringResource(R.string.dashboard_system_label),
            spaceActive = spaceActive,
            onMenuClick = { (activity as? MainActivity)?.openNavigationMenu() },
        ) { colors, typography ->
            item(key = "route-actions") {
                YokhanRouteCard(
                    title = stringResource(R.string.route_hub_actions_title),
                    colors = colors,
                    typography = typography,
                ) {
                    YokhanButtonGrid(
                        actions = listOf(
                            YokhanRouteAction(
                                title = stringResource(R.string.route_add),
                                primary = true,
                                onClick = {
                                    (activity as? MainActivity)?.openActivityRoute(
                                        YokhanShellActivityRoute.RouteCreate,
                                    )
                                },
                            ),
                            YokhanRouteAction(
                                title = stringResource(R.string.route_manage_assets),
                                onClick = {
                                    (activity as? MainActivity)?.openActivityRoute(
                                        YokhanShellActivityRoute.RouteAssets,
                                    )
                                },
                            ),
                            YokhanRouteAction(
                                title = stringResource(R.string.route_reset),
                                enabled = state.rules.isNotEmpty(),
                                onClick = ::confirmResetRoutes,
                            ),
                        ),
                        colors = colors,
                        typography = typography,
                    )
                }
            }

            item(key = "route-docs") {
                RouteDocsCard(colors, typography)
            }

            if (state.rules.isEmpty()) {
                item(key = "empty-routes") {
                    YokhanRouteCard(
                        title = stringResource(R.string.route_hub_rule_totals_title),
                        colors = colors,
                        typography = typography,
                    ) {
                        YokhanMonoValue(
                            text = stringResource(R.string.empty_route_notice),
                            colors = colors,
                            typography = typography,
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = state.rules,
                    key = { _, rule -> rule.id },
                ) { index, rule ->
                    RuleCard(
                        rule = rule,
                        index = index,
                        total = state.rules.size,
                        colors = colors,
                        typography = typography,
                    )
                }
            }
        }
    }

    @Composable
    private fun RouteDocsCard(
        colors: YokhanPalette,
        typography: YokhanTypography,
    ) {
        YokhanRouteCard(
            title = stringResource(R.string.route_hub_overview_title),
            colors = colors,
            typography = typography,
        ) {
            YokhanBody(
                text = stringResource(R.string.route_warn),
                colors = colors,
                typography = typography,
                maxLines = 4,
            )
            YokhanButtonGrid(
                actions = listOf(
                    YokhanRouteAction(
                        title = "GitHub",
                        onClick = { requireContext().launchCustomTab("https://github.com/Yokhan/YokhanVPN") },
                    ),
                ),
                colors = colors,
                typography = typography,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }

    @Composable
    private fun RuleCard(
        rule: RuleEntity,
        index: Int,
        total: Int,
        colors: YokhanPalette,
        typography: YokhanTypography,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .yokhanOutlinedSurface(colors)
                .clickable { toggleRule(rule, !rule.enabled) }
                .padding(16.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = rule.displayName(),
                        color = colors.primary,
                        fontFamily = typography.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    YokhanCaption(
                        text = rule.displayOutbound(),
                        colors = colors,
                        typography = typography,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                YokhanStatusPill(
                    text = stringResource(if (rule.enabled) R.string.enable else R.string.disable),
                    active = rule.enabled,
                    colors = colors,
                    typography = typography,
                )
            }

            val summary = rule.mkSummary()
            if (summary.isNotBlank()) {
                YokhanDivider(colors, Modifier.padding(vertical = 14.dp))
                YokhanMonoValue(
                    text = summary,
                    colors = colors,
                    typography = typography,
                    sizeSp = 11,
                    maxLines = 4,
                )
            }

            YokhanToggleRow(
                title = stringResource(if (rule.enabled) R.string.quick_disable else R.string.quick_enable),
                summary = stringResource(R.string.route_for, rule.displayName()),
                checked = rule.enabled,
                colors = colors,
                typography = typography,
                onCheckedChange = { checked -> toggleRule(rule, checked) },
                modifier = Modifier.padding(top = 14.dp),
            )

            YokhanButtonGrid(
                actions = listOf(
                    YokhanRouteAction(
                        title = stringResource(R.string.edit),
                        primary = true,
                        onClick = { editRule(rule) },
                    ),
                    YokhanRouteAction(
                        title = stringResource(R.string.delete),
                        onClick = { confirmDeleteRule(rule) },
                    ),
                    YokhanRouteAction(
                        title = stringResource(R.string.route_move_up),
                        enabled = index > 0,
                        onClick = { moveRule(index, index - 1) },
                    ),
                    YokhanRouteAction(
                        title = stringResource(R.string.route_move_down),
                        enabled = index < total - 1,
                        onClick = { moveRule(index, index + 1) },
                    ),
                ),
                colors = colors,
                typography = typography,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }

    private fun reloadRules() {
        runOnDefaultDispatcher {
            val rules = ProfileManager.getRules()
            onMainDispatcher {
                uiState.value = RouteUiState(rules = rules)
            }
        }
    }

    private fun editRule(rule: RuleEntity) {
        (activity as? MainActivity)?.openActivityRoute(
            YokhanShellActivityRoute.RouteEdit(rule.id),
        )
    }

    private fun toggleRule(rule: RuleEntity, enabled: Boolean) {
        if (rule.enabled == enabled) return
        runOnDefaultDispatcher {
            ProfileManager.updateRule(rule.copy(enabled = enabled))
            onMainDispatcher { needReload() }
        }
    }

    private fun moveRule(from: Int, to: Int) {
        val rules = uiState.value.rules
        if (from !in rules.indices || to !in rules.indices) return
        val source = rules[from].copy()
        val target = rules[to].copy()
        val sourceOrder = source.userOrder
        source.userOrder = target.userOrder
        target.userOrder = sourceOrder
        runOnDefaultDispatcher {
            SagerDatabase.rulesDao.updateRules(listOf(source, target))
            reloadRules()
            onMainDispatcher { needReload() }
        }
    }

    private fun confirmDeleteRule(rule: RuleEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm)
            .setMessage(R.string.delete_route_prompt)
            .setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    ProfileManager.deleteRule(rule.id)
                    onMainDispatcher { needReload() }
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun confirmResetRoutes() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm)
            .setMessage(R.string.route_reset)
            .setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    SagerDatabase.rulesDao.reset()
                    DataStore.rulesFirstCreate = false
                    reloadRules()
                    onMainDispatcher { needReload() }
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    override suspend fun onAdd(rule: RuleEntity) {
        reloadRules()
    }

    override suspend fun onUpdated(rule: RuleEntity) {
        reloadRules()
    }

    override suspend fun onRemoved(ruleId: Long) {
        reloadRules()
    }

    override suspend fun onCleared() {
        reloadRules()
    }

    private data class RouteUiState(
        val rules: List<RuleEntity> = emptyList(),
    )
}
