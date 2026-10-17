package com.yokhan.gates.windows

import com.yokhan.gates.windows.qa.WindowsQaState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class WindowsQaLaunchTest {
    @Test fun normalAndCliLaunchesDoNotEnableUiApi() {
        assertFalse(windowsQaRequested(emptyArray()))
        assertFalse(windowsQaRequested(arrayOf("--cli", "status")))
        assertTrue(windowsQaRequested(arrayOf("--qa-ui")))
        assertTrue(windowsQaRequested(arrayOf("--qa-access")))
    }

    @Test fun qaCannotBeCombinedWithRuntimeCommandsOrPaths() {
        for (args in listOf(arrayOf("--qa-ui", "connect"), arrayOf("--cli", "--qa-ui"),
            arrayOf("--qa-ui", "C:\\Users\\user\\AppData"), arrayOf("--qa-access", "connect"), arrayOf("--qa-acces"), arrayOf("unknown"))) {
            assertFailsWith<IllegalArgumentException> { windowsQaRequested(args) }
        }
    }

    @Test fun fixtureHistoryContainsNoRealAccountData() {
        val items = windowsQaIncidentItems()
        assertTrue(items.isNotEmpty())
        assertTrue(items.all { it.id.startsWith("fixture-") })
        assertTrue(items.any { it.incomplete })
    }

    @Test fun modalNeedsItsOwnCurrentFrameNotJustTheMainWindow() {
        val frames = WindowsQaFrames()
        val modal = WindowsQaState("bug", 3, false, emptyList())
        frames.drawn(WindowsQaSurface.BUG, 2)
        frames.drawn(WindowsQaSurface.MAIN, 3)
        assertFalse(frames.ready(modal))
        frames.drawn(WindowsQaSurface.BUG, 3)
        assertTrue(frames.ready(modal))
        assertFalse(frames.ready(modal.copy(revision = 4)))
    }

    @Test fun anOldDrawCannotAcknowledgeANewerPublication() {
        val frames = WindowsQaFrames()
        val state = WindowsQaState("settings", 5, false, emptyList())
        frames.drawn(WindowsQaSurface.MAIN, 4)
        assertFalse(frames.ready(state))
        frames.drawn(WindowsQaSurface.MAIN, 5)
        frames.drawn(WindowsQaSurface.MAIN, 4)
        assertTrue(frames.ready(state))
    }
}
