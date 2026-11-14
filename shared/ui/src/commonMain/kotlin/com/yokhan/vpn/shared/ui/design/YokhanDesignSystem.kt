package com.yokhan.gates.shared.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import nb4a.shared_ui.generated.resources.Res
import nb4a.shared_ui.generated.resources.golos_text
import nb4a.shared_ui.generated.resources.jetbrains_mono_regular
import nb4a.shared_ui.generated.resources.unbounded
import org.jetbrains.compose.resources.Font

@Immutable
data class YokhanPalette(
    val background: Color,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val border: Color,
    val divider: Color,
    val accent: Color,
    val orbCenter: Color,
    val surface: Color = background,
    val surfaceRaised: Color = border,
    val surfaceMuted: Color = background,
    val onAccent: Color = Color.White,
    val success: Color = YokhanGreen,
    val danger: Color = YokhanRed,
)

@Immutable
data class YokhanTypography(
    val display: FontFamily,
    val text: FontFamily,
    val mono: FontFamily,
)

@Composable
fun yokhanTypography(): YokhanTypography {
    val displayFont = Font(Res.font.unbounded)
    val textFont = Font(Res.font.golos_text)
    val monoFont = Font(Res.font.jetbrains_mono_regular)
    return remember(displayFont, textFont, monoFont) {
        YokhanTypography(
            display = FontFamily(displayFont),
            text = FontFamily(textFont),
            mono = FontFamily(monoFont),
        )
    }
}

fun yokhanPalette(spaceActive: Boolean): YokhanPalette {
    return if (spaceActive) YokhanSpacePalette else YokhanLightPalette
}

val YokhanAccent = Color(0xFFF26316)
val YokhanGreen = Color(0xFF28B44F)
val YokhanRed = Color(0xFFB54436)
val YokhanSuccess = Color(0xFF23734F)

val YokhanLightPalette = YokhanPalette(
    background = Color(0xFFF5F6F6),
    primary = Color(0xFF292C2D),
    secondary = Color(0xFF5D6163),
    tertiary = Color(0xFF7D8284),
    border = Color(0xFFC6CAC7),
    divider = Color(0xFFE0E3E0),
    accent = YokhanAccent,
    orbCenter = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFE9E9E5),
    surfaceMuted = Color(0xFFECEEEB),
    onAccent = Color(0xFFFFFFFF),
    success = YokhanSuccess,
    danger = YokhanRed,
)

val YokhanSpacePalette = YokhanPalette(
    background = Color(0xFF0A0A18),
    primary = Color(0xFFF2F3EF),
    secondary = Color(0xFF9EA2B0),
    tertiary = Color(0xFF7E8394),
    border = Color(0x28FFFFFF),
    divider = Color(0x16FFFFFF),
    accent = YokhanGreen,
    orbCenter = Color(0xFF1C1C34),
    surface = Color(0xFF141626),
    surfaceRaised = Color(0xFF1C1F33),
    surfaceMuted = Color(0xFF171929),
    onAccent = Color(0xFF08140D),
    success = YokhanGreen,
    danger = Color(0xFFE5534B),
)

object YokhanMotion {
    val CssEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
    val CssEaseIn = CubicBezierEasing(0.42f, 0f, 1f, 1f)
    val CssEaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    val CssEaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    val RocketLaunch = CubicBezierEasing(0.2f, 0.8f, 0.3f, 1f)
    val RocketDisconnect = CubicBezierEasing(0.4f, 0f, 0.8f, 0.2f)
}

object YokhanRadius {
    val Tiny = 1.dp
    val SectionMarker = 1.5.dp
    val Small = 6.dp
    val MenuGlyph = 8.dp
    val Control = 12.dp
    val Panel = 12.dp
    val Card = 12.dp
    val NavItem = 18.dp
    val NavRail = 24.dp
    val Pill = 999.dp
}

object YokhanSpacing {
    val Hairline = 1.dp
    val ProgressBar = 3.dp
    val SectionMarker = 5.dp
    val StatusDot = 7.dp
    val ControlDot = 9.dp
    val Icon = 18.dp
    val MenuGlyph = 32.dp
    val TopBarHeight = 58.dp
    val ScreenHorizontal = 12.dp
    val ScreenTop = 10.dp
    val ScreenBottom = 44.dp
    val SectionGap = 10.dp
    val CardPadding = 16.dp
    val CardTitleGap = 12.dp
    val DividerGap = 14.dp
    val CompactDividerGap = 12.dp
    val ActionTopGap = 14.dp
    val ControlMinHeight = 44.dp
    val CompactGap = 8.dp
    val InlineGap = 12.dp
    val ControlHorizontal = 14.dp
    val ControlVertical = 12.dp
}

object YokhanStroke {
    val Thin = 1.dp
}

object YokhanLayout {
    val ReadableMaxWidth = 672.dp
    val CompactBreakpoint = 360.dp
    val StackedSpeedBreakpoint = 316.dp
    val DesktopCompanionBreakpoint = 840.dp
    val DesktopContentCompanionBreakpoint = 580.dp
    val DesktopPrimaryColumnWidth = 460.dp
    val DesktopTotalMaxWidth = 1120.dp
    val DesktopNavMaxWidth = 920.dp
    val DesktopColumnGap = 16.dp
}

/** Converts a Compose [Color] into a Win32 COLORREF integer (`0x00BBGGRR`). */
fun Color.toWin32ColorRef(): Int {
    val r = (red.coerceIn(0f, 1f) * 255f).toInt() and 0xFF
    val g = (green.coerceIn(0f, 1f) * 255f).toInt() and 0xFF
    val b = (blue.coerceIn(0f, 1f) * 255f).toInt() and 0xFF
    return (b shl 16) or (g shl 8) or r
}
