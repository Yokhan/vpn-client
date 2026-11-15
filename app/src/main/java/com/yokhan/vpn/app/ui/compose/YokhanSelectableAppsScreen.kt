package com.yokhan.gates.app.ui.compose
import com.yokhan.gates.shared.ui.route.YokhanRouteAction
import com.yokhan.gates.shared.ui.route.YokhanRouteLazyFrame
import com.yokhan.gates.shared.ui.route.YokhanRouteCard
import com.yokhan.gates.shared.ui.route.YokhanCheckboxRow
import com.yokhan.gates.shared.ui.route.YokhanButtonGrid
import com.yokhan.gates.shared.ui.route.YokhanMonoValue
import com.yokhan.gates.shared.ui.route.YokhanBody
import com.yokhan.gates.shared.ui.route.YokhanCaption
import com.yokhan.gates.shared.ui.route.YokhanStatusPill
import com.yokhan.gates.shared.ui.route.yokhanOutlinedSurface
import com.yokhan.gates.shared.ui.route.YokhanRouteButton

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yokhan.gates.shared.ui.design.YokhanRadius
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import com.yokhan.gates.shared.ui.design.YokhanStroke
import com.yokhan.gates.shared.ui.design.YokhanTypography
import com.yokhan.gates.shared.ui.route.YokhanActionRow
import com.yokhan.gates.shared.ui.route.YokhanDivider
import io.nekohasekai.sagernet.R

@Immutable
data class YokhanSelectableApp(
    val packageName: String,
    val label: String,
    val uid: Int,
    val system: Boolean,
    val icon: Drawable? = null,
    val iconBitmap: ImageBitmap? = null,
)

