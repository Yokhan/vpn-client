package com.yokhan.vpn.app.ui.shell

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.IdRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import com.yokhan.vpn.shared.ui.design.YokhanAccent
import com.yokhan.vpn.shared.ui.design.YokhanGreen
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
    selectedRouteId: Int,
    serviceState: BaseService.State,
    drawerCommand: YokhanShellDrawerCommand,
    onRouteSelected: (Int) -> Unit,
    onDrawerOpenChanged: (Boolean) -> Unit,
    onFragmentHostReady: (FragmentContainerView) -> Unit,
) {
    val spaceActive = serviceState == BaseService.State.Connecting ||
        serviceState == BaseService.State.Connected ||
        serviceState == BaseService.State.Stopping
    val colors = yokhanPalette(spaceActive)
    val typography = yokhanTypography()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navItems = rememberShellNavItems()

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
                            selected = item.id == selectedRouteId,
                            spaceActive = spaceActive,
                            onClick = {
                                onRouteSelected(item.id)
                                scope.launch { drawerState.close() }
                            },
                        )
                    }

                    Spacer(Modifier.weight(1f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (serviceState.connected) YokhanGreen else colors.tertiary),
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
                            modifier = Modifier.padding(start = 8.dp),
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
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ShellNavItem(
    item: ShellNavItemModel,
    selected: Boolean,
    spaceActive: Boolean,
    onClick: () -> Unit,
) {
    val colors = yokhanPalette(spaceActive)
    val typography = yokhanTypography()
    val marker = if (selected) YokhanAccent else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .background(if (selected) colors.primary.copy(alpha = 0.06f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
        ShellNavItemModel(R.id.nav_home, stringResource(R.string.dashboard_home), "01"),
        ShellNavItemModel(R.id.nav_configuration, stringResource(R.string.menu_configuration), "02"),
        ShellNavItemModel(R.id.nav_group, stringResource(R.string.menu_group), "03"),
        ShellNavItemModel(R.id.nav_route, stringResource(R.string.menu_route), "04"),
        ShellNavItemModel(R.id.nav_settings, stringResource(R.string.settings), "05"),
        ShellNavItemModel(R.id.nav_tools, stringResource(R.string.menu_tools), "06"),
        ShellNavItemModel(R.id.nav_logcat, stringResource(R.string.menu_log), "07"),
        ShellNavItemModel(R.id.nav_about, stringResource(R.string.menu_about), "08"),
    )
}

@Immutable
private data class ShellNavItemModel(
    @IdRes val id: Int,
    val title: String,
    val glyph: String,
)
