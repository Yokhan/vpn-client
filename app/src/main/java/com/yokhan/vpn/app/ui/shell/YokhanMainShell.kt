package com.yokhan.vpn.app.ui.shell

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import com.yokhan.vpn.app.ui.compose.YokhanRouteStatusDot
import com.yokhan.vpn.app.ui.compose.yokhanOutlinedSurface
import com.yokhan.vpn.shared.ui.design.YokhanAccent
import com.yokhan.vpn.shared.ui.design.YokhanGreen
import com.yokhan.vpn.shared.ui.design.YokhanRadius
import com.yokhan.vpn.shared.ui.design.YokhanSpacing
import com.yokhan.vpn.shared.ui.design.yokhanPalette
import com.yokhan.vpn.shared.ui.design.yokhanTypography
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import kotlinx.coroutines.launch

@Immutable
data class YokhanShellDrawerCommand(
    val sequence: Int = 0,
    val open: Boolean = false,
)

@Composable
fun YokhanMainShell(
    selectedRoute: YokhanShellRoute,
    serviceState: BaseService.State,
    drawerCommand: YokhanShellDrawerCommand,
    onRouteSelected: (YokhanShellRoute) -> Unit,
    onDrawerOpenChanged: (Boolean) -> Unit,
    onFragmentHostReady: (FragmentContainerView) -> Unit,
    dashboardContent: @Composable () -> Unit,
) {
    val spaceActive = serviceState == BaseService.State.Connecting ||
        serviceState == BaseService.State.Connected ||
        serviceState == BaseService.State.Stopping
    val colors = yokhanPalette(spaceActive = true)
    val typography = yokhanTypography()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navItems = rememberShellNavItems()
    val showingDashboard = selectedRoute == YokhanShellRoute.Home

    LaunchedEffect(drawerCommand.sequence) {
        if (drawerCommand.open) drawerState.open() else drawerState.close()
    }
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.currentValue == DrawerValue.Open }
            .collect(onDrawerOpenChanged)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = colors.background,
                drawerContentColor = colors.primary,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(304.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    colors.background,
                                    colors.background.copy(alpha = 0.96f),
                                ),
                            ),
                        )
                        .safeDrawingPadding()
                        .padding(horizontal = 18.dp, vertical = 22.dp),
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        color = colors.primary,
                        fontFamily = typography.text,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.8.sp,
                    )
                    Text(
                        text = stringResource(R.string.dashboard_system_label),
                        color = colors.secondary,
                        fontFamily = typography.mono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 22.dp),
                    )

                    navItems.forEach { item ->
                        ShellNavItem(
                            item = item,
                            selected = item.route == selectedRoute,
                            onClick = {
                                onRouteSelected(item.route)
                                scope.launch { drawerState.close() }
                            },
                        )
                    }

                    Spacer(Modifier.weight(1f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .yokhanOutlinedSurface(colors, radius = YokhanRadius.Panel)
                            .padding(
                                horizontal = YokhanSpacing.ControlHorizontal,
                                vertical = YokhanSpacing.ControlVertical,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        YokhanRouteStatusDot(
                            active = serviceState.connected,
                            colors = colors,
                            activeColor = YokhanGreen,
                        )
                        Text(
                            text = if (serviceState.connected) {
                                stringResource(R.string.dashboard_connected_status)
                            } else {
                                stringResource(R.string.dashboard_disconnected_status)
                            },
                            color = colors.primary,
                            fontFamily = typography.mono,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = YokhanSpacing.CompactGap),
                        )
                    }
                }
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background),
        ) {
            AndroidView(
                factory = { context ->
                    FragmentContainerView(context).apply {
                        id = R.id.fragment_holder
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        onFragmentHostReady(this)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (showingDashboard) 0f else 1f),
            )
            if (showingDashboard) {
                dashboardContent()
            }
        }
    }
}

@Composable
private fun ShellNavItem(
    item: ShellNavItemModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = yokhanPalette(spaceActive = true)
    val typography = yokhanTypography()
    val marker = if (selected) YokhanAccent else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(YokhanRadius.Panel))
            .clickable(onClick = onClick)
            .background(if (selected) colors.primary.copy(alpha = 0.06f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.InlineGap),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(marker),
        )
        Text(
            text = item.glyph,
            color = if (selected) colors.accent else colors.secondary,
            fontFamily = typography.mono,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = item.title,
            color = if (selected) colors.primary else colors.secondary,
            fontFamily = typography.mono,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun rememberShellNavItems(): List<ShellNavItemModel> {
    return listOf(
        ShellNavItemModel(YokhanShellRoute.Home, stringResource(R.string.dashboard_home), "01"),
        ShellNavItemModel(YokhanShellRoute.Configuration, stringResource(R.string.menu_configuration), "02"),
        ShellNavItemModel(YokhanShellRoute.Group, stringResource(R.string.menu_group), "03"),
        ShellNavItemModel(YokhanShellRoute.Route, stringResource(R.string.menu_route), "04"),
        ShellNavItemModel(YokhanShellRoute.Settings, stringResource(R.string.settings), "05"),
        ShellNavItemModel(YokhanShellRoute.Tools, stringResource(R.string.menu_tools), "06"),
        ShellNavItemModel(YokhanShellRoute.Logcat, stringResource(R.string.menu_log), "07"),
        ShellNavItemModel(YokhanShellRoute.About, stringResource(R.string.menu_about), "08"),
    )
}

@Immutable
private data class ShellNavItemModel(
    val route: YokhanShellRoute,
    val title: String,
    val glyph: String,
)
