package com.yokhan.gates.app.ui.shell

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import com.yokhan.gates.app.ui.compose.YokhanRouteStatusDot
import com.yokhan.gates.app.ui.compose.yokhanOutlinedSurface
import com.yokhan.gates.shared.ui.design.YokhanAccent
import com.yokhan.gates.shared.ui.design.YokhanMotion
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanRadius
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import com.yokhan.gates.shared.ui.design.YokhanStarfield
import com.yokhan.gates.shared.ui.design.YokhanTypography
import com.yokhan.gates.shared.ui.design.yokhanPalette
import com.yokhan.gates.shared.ui.design.yokhanTypography
import io.nekohasekai.sagernet.BuildConfig
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
    content: @Composable () -> Unit,
) {
    val spaceActive = serviceState == BaseService.State.Connecting ||
        serviceState == BaseService.State.Connected ||
        serviceState == BaseService.State.Stopping
    val colors = yokhanPalette(spaceActive = spaceActive)
    val typography = yokhanTypography()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navItems = rememberShellNavItems()
    val bottomNavItems = rememberShellBottomNavItems()
    val showingDashboard = selectedRoute == YokhanShellRoute.Home
    val density = LocalDensity.current
    val topSafePadding = with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }
    val bottomSafePadding = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp() }
    val bottomContentPadding = ShellBottomNavigationHeight + bottomSafePadding
    val starfieldAlpha by animateFloatAsState(
        targetValue = if (spaceActive) 1f else 0f,
        animationSpec = tween(600, easing = YokhanMotion.CssEase),
        label = "shell-starfield-alpha",
    )

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
                            colors = colors,
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
                            active = true,
                            colors = colors,
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
            if (spaceActive || starfieldAlpha > 0.01f) {
                YokhanStarfield(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(starfieldAlpha),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomContentPadding),
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
                        .padding(top = if (showingDashboard) 0.dp else topSafePadding)
                        .alpha(if (showingDashboard) 0f else 1f),
                )
                if (showingDashboard) {
                    content()
                }
            }
            ShellBottomNavigation(
                selectedRoute = selectedRoute,
                items = bottomNavItems,
                colors = colors,
                onRouteSelected = onRouteSelected,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

private val ShellBottomNavigationHeight = 74.dp

@Composable
private fun ShellBottomNavigation(
    selectedRoute: YokhanShellRoute,
    items: List<ShellBottomNavItemModel>,
    colors: YokhanPalette,
    onRouteSelected: (YokhanShellRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val typography = yokhanTypography()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        colors.background.copy(alpha = 0.92f),
                        colors.background,
                    ),
                ),
            )
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(colors.background.copy(alpha = 0.88f))
                .border(1.dp, colors.border, RoundedCornerShape(26.dp))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items.forEach { item ->
                ShellBottomNavigationItem(
                    item = item,
                    selected = item.route == selectedRoute,
                    colors = colors,
                    typography = typography,
                    onClick = { onRouteSelected(item.route) },
                )
            }
        }
    }
}

@Composable
private fun RowScope.ShellBottomNavigationItem(
    item: ShellBottomNavItemModel,
    selected: Boolean,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onClick: () -> Unit,
) {
    val selectedBackground = colors.primary.copy(alpha = if (selected) 0.09f else 0f)
    val selectedColor = if (selected) colors.primary else colors.secondary
    val accentColor = if (selected) colors.accent else colors.secondary.copy(alpha = 0.58f)
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .background(selectedBackground)
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .width(if (selected) 24.dp else 14.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(accentColor),
        )
        Text(
            text = item.glyph,
            color = accentColor,
            fontFamily = typography.mono,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 5.dp),
        )
        Text(
            text = item.title,
            color = selectedColor,
            fontFamily = typography.mono,
            fontSize = 9.sp,
            lineHeight = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun ShellNavItem(
    item: ShellNavItemModel,
    selected: Boolean,
    colors: YokhanPalette,
    onClick: () -> Unit,
) {
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
    return buildList {
        add(ShellNavItemModel(YokhanShellRoute.Home, stringResource(R.string.dashboard_home), "01"))
        add(ShellNavItemModel(YokhanShellRoute.Configuration, stringResource(R.string.menu_configuration), "02"))
        add(ShellNavItemModel(YokhanShellRoute.Group, stringResource(R.string.menu_group), "03"))
        add(ShellNavItemModel(YokhanShellRoute.Route, stringResource(R.string.menu_route), "04"))
        add(ShellNavItemModel(YokhanShellRoute.Settings, stringResource(R.string.settings), "05"))
        add(ShellNavItemModel(YokhanShellRoute.Tools, stringResource(R.string.menu_tools), "06"))
        if (BuildConfig.DEBUG) {
            add(ShellNavItemModel(YokhanShellRoute.Logcat, stringResource(R.string.menu_log), "07"))
        }
        add(ShellNavItemModel(YokhanShellRoute.About, stringResource(R.string.menu_about), if (BuildConfig.DEBUG) "08" else "07"))
    }
}

@Composable
private fun rememberShellBottomNavItems(): List<ShellBottomNavItemModel> {
    return listOf(
        ShellBottomNavItemModel(YokhanShellRoute.Home, stringResource(R.string.shell_nav_home), "01"),
        ShellBottomNavItemModel(YokhanShellRoute.Configuration, stringResource(R.string.shell_nav_configs), "02"),
        ShellBottomNavItemModel(YokhanShellRoute.Group, stringResource(R.string.shell_nav_groups), "03"),
        ShellBottomNavItemModel(YokhanShellRoute.Route, stringResource(R.string.shell_nav_routes), "04"),
        ShellBottomNavItemModel(YokhanShellRoute.Settings, stringResource(R.string.shell_nav_settings), "05"),
    )
}

@Immutable
private data class ShellNavItemModel(
    val route: YokhanShellRoute,
    val title: String,
    val glyph: String,
)

@Immutable
private data class ShellBottomNavItemModel(
    val route: YokhanShellRoute,
    val title: String,
    val glyph: String,
)
