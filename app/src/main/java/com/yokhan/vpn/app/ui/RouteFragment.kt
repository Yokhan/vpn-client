package com.yokhan.gates.app.ui

import android.os.Bundle
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import com.yokhan.gates.shared.ui.design.YokhanTypography
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import com.yokhan.gates.shared.ui.route.YokhanActionRow
import com.yokhan.gates.shared.ui.route.YokhanBody
import com.yokhan.gates.shared.ui.route.YokhanButtonGrid
import com.yokhan.gates.shared.ui.route.YokhanCaption
import com.yokhan.gates.app.ui.compose.YokhanActionSheetDialog
import com.yokhan.gates.app.ui.compose.YokhanActionSheetItem
import com.yokhan.gates.app.ui.compose.YokhanActionSheetState
import com.yokhan.gates.app.ui.compose.YokhanComposeRouteFragment
import com.yokhan.gates.shared.ui.design.yokhanPalette
import com.yokhan.gates.shared.ui.design.yokhanTypography
import com.yokhan.gates.shared.ui.route.YokhanDivider
import com.yokhan.gates.shared.ui.route.YokhanMonoValue
import com.yokhan.gates.shared.ui.route.YokhanRouteAction
import com.yokhan.gates.shared.ui.route.YokhanRouteButton
import com.yokhan.gates.shared.ui.route.YokhanRouteCard
import com.yokhan.gates.shared.ui.route.YokhanRouteLazyFrame
import com.yokhan.gates.shared.ui.route.YokhanToggle
import com.yokhan.gates.shared.ui.route.yokhanOutlinedSurface
import com.yokhan.gates.app.ui.shell.YokhanShellActivityRoute

class RouteFragment : YokhanComposeRouteFragment(), ProfileManager.RuleListener {

