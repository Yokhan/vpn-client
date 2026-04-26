package com.yokhan.vpn.app.ui.compose

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yokhan.vpn.shared.ui.design.YokhanPalette
import com.yokhan.vpn.shared.ui.design.YokhanTypography
import io.nekohasekai.sagernet.R

@Immutable
data class YokhanSelectableApp(
    val packageName: String,
    val label: String,
    val uid: Int,
    val system: Boolean,
    val icon: Drawable,
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
    extraControls: @Composable ColumnScope.(YokhanPalette, YokhanTypography) -> Unit = { _, _ -> },
) {
    YokhanRouteLazyFrame(
        title = title,
        systemLabel = stringResource(R.string.dashboard_system_label),
        spaceActive = spaceActive,
        onMenuClick = onBack,
    ) { colors, typography ->
        item(key = "app-controls") {
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
                YokhanCheckboxRow(
                    title = stringResource(R.string.show_system_apps),
                    checked = showSystemApps,
                    colors = colors,
                    typography = typography,
                    onCheckedChange = onShowSystemAppsChange,
                    modifier = Modifier.padding(top = 10.dp),
                )
                YokhanButtonGrid(
                    actions = actions,
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 12.dp),
                )
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
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
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

            else -> items(
                items = apps,
                key = { it.packageName },
            ) { app ->
                YokhanSelectableAppRow(
                    app = app,
                    selected = isSelected(app),
                    colors = colors,
                    typography = typography,
                    onClick = { onToggleApp(app) },
                )
            }
        }
    }
}

@Composable
private fun YokhanSelectableAppRow(
    app: YokhanSelectableApp,
    selected: Boolean,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, colors.border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
            },
            update = { image -> image.setImageDrawable(app.icon) },
            modifier = Modifier.size(40.dp),
        )
        Column(Modifier.weight(1f)) {
            androidx.compose.material3.Text(
                text = app.label,
                color = colors.primary,
                fontFamily = typography.mono,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            YokhanCaption(
                text = "${app.packageName} (${app.uid})",
                colors = colors,
                typography = typography,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        YokhanStatusPill(
            text = stringResource(if (selected) R.string.enable else R.string.disable),
            active = selected,
            colors = colors,
            typography = typography,
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
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.primary.copy(alpha = 0.035f))
                    .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
