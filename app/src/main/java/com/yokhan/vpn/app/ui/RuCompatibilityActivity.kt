package com.yokhan.gates.app.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.yokhan.gates.app.routing.RuCompatibilityClassifier
import com.yokhan.gates.app.routing.RuCompatibilityDecision
import com.yokhan.gates.app.routing.RuCompatibilityReason
import com.yokhan.gates.app.routing.RuCompatibilityRoute
import com.yokhan.gates.shared.ui.route.YokhanBody
import com.yokhan.gates.shared.ui.route.YokhanCaption
import com.yokhan.gates.shared.ui.route.YokhanMonoValue
import com.yokhan.gates.shared.ui.route.YokhanRouteCard
import com.yokhan.gates.shared.ui.route.YokhanRouteLazyFrame
import com.yokhan.gates.shared.ui.route.YokhanStatusPill
import com.yokhan.gates.shared.ui.route.yokhanOutlinedSurface
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import com.yokhan.gates.shared.ui.design.YokhanTypography
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.utils.PackageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RuCompatibilityActivity : ThemedActivity() {

    private val compatibilityState = mutableStateOf(RuCompatibilityUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Content() }
        loadCompatibility()
    }

    @Composable
    private fun Content() {
        val state = compatibilityState.value
        YokhanRouteLazyFrame(
            title = getString(R.string.ru_compatibility_title),
            systemLabel = getString(R.string.dashboard_system_label),
            spaceActive = DataStore.serviceState.isSpaceActive(),
            onMenuClick = ::finish,
        ) { colors, typography ->
            item(key = "summary") {
                YokhanRouteCard(
                    title = getString(R.string.ru_compatibility_overview),
                    colors = colors,
                    typography = typography,
                ) {
                    YokhanMonoValue(
                        text = getString(
                            R.string.ru_compatibility_overview_value,
                            state.directCount,
                            state.totalCount,
                            state.exactRuleCount,
                            state.prefixRuleCount,
                        ),
                        colors = colors,
                        typography = typography,
                        sizeSp = 12,
                        maxLines = 3,
                    )
                    YokhanBody(
                        text = getString(R.string.ru_compatibility_overview_summary),
                        colors = colors,
                        typography = typography,
                        modifier = Modifier.padding(top = 8.dp),
                        maxLines = 5,
                    )
                }
            }

            items(
                items = state.rows,
                key = { it.packageName },
            ) { row ->
                CompatibilityRow(
                    row = row,
                    colors = colors,
                    typography = typography,
                )
            }
        }
    }

    @Composable
    private fun CompatibilityRow(
        row: RuCompatibilityRow,
        colors: YokhanPalette,
        typography: YokhanTypography,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .yokhanOutlinedSurface(colors)
                .padding(YokhanSpacing.ControlHorizontal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.ControlHorizontal),
        ) {
            Column(Modifier.weight(1f)) {
                YokhanMonoValue(
                    text = row.label,
                    colors = colors,
                    typography = typography,
                    sizeSp = 14,
                    maxLines = 1,
                )
                YokhanCaption(
                    text = row.packageName,
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 4.dp),
                )
                YokhanBody(
                    text = row.reason,
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 6.dp),
                    maxLines = 2,
                )
            }
            YokhanStatusPill(
                text = getString(
                    if (row.route == RuCompatibilityRoute.Direct) {
                        R.string.ru_compatibility_direct
                    } else {
                        R.string.ru_compatibility_tunnel
                    }
                ),
                active = row.route == RuCompatibilityRoute.Direct,
                colors = colors,
                typography = typography,
            )
        }
    }

    private fun loadCompatibility() {
        lifecycleScope.launch {
            compatibilityState.value = RuCompatibilityUiState(loading = true)
            val nextState = withContext(Dispatchers.IO) {
                PackageCache.reload()
                val packages = PackageCache.installedPackages.keys
                    .filterNot { it == BuildConfig.APPLICATION_ID }
                val classifier = RuCompatibilityClassifier(this@RuCompatibilityActivity)
                val decisions = classifier.explainInstalledPackages(packages)
                val ruleCount = classifier.countRules()
                val rows = decisions.map { it.toRow() }
                RuCompatibilityUiState(
                    loading = false,
                    totalCount = rows.size,
                    directCount = rows.count { it.route == RuCompatibilityRoute.Direct },
                    exactRuleCount = ruleCount.exactDirect,
                    prefixRuleCount = ruleCount.prefixDirect,
                    rows = rows,
                )
            }
            compatibilityState.value = nextState
        }
    }

    private fun RuCompatibilityDecision.toRow(): RuCompatibilityRow {
        return RuCompatibilityRow(
            packageName = packageName,
            label = PackageCache.loadLabel(packageName),
            route = route,
            reason = when (reason) {
                RuCompatibilityReason.ExactRule -> getString(
                    R.string.ru_compatibility_reason_exact,
                    matchedRule.orEmpty(),
                )
                RuCompatibilityReason.PrefixRule -> getString(
                    R.string.ru_compatibility_reason_prefix,
                    matchedRule.orEmpty(),
                )
                RuCompatibilityReason.DefaultRoute -> getString(R.string.ru_compatibility_reason_default)
            },
        )
    }

    private fun BaseService.State.isSpaceActive(): Boolean {
        return this == BaseService.State.Connecting ||
            this == BaseService.State.Connected ||
            this == BaseService.State.Stopping
    }

    private data class RuCompatibilityUiState(
        val loading: Boolean = false,
        val totalCount: Int = 0,
        val directCount: Int = 0,
        val exactRuleCount: Int = 0,
        val prefixRuleCount: Int = 0,
        val rows: List<RuCompatibilityRow> = emptyList(),
    )

    private data class RuCompatibilityRow(
        val packageName: String,
        val label: String,
        val route: RuCompatibilityRoute,
        val reason: String,
    )
}