    private val uiState = mutableStateOf(RouteUiState())
    private val actionSheetState = mutableStateOf<YokhanActionSheetState?>(null)

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
            systemLabel = stringResource(R.string.system_route_label, "04"),
            spaceActive = spaceActive,
            onMenuClick = { (activity as? MainActivity)?.openNavigationMenu() },
        ) { colors, typography ->
            item(key = "app-split-routing") {
                YokhanRouteCard(
                    title = "ПРИЛОЖЕНИЯ И СОВМЕСТИМОСТЬ",
                    colors = colors,
                    typography = typography,
                ) {
                    YokhanActionRow(
                        title = stringResource(R.string.settings_manage_apps),
                        subtitle = state.appRoutingSummary,
                        accent = true,
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
                        title = stringResource(R.string.settings_ru_bypass_card_title),
                        subtitle = state.ruBypassSummary,
                        colors = colors,
                        typography = typography,
                        onClick = {
                            (activity as? MainActivity)?.openActivityRoute(
                                YokhanShellActivityRoute.RuCompatibility,
                            )
                        },
                    )
                }
            }
            item(key = "route-actions") {
                var docsExpanded by remember { mutableStateOf(false) }
                YokhanRouteCard(
                    title = stringResource(R.string.route_hub_actions_title),
                    colors = colors,
                    typography = typography,
                ) {
                    YokhanRouteButton(
                        text = stringResource(R.string.route_add),
                        primary = true,
                        enabled = true,
                        colors = colors,
                        typography = typography,
                        onClick = {
                            (activity as? MainActivity)?.openActivityRoute(
                                YokhanShellActivityRoute.RouteCreate,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    YokhanDivider(colors, Modifier.padding(vertical = 8.dp))
                    YokhanActionRow(
                        title = stringResource(R.string.route_manage_assets),
                        colors = colors,
                        typography = typography,
                        onClick = {
                            (activity as? MainActivity)?.openActivityRoute(
                                YokhanShellActivityRoute.RouteAssets,
                            )
                        },
                    )
                    YokhanDivider(colors, Modifier.padding(vertical = 4.dp))
                    YokhanActionRow(
                        title = stringResource(R.string.route_reset),
                        enabled = state.rules.isNotEmpty(),
                        colors = colors,
                        typography = typography,
                        onClick = ::confirmResetRoutes,
                    )
                    YokhanDivider(colors, Modifier.padding(vertical = 4.dp))
                    YokhanActionRow(
                        title = if (docsExpanded) {
                            stringResource(R.string.route_docs_hint_hide)
                        } else {
                            stringResource(R.string.route_docs_hint_toggle)
                        },
                        trailingText = if (docsExpanded) "▴" else "▾",
                        colors = colors,
                        typography = typography,
                        onClick = { docsExpanded = !docsExpanded },
                    )
                    AnimatedVisibility(visible = docsExpanded) {
                        Column(Modifier.fillMaxWidth()) {
                            YokhanDivider(colors, Modifier.padding(vertical = 10.dp))
                            RouteDocsBody(colors, typography)
                        }
                    }
                }
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
        YokhanActionSheetDialog(
            state = actionSheetState.value,
            colors = yokhanPalette(spaceActive),
            typography = yokhanTypography(),
            onDismiss = { actionSheetState.value = null },
        )
    }

    @Composable
    private fun RouteDocsBody(
        colors: YokhanPalette,
        typography: YokhanTypography,
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
                    title = stringResource(R.string.github),
                    fullWidth = true,
                    onClick = { requireContext().launchCustomTab(BuildConfig.YOKHAN_APP_DOWNLOAD_URL) },
                ),
            ),
            colors = colors,
            typography = typography,
            modifier = Modifier.padding(top = 12.dp),
        )
    }

    @Composable
    private fun RuleCard(
        rule: RuleEntity,
        index: Int,
        total: Int,
        colors: YokhanPalette,
        typography: YokhanTypography,
    ) {
        val displayTitle = ruleDisplayTitle(rule)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .yokhanOutlinedSurface(colors)
                .clickable { editRule(rule) }
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = displayTitle,
                        color = colors.primary,
                        fontFamily = typography.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    YokhanCaption(
                        text = rule.displayOutbound(),
                        colors = colors,
                        typography = typography,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                YokhanToggle(
                    checked = rule.enabled,
                    colors = colors,
                    modifier = Modifier
                        .clickable { toggleRule(rule, !rule.enabled) }
                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                )
            }

            val summary = rule.mkSummary()
            if (summary.isNotBlank()) {
                YokhanDivider(colors, Modifier.padding(vertical = YokhanSpacing.DividerGap))
                YokhanMonoValue(
                    text = summary,
                    colors = colors,
                    typography = typography,
                    sizeSp = 11,
                    maxLines = 4,
                )
            }

            YokhanDivider(colors, Modifier.padding(vertical = 8.dp))
            YokhanActionRow(
                title = stringResource(R.string.route_actions_title),
                colors = colors,
                typography = typography,
                onClick = { showRuleActionsDialog(rule, index, total, displayTitle) },
            )
        }
    }

    @Composable
    private fun ruleDisplayTitle(rule: RuleEntity): String {
        val raw = rule.displayName()
        return when (raw.trim().lowercase()) {
            "block quic" -> stringResource(R.string.route_name_block_quic)
            "block ads" -> stringResource(R.string.route_name_block_ads)
            "block telemetry" -> stringResource(R.string.route_name_block_telemetry)
            "bypass lan" -> stringResource(R.string.route_name_bypass_lan)
            else -> raw
        }
    }

    private fun showRuleActionsDialog(
        rule: RuleEntity,
        index: Int,
        total: Int,
        displayTitle: String,
    ) {
        val entries = mutableListOf<YokhanActionSheetItem>()
        entries += YokhanActionSheetItem(
            title = getString(R.string.edit),
            accent = true,
            onClick = { editRule(rule) },
        )
        if (index > 0) {
            entries += YokhanActionSheetItem(
                title = getString(R.string.route_move_up),
                onClick = { moveRule(index, index - 1) },
            )
        }
        if (index < total - 1) {
            entries += YokhanActionSheetItem(
                title = getString(R.string.route_move_down),
                onClick = { moveRule(index, index + 1) },
            )
        }
        entries += YokhanActionSheetItem(
            title = getString(R.string.delete),
            onClick = { confirmDeleteRule(rule) },
        )
        actionSheetState.value = YokhanActionSheetState(
            title = displayTitle,
            items = entries,
        )
    }

    private fun reloadRules() {
        runOnDefaultDispatcher {
            val rules = ProfileManager.getRules()
            val appRoutingSummary = when {
                DataStore.wholeTrafficMode -> "Весь трафик через GATES"
                !DataStore.proxyApps -> "По умолчанию (автоматический режим)"
                DataStore.bypass -> "Исключать выбранные приложения"
                else -> "Проксировать выбранные приложения"
            }
            val ruBypassSummary = if (DataStore.enableRuBypass && !DataStore.wholeTrafficMode) {
                "Сервисы РФ идут напрямую"
            } else {
                "Выключено"
            }
            onMainDispatcher {
                uiState.value = RouteUiState(
                    rules = rules,
                    appRoutingSummary = appRoutingSummary,
                    ruBypassSummary = ruBypassSummary,
                )
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
        val appRoutingSummary: String = "",
        val ruBypassSummary: String = "",
    )
}
