package com.yokhan.gates.app.ui.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yokhan.gates.shared.ui.design.YokhanGreen
import com.yokhan.gates.shared.ui.design.YokhanMotion
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanRadius
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import com.yokhan.gates.shared.ui.design.YokhanStarfield
import com.yokhan.gates.shared.ui.design.YokhanStroke
import com.yokhan.gates.shared.ui.design.YokhanTypography
import com.yokhan.gates.shared.ui.design.yokhanPalette
import com.yokhan.gates.shared.ui.design.yokhanTypography

@Immutable
data class YokhanRouteAction(
    val title: String,
    val primary: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
fun YokhanRouteFrame(
    title: String,
    systemLabel: String,
    spaceActive: Boolean,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(YokhanPalette, YokhanTypography) -> Unit,
) {
    val target = yokhanPalette(spaceActive = spaceActive)
    val colors = target.animate()
    val typography = yokhanTypography()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        YokhanRouteStars(
            active = spaceActive,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            YokhanRouteTopBar(
                title = title,
                systemLabel = systemLabel,
                colors = colors,
                typography = typography,
                onMenuClick = onMenuClick,
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = YokhanSpacing.ScreenHorizontal)
                    .padding(
                        top = YokhanSpacing.ScreenTop,
                        bottom = YokhanSpacing.ScreenBottom,
                    ),
                verticalArrangement = Arrangement.spacedBy(YokhanSpacing.SectionGap),
            ) {
                content(colors, typography)
            }
        }
    }
}

@Composable
fun YokhanRouteLazyFrame(
    title: String,
    systemLabel: String,
    spaceActive: Boolean,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.(YokhanPalette, YokhanTypography) -> Unit,
) {
    val target = yokhanPalette(spaceActive = spaceActive)
    val colors = target.animate()
    val typography = yokhanTypography()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        YokhanRouteStars(
            active = spaceActive,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            YokhanRouteTopBar(
                title = title,
                systemLabel = systemLabel,
                colors = colors,
                typography = typography,
                onMenuClick = onMenuClick,
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(YokhanSpacing.SectionGap),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = YokhanSpacing.ScreenHorizontal,
                    top = YokhanSpacing.ScreenTop,
                    end = YokhanSpacing.ScreenHorizontal,
                    bottom = YokhanSpacing.ScreenBottom,
                ),
            ) {
                content(colors, typography)
            }
        }
    }
}

@Composable
fun YokhanRouteSurface(
    title: String,
    systemLabel: String,
    spaceActive: Boolean,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(YokhanPalette, YokhanTypography) -> Unit,
) {
    val target = yokhanPalette(spaceActive = spaceActive)
    val colors = target.animate()
    val typography = yokhanTypography()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        YokhanRouteStars(
            active = spaceActive,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            YokhanRouteTopBar(
                title = title,
                systemLabel = systemLabel,
                colors = colors,
                typography = typography,
                onMenuClick = onMenuClick,
            )
            content(colors, typography)
        }
    }
}

@Composable
fun YokhanRouteCard(
    title: String,
    colors: YokhanPalette,
    typography: YokhanTypography,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .yokhanOutlinedSurface(colors)
            .padding(YokhanSpacing.CardPadding),
    ) {
        YokhanSectionLabel(title, colors, typography)
        Spacer(Modifier.height(YokhanSpacing.CardTitleGap))
        content()
    }
}

@Composable
fun YokhanSectionLabel(
    title: String,
    colors: YokhanPalette,
    typography: YokhanTypography,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.heightIn(min = YokhanSpacing.Icon),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.StatusDot),
    ) {
        Box(
            modifier = Modifier
                .size(YokhanSpacing.SectionMarker)
                .offset(y = 0.5.dp)
                .clip(RoundedCornerShape(YokhanRadius.SectionMarker))
                .background(colors.primary.copy(alpha = 0.72f)),
        )
        Text(
            text = title.uppercase(),
            color = colors.secondary,
            fontFamily = typography.mono,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun YokhanHeadline(
    text: String,
    colors: YokhanPalette,
    typography: YokhanTypography,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = colors.primary,
        fontFamily = typography.text,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun YokhanBody(
    text: String,
    colors: YokhanPalette,
    typography: YokhanTypography,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        color = colors.secondary,
        fontFamily = typography.text,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun YokhanMonoValue(
    text: String,
    colors: YokhanPalette,
    typography: YokhanTypography,
    modifier: Modifier = Modifier,
    sizeSp: Int = 13,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        color = colors.primary,
        fontFamily = typography.mono,
        fontSize = sizeSp.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = (sizeSp + 5).sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun YokhanCaption(
    text: String,
    colors: YokhanPalette,
    typography: YokhanTypography,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = colors.tertiary,
        fontFamily = typography.mono,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
        modifier = modifier,
    )
}

@Composable
fun YokhanDivider(colors: YokhanPalette, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(YokhanStroke.Thin)
            .background(colors.divider),
    )
}

@Composable
fun YokhanRouteStatusDot(
    active: Boolean,
    colors: YokhanPalette,
    modifier: Modifier = Modifier,
    size: Dp = YokhanSpacing.StatusDot,
    activeColor: Color = colors.accent,
    inactiveColor: Color = colors.tertiary,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) activeColor else inactiveColor),
    )
}

