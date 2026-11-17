package com.yokhan.gates.shared.ui.route

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yokhan.gates.shared.ui.design.YokhanGreen
import com.yokhan.gates.shared.ui.design.YokhanLayout
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
    val fullWidth: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun YokhanRouteFrame(
    title: String,
    systemLabel: String,
    spaceActive: Boolean,
    onMenuClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    showMenu: Boolean = false,
    bottomInsetPaddingDp: Int = 64,
    subtitle: String = "",
    fullWidthContent: Boolean = false,
    companionContent: (@Composable ColumnScope.(YokhanPalette, YokhanTypography) -> Unit)? = null,
    content: @Composable ColumnScope.(YokhanPalette, YokhanTypography) -> Unit,
) {
    val colors = yokhanPalette(spaceActive = spaceActive)
    val bgState = animateColorAsState(
        targetValue = colors.background,
        animationSpec = tween(380, easing = YokhanMotion.CssEase),
        label = "route-frame-bg",
    )
    val typography = yokhanTypography()
    val bottomScrollPadding = YokhanSpacing.ScreenBottom +
        bottomInsetPaddingDp.dp +
        WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind { drawRect(bgState.value) },
    ) {
        YokhanRouteStars(
            active = spaceActive,
            modifier = Modifier.fillMaxSize(),
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            val useCompanion = companionContent != null && maxWidth >= YokhanLayout.DesktopContentCompanionBreakpoint
            val expandFullWidth = fullWidthContent && maxWidth >= YokhanLayout.DesktopContentCompanionBreakpoint
            val topBarMaxWidth = if (useCompanion || expandFullWidth) YokhanLayout.DesktopTotalMaxWidth else YokhanLayout.ReadableMaxWidth

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                YokhanRouteTopBar(
                    title = title,
                    subtitle = subtitle,
                    systemLabel = systemLabel,
                    colors = colors,
                    typography = typography,
                    onMenuClick = onMenuClick,
                    onBack = onBack,
                    showMenu = showMenu,
                    maxWidth = topBarMaxWidth,
                )
                if (useCompanion) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .widthIn(max = YokhanLayout.DesktopTotalMaxWidth)
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(YokhanLayout.DesktopColumnGap),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(0.47f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(
                                    top = YokhanSpacing.ScreenTop,
                                    bottom = bottomScrollPadding,
                                ),
                            verticalArrangement = Arrangement.spacedBy(YokhanSpacing.SectionGap),
                        ) {
                            content(colors, typography)
                        }
                        Column(
                            modifier = Modifier
                                .weight(0.53f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(
                                    top = YokhanSpacing.ScreenTop,
                                    bottom = bottomScrollPadding,
                                ),
                            verticalArrangement = Arrangement.spacedBy(YokhanSpacing.SectionGap),
                        ) {
                            companionContent?.invoke(this, colors, typography)
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = YokhanLayout.ReadableMaxWidth)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = YokhanSpacing.ScreenHorizontal)
                                .padding(
                                    top = YokhanSpacing.ScreenTop,
                                    bottom = bottomScrollPadding,
                                ),
                            verticalArrangement = Arrangement.spacedBy(YokhanSpacing.SectionGap),
                        ) {
                            content(colors, typography)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun YokhanRouteLazyFrame(
    title: String,
    systemLabel: String,
    spaceActive: Boolean,
    onMenuClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    showMenu: Boolean = false,
    bottomInsetPaddingDp: Int = 64,
    subtitle: String = "",
    fullWidthContent: Boolean = false,
    companionContent: (@Composable ColumnScope.(YokhanPalette, YokhanTypography) -> Unit)? = null,
    content: LazyListScope.(YokhanPalette, YokhanTypography) -> Unit,
) {
    val colors = yokhanPalette(spaceActive = spaceActive)
    val bgState = animateColorAsState(
        targetValue = colors.background,
        animationSpec = tween(380, easing = YokhanMotion.CssEase),
        label = "route-lazy-bg",
    )
    val typography = yokhanTypography()
    val bottomScrollPadding = YokhanSpacing.ScreenBottom +
        bottomInsetPaddingDp.dp +
        WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind { drawRect(bgState.value) },
    ) {
        YokhanRouteStars(
            active = spaceActive,
            modifier = Modifier.fillMaxSize(),
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            val useCompanion = companionContent != null && maxWidth >= YokhanLayout.DesktopContentCompanionBreakpoint
            val expandFullWidth = fullWidthContent && maxWidth >= YokhanLayout.DesktopContentCompanionBreakpoint
            val topBarMaxWidth = if (useCompanion || expandFullWidth) YokhanLayout.DesktopTotalMaxWidth else YokhanLayout.ReadableMaxWidth

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                YokhanRouteTopBar(
                    title = title,
                    subtitle = subtitle,
                    systemLabel = systemLabel,
                    colors = colors,
                    typography = typography,
                    onMenuClick = onMenuClick,
                    onBack = onBack,
                    showMenu = showMenu,
                    maxWidth = topBarMaxWidth,
                )
                if (useCompanion) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .widthIn(max = YokhanLayout.DesktopTotalMaxWidth)
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(YokhanLayout.DesktopColumnGap),
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .weight(0.48f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(YokhanSpacing.SectionGap),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                top = YokhanSpacing.ScreenTop,
                                bottom = bottomScrollPadding,
                            ),
                        ) {
                            content(colors, typography)
                        }
                        Column(
                            modifier = Modifier
                                .weight(0.52f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(
                                    top = YokhanSpacing.ScreenTop,
                                    bottom = bottomScrollPadding,
                                ),
                            verticalArrangement = Arrangement.spacedBy(YokhanSpacing.SectionGap),
                        ) {
                            companionContent?.invoke(this, colors, typography)
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = if (expandFullWidth) YokhanLayout.DesktopTotalMaxWidth else YokhanLayout.ReadableMaxWidth),
                            verticalArrangement = Arrangement.spacedBy(YokhanSpacing.SectionGap),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = YokhanSpacing.ScreenHorizontal,
                                top = YokhanSpacing.ScreenTop,
                                end = YokhanSpacing.ScreenHorizontal,
                                bottom = bottomScrollPadding,
                            ),
                        ) {
                            content(colors, typography)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun YokhanRouteSurface(
    title: String,
    systemLabel: String,
    spaceActive: Boolean,
    onMenuClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    showMenu: Boolean = false,
    content: @Composable ColumnScope.(YokhanPalette, YokhanTypography) -> Unit,
) {
    val colors = yokhanPalette(spaceActive = spaceActive)
    val bgState = animateColorAsState(
        targetValue = colors.background,
        animationSpec = tween(380, easing = YokhanMotion.CssEase),
        label = "route-surface-bg",
    )
    val typography = yokhanTypography()

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind { drawRect(bgState.value) },
    ) {
        YokhanRouteStars(
            active = spaceActive,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            YokhanRouteTopBar(
                title = title,
                systemLabel = systemLabel,
                colors = colors,
                typography = typography,
                onMenuClick = onMenuClick,
                showMenu = showMenu,
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
            letterSpacing = 0.sp,
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
        letterSpacing = 0.sp,
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
        letterSpacing = 0.sp,
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
    backgroundColor: Color = colors.surface,
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
fun YokhanAdaptiveActionPair(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 320.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
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
    val rows = mutableListOf<List<YokhanRouteAction>>()
    var pendingPair = mutableListOf<YokhanRouteAction>()
    for (action in actions) {
        if (action.fullWidth) {
            if (pendingPair.isNotEmpty()) {
                rows += pendingPair.toList()
                pendingPair = mutableListOf()
            }
            rows += listOf(action)
        } else {
            pendingPair += action
            if (pendingPair.size == 2) {
                rows += pendingPair.toList()
                pendingPair = mutableListOf()
            }
        }
    }
    if (pendingPair.isNotEmpty()) {
        rows += pendingPair.toList()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap),
    ) {
        rows.forEach { row ->
            if (row.size == 1 && row[0].fullWidth) {
                val action = row[0]
                YokhanRouteButton(
                    text = action.title,
                    primary = action.primary,
                    enabled = action.enabled,
                    colors = colors,
                    typography = typography,
                    onClick = action.onClick,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
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
    val background = (if (primary) colors.accent else colors.surface).let {
        if (enabled) it else it.copy(alpha = 0.55f)
    }
    val border = (if (primary) colors.accent else colors.border).let {
        if (enabled) it else it.copy(alpha = 0.55f)
    }
    val textColor = (if (primary) colors.onAccent else colors.primary).copy(alpha = alpha)
    val shape = RoundedCornerShape(YokhanRadius.Control)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.98f else 1f,
        animationSpec = tween(110),
        label = "route-btn-scale",
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .heightIn(min = YokhanSpacing.ControlMinHeight)
            .clip(shape)
            .background(background)
            .border(YokhanStroke.Thin, border, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.foundation.LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(
                horizontal = 10.dp,
                vertical = 10.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontFamily = typography.mono,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            letterSpacing = 0.sp,
            maxLines = 2,
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
fun YokhanActionRow(
    title: String,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    trailingText: String = "›",
    accent: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.985f else 1f,
        animationSpec = tween(100),
        label = "action-row-scale",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = YokhanSpacing.ControlMinHeight)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(RoundedCornerShape(YokhanRadius.Panel))
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.foundation.LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.42f)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.InlineGap),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (accent) colors.accent else colors.primary,
                fontFamily = typography.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                YokhanBody(
                    text = subtitle,
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 3.dp),
                    maxLines = 2,
                )
            }
        }
        if (trailingText.isNotEmpty()) {
            Text(
                text = trailingText,
                color = if (accent) colors.accent else colors.secondary,
                fontFamily = typography.mono,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
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
    subtitle: String = "",
    onBack: (() -> Unit)? = null,
    showMenu: Boolean = false,
    backContentDescription: String = "Назад",
    maxWidth: Dp = YokhanLayout.ReadableMaxWidth,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = maxWidth)
            .heightIn(min = YokhanSpacing.TopBarHeight)
            .padding(horizontal = YokhanSpacing.ScreenHorizontal, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack == null && showMenu) {
            YokhanMenuGlyph(
                colors = colors,
                onClick = onMenuClick,
            )
        } else if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("route.back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backContentDescription, tint = colors.primary)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = colors.primary,
                fontFamily = typography.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.2).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = colors.secondary,
                    fontFamily = typography.text,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = systemLabel.uppercase(),
                color = colors.secondary,
                fontFamily = typography.mono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.sp,
                maxLines = 1,
            )
            YokhanRouteStatusDot(
                active = true,
                colors = colors,
                size = 6.dp,
            )
        }
    }
}

@Composable
private fun YokhanMenuGlyph(
    colors: YokhanPalette,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 36.dp, height = 34.dp)
            .testTag("route.menu")
            .clip(RoundedCornerShape(YokhanRadius.Small))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(YokhanRadius.Tiny))
                        .background(colors.primary.copy(alpha = 0.74f)),
                )
            }
        }
    }
}

@Composable
fun YokhanToggle(
    checked: Boolean,
    colors: YokhanPalette,
    modifier: Modifier = Modifier,
) {
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = tween(220, easing = YokhanMotion.CssEaseOut),
        label = "route-toggle",
    )
    val density = LocalDensity.current
    Box(
        modifier = modifier
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
    var mounted by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active) {
            mounted = true
        } else {
            delay(480)
            mounted = false
        }
    }
    val alphaState = animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(420, easing = YokhanMotion.CssEase),
        label = "route-stars-alpha",
    )
    if (mounted) {
        YokhanStarfield(
            modifier = modifier.graphicsLayer { alpha = alphaState.value },
            active = mounted,
        )
    }
}
