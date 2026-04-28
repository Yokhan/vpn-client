package com.yokhan.vpn.shared.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
)

@Immutable
data class YokhanTypography(
    val display: FontFamily,
    val text: FontFamily,
    val mono: FontFamily,
)

@Composable
fun yokhanTypography(): YokhanTypography {
    return YokhanTypography(
        display = FontFamily(Font(Res.font.unbounded)),
        text = FontFamily(Font(Res.font.golos_text)),
        mono = FontFamily(Font(Res.font.jetbrains_mono_regular)),
    )
}

fun yokhanPalette(spaceActive: Boolean): YokhanPalette {
    return if (spaceActive) YokhanSpacePalette else YokhanLightPalette
}

val YokhanAccent = Color(0xFFE55A1B)
val YokhanGreen = Color(0xFF28B44F)
val YokhanRed = Color(0xFFE53E3E)

val YokhanLightPalette = YokhanPalette(
    background = Color(0xFFF3F3F7),
    primary = Color(0xFF111117),
    secondary = Color(0xFF6C6C74),
    tertiary = Color(0xFFA3A3AB),
    border = Color(0x14000000),
    divider = Color(0x0D000000),
    accent = YokhanAccent,
    orbCenter = Color.White,
)

val YokhanSpacePalette = YokhanPalette(
    background = Color(0xFF0A0A18),
    primary = Color(0xFFE8E8F0),
    secondary = Color(0xFF8888A0),
    tertiary = Color(0xFF8888A0),
    border = Color(0x1AFFFFFF),
    divider = Color(0x0DFFFFFF),
    accent = YokhanGreen,
    orbCenter = Color(0xFF1C1C34),
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
    val Small = 5.dp
    val MenuGlyph = 8.dp
    val Panel = 14.dp
    val Card = 18.dp
    val Pill = 999.dp
}

object YokhanSpacing {
    val Hairline = 1.dp
    val SectionMarker = 5.dp
    val StatusDot = 7.dp
    val ControlDot = 9.dp
    val Icon = 18.dp
    val MenuGlyph = 32.dp
    val TopBarHeight = 58.dp
    val ScreenHorizontal = 24.dp
    val ScreenTop = 10.dp
    val ScreenBottom = 44.dp
    val SectionGap = 10.dp
    val CardPadding = 16.dp
    val CardTitleGap = 12.dp
    val CompactGap = 8.dp
    val InlineGap = 12.dp
    val ControlHorizontal = 14.dp
    val ControlVertical = 12.dp
}

object YokhanStroke {
    val Thin = 1.dp
}
