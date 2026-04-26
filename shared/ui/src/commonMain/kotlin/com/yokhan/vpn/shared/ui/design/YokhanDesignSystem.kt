package com.yokhan.vpn.shared.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
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