@Composable
fun YokhanRouteProgressBar(
    progressFraction: Float,
    colors: YokhanPalette,
    modifier: Modifier = Modifier,
    showTrack: Boolean = true,
) {
    val fraction = progressFraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(YokhanSpacing.ProgressBar)
            .background(if (showTrack) colors.divider else Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(YokhanSpacing.ProgressBar)
                .background(colors.accent),
        )
    }
}

fun Modifier.yokhanOutlinedSurface(
    colors: YokhanPalette,
    radius: Dp = YokhanRadius.Card,
    borderColor: Color = colors.border,
    backgroundColor: Color = Color.Transparent,
): Modifier {
    val shape = RoundedCornerShape(radius)
    return clip(shape)
        .background(backgroundColor)
        .border(YokhanStroke.Thin, borderColor, shape)
}

@Composable
fun YokhanStatPair(
    firstValue: String,
    firstLabel: String,
    secondValue: String,
    secondLabel: String,
    colors: YokhanPalette,
    typography: YokhanTypography,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.InlineGap),
    ) {
        Column(Modifier.weight(1f)) {
            YokhanMonoValue(firstValue, colors, typography, sizeSp = 12, maxLines = 2)
            YokhanCaption(firstLabel, colors, typography, Modifier.padding(top = 4.dp))
        }
        Box(
            modifier = Modifier
                .width(YokhanStroke.Thin)
                .height(42.dp)
                .background(colors.divider),
        )
        Column(Modifier.weight(1f)) {
            YokhanMonoValue(secondValue, colors, typography, sizeSp = 12, maxLines = 2)
            YokhanCaption(secondLabel, colors, typography, Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun YokhanButtonGrid(
    actions: List<YokhanRouteAction>,
    colors: YokhanPalette,
    typography: YokhanTypography,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap),
    ) {
        actions.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap),
            ) {
                row.forEach { action ->
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
                if (row.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun YokhanRouteButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha = if (enabled) 1f else 0.42f
    val background = if (primary) colors.accent.copy(alpha = 0.92f) else Color.Transparent
    val border = if (primary) colors.accent.copy(alpha = 0.9f) else colors.border
    val textColor = if (primary) Color.White else colors.primary

    Box(
        modifier = modifier
            .heightIn(min = YokhanSpacing.ControlMinHeight)
            .clip(RoundedCornerShape(YokhanRadius.Pill))
            .background(background)
            .border(YokhanStroke.Thin, border, RoundedCornerShape(YokhanRadius.Pill))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(alpha)
            .padding(
                horizontal = YokhanSpacing.ControlHorizontal,
                vertical = YokhanSpacing.ControlVertical,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontFamily = typography.mono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun YokhanToggleRow(
    title: String,
    summary: String,
    checked: Boolean,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(YokhanRadius.Panel))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.ControlHorizontal),
    ) {
        Column(Modifier.weight(1f)) {
            YokhanMonoValue(title, colors, typography, sizeSp = 13)
            YokhanBody(summary, colors, typography, Modifier.padding(top = 4.dp), maxLines = 3)
        }
        YokhanToggle(checked, colors)
    }
}

@Composable
fun YokhanCheckboxRow(
    title: String,
    checked: Boolean,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(YokhanRadius.Panel))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.InlineGap),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(YokhanRadius.Small))
                .border(YokhanStroke.Thin, colors.border, RoundedCornerShape(YokhanRadius.Small))
                .background(if (checked) colors.accent.copy(alpha = 0.9f) else Color.Transparent),
        )
        YokhanMonoValue(title, colors, typography, sizeSp = 13, modifier = Modifier.weight(1f))
    }
}