@Immutable
data class YokhanFilterChipItem(
    val key: String,
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

@Composable
fun YokhanSelectableAppsScreen(
    title: String,
    spaceActive: Boolean,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    showSystemApps: Boolean,
    onShowSystemAppsChange: (Boolean) -> Unit,
    loading: Boolean,
    apps: List<YokhanSelectableApp>,
    allAppsEmpty: Boolean,
    isSelected: (YokhanSelectableApp) -> Boolean,
    onToggleApp: (YokhanSelectableApp) -> Unit,
    actions: List<YokhanRouteAction>,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    filterChips: List<YokhanFilterChipItem> = emptyList(),
    extraControls: @Composable ColumnScope.(YokhanPalette, YokhanTypography) -> Unit = { _, _ -> },
) {
    YokhanRouteLazyFrame(
        title = title,
        systemLabel = stringResource(R.string.dashboard_system_label),
        spaceActive = spaceActive,
        onBack = onBack,
    ) { colors, typography ->
        item(key = "app-controls") {
            var actionsExpanded by remember { mutableStateOf(false) }
            YokhanRouteCard(
                title = stringResource(R.string.route_hub_actions_title),
                colors = colors,
                typography = typography,
            ) {
                extraControls(colors, typography)
                YokhanTextInput(
                    value = searchText,
                    onValueChange = onSearchTextChange,
                    placeholder = stringResource(R.string.search_apps),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (filterChips.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        filterChips.forEach { chip ->
                            val chipShape = RoundedCornerShape(YokhanRadius.Pill)
                            androidx.compose.material3.Text(
                                text = chip.label,
                                color = if (chip.selected) colors.onAccent else colors.primary,
                                fontFamily = typography.mono,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(chipShape)
                                    .background(if (chip.selected) colors.accent else colors.surfaceRaised)
                                    .border(
                                        YokhanStroke.Thin,
                                        if (chip.selected) colors.accent else colors.border,
                                        chipShape,
                                    )
                                    .clickable(onClick = chip.onClick)
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                } else {
                    YokhanCheckboxRow(
                        title = stringResource(R.string.show_system_apps),
                        checked = showSystemApps,
                        colors = colors,
                        typography = typography,
                        onCheckedChange = onShowSystemAppsChange,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                if (actions.isNotEmpty()) {
                    YokhanDivider(colors, Modifier.padding(top = 10.dp, bottom = 4.dp))
                    YokhanActionRow(
                        title = "Дополнительно (Импорт / Экспорт / Сброс)",
                        trailingText = if (actionsExpanded) "▴" else "▾",
                        colors = colors,
                        typography = typography,
                        onClick = { actionsExpanded = !actionsExpanded },
                    )
                    AnimatedVisibility(visible = actionsExpanded) {
                        YokhanButtonGrid(
                            actions = actions,
                            colors = colors,
                            typography = typography,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        when {
            loading -> item(key = "app-loading") {
                YokhanRouteCard(
                    title = stringResource(R.string.app_list_loading),
                    colors = colors,
                    typography = typography,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.ControlHorizontal),
                    ) {
                        CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp)
                        YokhanMonoValue(
                            text = stringResource(R.string.app_list_loading),
                            colors = colors,
                            typography = typography,
                            sizeSp = 12,
                        )
                    }
                }
            }

            allAppsEmpty -> item(key = "app-empty") {
                YokhanRouteCard(
                    title = stringResource(R.string.select_apps),
                    colors = colors,
                    typography = typography,
                ) {
                    YokhanBody(
                        text = stringResource(R.string.app_list_permission_denied),
                        colors = colors,
                        typography = typography,
                        maxLines = 4,
                    )
                    YokhanButtonGrid(
                        actions = listOf(
                            YokhanRouteAction(
                                title = stringResource(R.string.open_app_settings),
                                primary = true,
                                onClick = onOpenSettings,
                            ),
                        ),
                        colors = colors,
                        typography = typography,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            apps.isEmpty() -> item(key = "app-no-matches") {
                YokhanRouteCard(
                    title = stringResource(R.string.select_apps),
                    colors = colors,
                    typography = typography,
                ) {
                    YokhanBody(
                        text = stringResource(R.string.app_list_no_matches),
                        colors = colors,
                        typography = typography,
                    )
                }
            }

            else -> {
                val columns = 3
                items(
                    items = apps.chunked(columns),
                    key = { row -> row.first().packageName },
                ) { rowApps ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = YokhanSpacing.CompactGap),
                        horizontalArrangement = Arrangement.spacedBy(
                            YokhanSpacing.ControlHorizontal,
                        ),
                    ) {
                        rowApps.forEach { app ->
                            YokhanSelectableAppTile(
                                app = app,
                                selected = isSelected(app),
                                colors = colors,
                                typography = typography,
                                onClick = { onToggleApp(app) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(columns - rowApps.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YokhanSelectableAppTile(
    app: YokhanSelectableApp,
    selected: Boolean,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .yokhanOutlinedSurface(
                colors = colors,
                borderColor = if (selected) colors.accent else colors.border,
            )
            .clickable(onClick = onClick)
            .padding(YokhanSpacing.ControlHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (app.iconBitmap != null) {
            Image(
                bitmap = app.iconBitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(44.dp),
            )
        } else if (app.icon != null) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                },
                update = { image -> image.setImageDrawable(app.icon) },
                modifier = Modifier.size(44.dp),
            )
        } else {
            Spacer(Modifier.size(44.dp))
        }
        androidx.compose.material3.Text(
            text = app.label,
            color = colors.primary,
            fontFamily = typography.mono,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        androidx.compose.material3.Text(
            text = if (selected) "✓" else "—",
            color = if (selected) colors.accent else colors.secondary,
            fontFamily = typography.mono,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun YokhanTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    colors: YokhanPalette,
    typography: YokhanTypography,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        cursorBrush = SolidColor(colors.accent),
        textStyle = TextStyle(
            color = colors.primary,
            fontFamily = typography.mono,
            fontSize = 13.sp,
        ),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { input ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .yokhanOutlinedSurface(
                        colors = colors,
                        radius = YokhanRadius.Panel,
                        backgroundColor = colors.primary.copy(alpha = 0.035f),
                    )
                    .padding(
                        horizontal = YokhanSpacing.ControlHorizontal,
                        vertical = YokhanSpacing.ControlVertical,
                    ),
            ) {
                if (value.isBlank()) {
                    YokhanCaption(
                        text = placeholder,
                        colors = colors,
                        typography = typography,
                    )
                }
                input()
            }
        },
    )
}

@Composable
fun YokhanSegmentedActions(
    actions: List<YokhanRouteAction>,
    colors: YokhanPalette,
    typography: YokhanTypography,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap),
    ) {
        actions.forEach { action ->
            YokhanRouteButton(
                text = action.title,
                primary = action.primary,
                enabled = action.enabled,
                colors = colors,
                typography = typography,
                onClick = action.onClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
    Spacer(Modifier.height(2.dp))
}
