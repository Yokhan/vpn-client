package com.yokhan.gates.shared.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yokhan.gates.shared.ui.design.YokhanLayout
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanRadius
import com.yokhan.gates.shared.ui.design.YokhanStroke
import com.yokhan.gates.shared.ui.design.yokhanTypography

@Immutable
data class GatesNavigationItem(val key: String, val title: String, val glyph: String)

object GatesNavigationTokens {
    val Height = 76.dp
    val RailHeight = 60.dp
    val RailRadius = YokhanRadius.NavRail
    val ItemRadius = YokhanRadius.NavItem
}

@Composable
fun GatesBottomNavigation(
    selectedKey: String,
    items: List<GatesNavigationItem>,
    colors: YokhanPalette,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val typography = yokhanTypography()
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        val wideDesktop = maxWidth >= YokhanLayout.DesktopCompanionBreakpoint
        val railMaxWidth = if (wideDesktop) YokhanLayout.DesktopNavMaxWidth else YokhanLayout.ReadableMaxWidth
        val railHeight = if (wideDesktop) 62.dp else GatesNavigationTokens.RailHeight
        val titleFontSize = if (wideDesktop) 11.sp else 9.sp
        val titleLineHeight = if (wideDesktop) 12.sp else 10.sp
        val itemSpacing = if (wideDesktop) 6.dp else 3.dp

        Row(
            Modifier
                .fillMaxWidth()
                .widthIn(max = railMaxWidth)
                .height(railHeight)
                .clip(RoundedCornerShape(GatesNavigationTokens.RailRadius))
                .background(colors.surface)
                .border(YokhanStroke.Thin, colors.border, RoundedCornerShape(GatesNavigationTokens.RailRadius))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            val haptics = LocalHapticFeedback.current
            items.forEach { item ->
                val selected = item.key == selectedKey
                val accent by animateColorAsState(
                    targetValue = if (selected) colors.accent else colors.secondary.copy(alpha = 0.72f),
                    animationSpec = tween(180),
                    label = "nav-accent-${item.key}",
                )
                val pillBackground by animateColorAsState(
                    targetValue = if (selected) colors.surfaceRaised else Color.Transparent,
                    animationSpec = tween(180),
                    label = "nav-bg-${item.key}",
                )
                val indicatorWidth by animateDpAsState(
                    targetValue = if (selected) 24.dp else 12.dp,
                    animationSpec = tween(180),
                    label = "nav-indicator-${item.key}",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag("route.nav.${item.key.lowercase()}")
                        .clip(RoundedCornerShape(GatesNavigationTokens.ItemRadius))
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.Tab,
                            onClick = {
                                if (!selected) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                onSelect(item.key)
                            },
                        )
                        .background(pillBackground)
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .width(indicatorWidth)
                            .height(2.5.dp)
                            .clip(RoundedCornerShape(YokhanRadius.Pill))
                            .background(if (selected) accent else colors.border),
                    )
                    Text(
                        item.glyph,
                        color = accent,
                        fontFamily = typography.mono,
                        fontSize = 10.sp,
                        lineHeight = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                    Text(
                        item.title,
                        color = if (selected) colors.primary else colors.secondary,
                        fontFamily = typography.mono,
                        fontSize = titleFontSize,
                        lineHeight = titleLineHeight,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