@Composable
fun YokhanStatusPill(
    text: String,
    active: Boolean,
    colors: YokhanPalette,
    typography: YokhanTypography,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(YokhanRadius.Pill))
            .border(YokhanStroke.Thin, colors.border, RoundedCornerShape(YokhanRadius.Pill))
            .padding(horizontal = YokhanSpacing.InlineGap, vertical = YokhanSpacing.CompactGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap),
    ) {
        YokhanRouteStatusDot(
            active = active,
            colors = colors,
            activeColor = YokhanGreen,
        )
        Text(
            text = text,
            color = colors.primary,
            fontFamily = typography.mono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun YokhanRouteTopBar(
    title: String,
    systemLabel: String,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onMenuClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(YokhanSpacing.TopBarHeight)
            .padding(horizontal = YokhanSpacing.ScreenHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.Icon),
    ) {
        YokhanMenuGlyph(colors = colors, onClick = onMenuClick)
        Text(
            text = title,
            color = colors.primary,
            fontFamily = typography.text,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = systemLabel.uppercase(),
                color = colors.secondary,
                fontFamily = typography.mono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                maxLines = 1,
            )
            YokhanRouteStatusDot(
                active = true,
                colors = colors,
                size = YokhanSpacing.ControlDot,
            )
        }
    }
}

@Composable
private fun YokhanMenuGlyph(
    colors: YokhanPalette,
    onClick: () -> Unit,
) {
    Canvas(
        modifier = Modifier
            .size(YokhanSpacing.MenuGlyph)
            .clip(RoundedCornerShape(YokhanRadius.MenuGlyph))
            .clickable(onClick = onClick)
            .padding(3.dp),
    ) {
        val stroke = 2.4.dp.toPx()
        val x0 = 4.dp.toPx()
        val x1 = size.width - 4.dp.toPx()
        listOf(8.dp.toPx(), 16.dp.toPx(), 24.dp.toPx()).forEach { y ->
            drawLine(
                color = colors.primary.copy(alpha = 0.78f),
                start = Offset(x0, y),
                end = Offset(x1, y),
                strokeWidth = stroke,
                cap = StrokeCap.Square,
            )
        }
    }
}

@Composable
private fun YokhanToggle(
    checked: Boolean,
    colors: YokhanPalette,
) {
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = tween(220, easing = YokhanMotion.CssEaseOut),
        label = "route-toggle",
    )
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .width(48.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (checked) colors.accent.copy(alpha = 0.35f) else colors.primary.copy(alpha = 0.08f))
            .border(1.dp, colors.border, RoundedCornerShape(999.dp)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(with(density) { knobOffset.roundToPx() }, 0) }
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked) colors.accent else colors.primary.copy(alpha = 0.78f)),
        )
    }
}

@Composable
private fun YokhanRouteStars(
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(600, easing = YokhanMotion.CssEase),
        label = "route-stars-alpha",
    )
    if (active || alpha > 0.01f) {
        YokhanStarfield(
            modifier = modifier.alpha(alpha),
        )
    }
}

@Composable
private fun YokhanPalette.animate(): YokhanPalette {
    return YokhanPalette(
        background = animateColorAsState(background, tween(560, easing = YokhanMotion.CssEase), label = "route-bg").value,
        primary = animateColorAsState(primary, tween(360, easing = YokhanMotion.CssEase), label = "route-primary").value,
        secondary = animateColorAsState(secondary, tween(360, easing = YokhanMotion.CssEase), label = "route-secondary").value,
        tertiary = animateColorAsState(tertiary, tween(360, easing = YokhanMotion.CssEase), label = "route-tertiary").value,
        border = animateColorAsState(border, tween(360, easing = YokhanMotion.CssEase), label = "route-border").value,
        divider = animateColorAsState(divider, tween(360, easing = YokhanMotion.CssEase), label = "route-divider").value,
        accent = animateColorAsState(accent, tween(360, easing = YokhanMotion.CssEase), label = "route-accent").value,
        orbCenter = animateColorAsState(orbCenter, tween(360, easing = YokhanMotion.CssEase), label = "route-orb").value,
    )
}
