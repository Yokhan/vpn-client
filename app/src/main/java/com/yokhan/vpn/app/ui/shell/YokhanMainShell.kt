package com.yokhan.gates.app.ui.shell

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import com.yokhan.gates.shared.ui.route.YokhanRouteStatusDot
import com.yokhan.gates.shared.ui.design.YokhanMotion
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanRadius
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import com.yokhan.gates.shared.ui.design.YokhanStarfield
import com.yokhan.gates.shared.ui.design.YokhanTypography
import com.yokhan.gates.shared.ui.design.yokhanPalette
import com.yokhan.gates.shared.ui.design.yokhanTypography
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import kotlinx.coroutines.delay

@Immutable
data class YokhanShellDrawerCommand(
    val sequence: Int = 0,
    val open: Boolean = false,
)

@Immutable
data class YokhanShellBanner(
    val id: Long,
    val text: String,
    val isError: Boolean = false,
)

@Composable
fun YokhanMainShell(
    selectedRoute: YokhanShellRoute,
    serviceState: BaseService.State,
    drawerCommand: YokhanShellDrawerCommand,
    onRouteSelected: (YokhanShellRoute) -> Unit,
    onActivityRouteSelected: (YokhanShellActivityRoute) -> Unit = {},
    onDrawerOpenChanged: (Boolean) -> Unit,
    onFragmentHostReady: (FragmentContainerView) -> Unit,
    banner: YokhanShellBanner? = null,
    onDismissBanner: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val spaceActive = serviceState == BaseService.State.Connecting ||
        serviceState == BaseService.State.Connected
    val colors = yokhanPalette(spaceActive = spaceActive)
    val typography = yokhanTypography()
    val bottomNavItems = rememberShellBottomNavItems()
    val showingDashboard = selectedRoute == YokhanShellRoute.Home
    val fragmentAlphaState = animateFloatAsState(
        targetValue = if (showingDashboard) 0f else 1f,
        animationSpec = tween(160, easing = YokhanMotion.CssEaseOut),
        label = "shell-fragment-alpha",
    )
    val dashboardAlphaState = animateFloatAsState(
        targetValue = if (showingDashboard) 1f else 0f,
        animationSpec = tween(160, easing = YokhanMotion.CssEaseOut),
        label = "shell-dashboard-alpha",
    )
    val shellBgState = animateColorAsState(
        targetValue = colors.background,
        animationSpec = tween(420, easing = YokhanMotion.CssEase),
        label = "shell-bg",
    )
    var starfieldMounted by remember { mutableStateOf(spaceActive) }
    LaunchedEffect(spaceActive) {
        if (spaceActive) {
            starfieldMounted = true
        } else {
            delay(480)
            starfieldMounted = false
        }
    }
    val starfieldAlphaState = animateFloatAsState(
        targetValue = if (spaceActive) 1f else 0f,
        animationSpec = tween(420, easing = YokhanMotion.CssEase),
        label = "shell-starfield-alpha",
    )
    val density = LocalDensity.current
    val topSafePadding = with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }
    val bottomSafePadding = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp() }
    val bottomContentPadding = ShellBottomNavigationHeight + bottomSafePadding

    LaunchedEffect(drawerCommand.sequence) {
        onDrawerOpenChanged(false)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind { drawRect(shellBgState.value) },
    ) {
        if (starfieldMounted) {
            YokhanStarfield(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = starfieldAlphaState.value },
                active = starfieldMounted,
                animate = starfieldMounted && showingDashboard,
            )
        }
        Box(
            modifier = Modifier.fillMaxSize(),
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
                update = { host ->
                    val alphaVal = fragmentAlphaState.value
                    if (host.alpha != alphaVal) {
                        host.alpha = alphaVal
                    }
                    val targetVisibility = if (showingDashboard && alphaVal <= 0.01f) {
                        View.INVISIBLE
                    } else {
                        View.VISIBLE
                    }
                    if (host.visibility != targetVisibility) {
                        host.visibility = targetVisibility
                    }
                    val targetA11y = if (showingDashboard) {
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    } else {
                        View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    }
                    if (host.importantForAccessibility != targetA11y) {
                        host.importantForAccessibility = targetA11y
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topSafePadding),
            )
            if (showingDashboard || dashboardAlphaState.value > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = dashboardAlphaState.value },
                ) {
                    content()
                }
            }
        }
        var renderedBanner by remember { mutableStateOf(banner) }
        if (banner != null) {
            renderedBanner = banner
            LaunchedEffect(banner.id) {
                delay(if (banner.isError) 8_000L else 5_000L)
                onDismissBanner()
            }
        }
        AnimatedVisibility(
            visible = banner != null,
            enter = fadeIn(tween(220, easing = YokhanMotion.CssEaseOut)) +
                slideInVertically(tween(240, easing = YokhanMotion.CssEaseOut)) { it / 2 },
            exit = fadeOut(tween(150, easing = YokhanMotion.CssEaseIn)) +
                slideOutVertically(tween(150, easing = YokhanMotion.CssEaseIn)) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = YokhanSpacing.ScreenHorizontal,
                    end = YokhanSpacing.ScreenHorizontal,
                    bottom = bottomContentPadding + 12.dp,
                ),
        ) {
            renderedBanner?.let { currentBanner ->
                ShellAlertBanner(
                    banner = currentBanner,
                    colors = colors,
                    typography = typography,
                    onDismiss = onDismissBanner,
                )
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

@Composable
private fun ShellAlertBanner(
    banner: YokhanShellBanner,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(YokhanRadius.Card)
    val dotColor = if (banner.isError) colors.danger else colors.accent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, if (banner.isError) colors.danger.copy(alpha = 0.55f) else colors.border, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        YokhanRouteStatusDot(
            active = true,
            colors = colors,
            activeColor = dotColor,
        )
        Text(
            text = banner.text,
            color = colors.primary,
            fontFamily = typography.text,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.shell_banner_dismiss),
            color = colors.accent,
            fontFamily = typography.mono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(YokhanRadius.Small))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

private val ShellBottomNavigationHeight = com.yokhan.gates.shared.ui.navigation.GatesNavigationTokens.Height

@Composable
private fun ShellBottomNavigation(
    selectedRoute: YokhanShellRoute,
    items: List<ShellBottomNavItemModel>,
    colors: YokhanPalette,
    onRouteSelected: (YokhanShellRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    com.yokhan.gates.shared.ui.navigation.GatesBottomNavigation(
        selectedKey = selectedRoute.name,
        items = items.map { com.yokhan.gates.shared.ui.navigation.GatesNavigationItem(it.route.name, it.title, it.glyph) },
        colors = colors,
        onSelect = { key -> items.firstOrNull { it.route.name == key }?.let { onRouteSelected(it.route) } },
        modifier = modifier,
    )
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
private data class ShellBottomNavItemModel(
    val route: YokhanShellRoute,
    val title: String,
    val glyph: String,
)
