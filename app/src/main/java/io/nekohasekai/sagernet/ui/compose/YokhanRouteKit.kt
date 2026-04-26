package io.nekohasekai.sagernet.ui.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yokhan.vpn.shared.ui.design.YokhanGreen
import com.yokhan.vpn.shared.ui.design.YokhanMotion
import com.yokhan.vpn.shared.ui.design.YokhanPalette
import com.yokhan.vpn.shared.ui.design.YokhanTypography
import com.yokhan.vpn.shared.ui.design.yokhanPalette
import com.yokhan.vpn.shared.ui.design.yokhanTypography

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
    val target = yokhanPalette(spaceActive)
    val colors = target.animate()
    val typography = yokhanTypography()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        YokhanRouteStars(
            active = spaceActive,
            color = colors.secondary,
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
                    .padding(horizontal = 24.dp)
                    .padding(top = 10.dp, bottom = 44.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
    val target = yokhanPalette(spaceActive)
    val colors = target.animate()
    val typography = yokhanTypography()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        YokhanRouteStars(
            active = spaceActive,
            color = colors.secondary,
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 24.dp,
                    top = 10.dp,
                    end = 24.dp,
                    bottom = 44.dp,
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
    val target = yokhanPalette(spaceActive)
    val colors = target.animate()
    val typography = yokhanTypography()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        YokhanRouteStars(
            active = spaceActive,
            color = colors.secondary,
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
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, colors.border, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        YokhanSectionLabel(title, colors, typography)
        Spacer(Modifier.height(12.dp))
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
        modifier = modifier.heightIn(min = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .offset(y = 0.5.dp)
                .clip(RoundedCornerShape(1.5.dp))
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
            .height(1.dp)
            .background(colors.divider),
    )
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            YokhanMonoValue(firstValue, colors, typography, sizeSp = 12, maxLines = 2)
            YokhanCaption(firstLabel, colors, typography, Modifier.padding(top = 4.dp))
        }
        Box(
            modifier = Modifier
                .width(1.dp)
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(alpha)
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
            .clip(RoundedCornerShape(14.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
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
            .clip(RoundedCornerShape(14.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .border(1.dp, colors.border, RoundedCornerShape(5.dp))
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
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, colors.border, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (active) YokhanGreen else colors.tertiary),
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
            .height(58.dp)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
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
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(colors.accent),
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
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
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
                .offset(x = knobOffset)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked) colors.accent else colors.primary.copy(alpha = 0.78f)),
        )
    }
}

@Composable
private fun YokhanRouteStars(
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val alpha by animateColorAsState(
        targetValue = if (active) color.copy(alpha = 0.82f) else Color.Transparent,
        animationSpec = tween(600, easing = YokhanMotion.CssEase),
        label = "route-stars-alpha",
    )
    val stars = remember {
        listOf(
            0.07f to 0.09f,
            0.19f to 0.22f,
            0.43f to 0.08f,
            0.61f to 0.19f,
            0.91f to 0.13f,
            0.13f to 0.43f,
            0.35f to 0.53f,
            0.72f to 0.47f,
            0.88f to 0.62f,
            0.22f to 0.81f,
            0.55f to 0.89f,
            0.79f to 0.77f,
        )
    }
    Canvas(modifier = modifier) {
        stars.forEachIndexed { index, star ->
            val radius = if (index % 4 == 0) 2.0f else 1.2f
            drawCircle(
                color = alpha,
                radius = radius.dp.toPx(),
                center = Offset(size.width * star.first, size.height * star.second),
            )
        }
        if (active) {
            listOf(0.18f to 0.16f, 0.58f to 0.36f, 0.82f to 0.28f, 0.28f to 0.66f).forEach {
                drawLine(
                    color = alpha.copy(alpha = 0.62f),
                    start = Offset(size.width * it.first, size.height * it.second),
                    end = Offset(size.width * it.first, size.height * it.second + 22.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
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
