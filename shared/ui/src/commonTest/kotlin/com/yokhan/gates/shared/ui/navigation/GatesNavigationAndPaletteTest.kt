package com.yokhan.gates.shared.ui.navigation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yokhan.gates.shared.ui.access.GatesAccessUiLabels
import com.yokhan.gates.shared.ui.design.YokhanAccent
import com.yokhan.gates.shared.ui.design.YokhanLayout
import com.yokhan.gates.shared.ui.design.YokhanLightPalette
import com.yokhan.gates.shared.ui.design.YokhanRadius
import com.yokhan.gates.shared.ui.design.YokhanSpacePalette
import com.yokhan.gates.shared.ui.design.toWin32ColorRef
import com.yokhan.gates.shared.ui.design.yokhanPalette
import com.yokhan.gates.shared.ui.diagnostics.GatesIncidentHistoryLabels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GatesNavigationAndPaletteTest {
    @Test
    fun lightPaletteUsesLogikSurfaceAndAccentWhileSpaceKeepsGatesIdentity() {
        val light = yokhanPalette(spaceActive = false)
        assertEquals(YokhanLightPalette, light)
        assertEquals(Color(0xFFF5F6F6), light.background)
        assertEquals(Color(0xFFFFFFFF), light.surface)
        assertEquals(Color(0xFFE9E9E5), light.surfaceRaised)
        assertEquals(Color(0xFFC6CAC7), light.border)
        assertEquals(Color(0xFFF26316), YokhanAccent)
        assertEquals(YokhanAccent, light.accent)

        val space = yokhanPalette(spaceActive = true)
        assertEquals(YokhanSpacePalette, space)
        assertEquals(Color(0xFF0A0A18), space.background)
        assertNotEquals(light.accent, space.accent)
    }

    @Test
    fun logikGeometryAndReadableWidthTokensMatchSpec() {
        assertEquals(12.dp, YokhanRadius.Panel)
        assertEquals(12.dp, YokhanRadius.Control)
        assertEquals(12.dp, YokhanRadius.Card)
        assertEquals(24.dp, YokhanRadius.NavRail)
        assertEquals(672.dp, YokhanLayout.ReadableMaxWidth)
        assertEquals(840.dp, YokhanLayout.DesktopCompanionBreakpoint)
        assertEquals(1120.dp, YokhanLayout.DesktopTotalMaxWidth)
    }

    @Test
    fun win32ColorRefConvertsPaletteColorsAccurately() {
        assertEquals(0x00180A0A, YokhanSpacePalette.background.toWin32ColorRef())
        assertEquals(0x00F6F6F5, YokhanLightPalette.background.toWin32ColorRef())
    }

    @Test
    fun uiLabelsHaveNonBlankDefaultsForRussianScreens() {
        val access = GatesAccessUiLabels.Russian
        val history = GatesIncidentHistoryLabels.Russian
        assertEquals("Подписка", access.manualTitle)
        assertEquals("Показать", access.showSecret)
        assertEquals("Скрыть", access.hideSecret)
        assertEquals("Показать ещё", history.showMore)
    }
}
