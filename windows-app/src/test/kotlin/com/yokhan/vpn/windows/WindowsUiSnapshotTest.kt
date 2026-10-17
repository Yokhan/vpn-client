package com.yokhan.gates.windows

import com.yokhan.gates.shared.domain.ConnectionStatus
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsUiSnapshotTest {
    @Test
    fun utilityFailureDoesNotPretendTheTunnelDisconnected() {
        assertEquals(ConnectionStatus.Connected,
            windowsUiStatusAfterCommand(ConnectionStatus.Connected, null, 1))
        assertEquals(ConnectionStatus.Connected,
            windowsUiStatusAfterCommand(ConnectionStatus.Connected, null, 0))
    }

    @Test
    fun explicitConnectionCommandsRetainTheirStatusTransitions() {
        assertEquals(ConnectionStatus.Connected,
            windowsUiStatusAfterCommand(ConnectionStatus.Connecting, ConnectionStatus.Connected, 0))
        assertEquals(ConnectionStatus.Disconnected,
            windowsUiStatusAfterCommand(ConnectionStatus.Disconnecting, ConnectionStatus.Disconnected, 0))
        assertEquals(ConnectionStatus.Disconnected,
            windowsUiStatusAfterCommand(ConnectionStatus.Connecting, ConnectionStatus.Connected, 1))
    }

    @Test
    fun startupRuntimeStateIsStaleAfterRuntimeCommandBegins() {
        val generation = WindowsRuntimeCommandGeneration()
        val startupGeneration = generation.capture()

        assertTrue(generation.isUnchanged(startupGeneration))
        generation.commandStarted()
        assertFalse(generation.isUnchanged(startupGeneration))
    }

    @Test
    fun snapshotReadsRunOnIoDispatcherAndAreCachedTogether() {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "windows-ui-snapshot-test-io")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val uiThread = Thread.currentThread()
            var configThread: Thread? = null
            var sessionThread: Thread? = null
            var routingThread: Thread? = null
            val config = WindowsAppConfig(activeRoute = "bridge")
            val session = WindowsSessionState(true, true, true, "ready")
            val routing = AppRoutingState(true, listOf("app.exe"), emptyList(), emptyList(), emptyList())

            val snapshot = runBlocking {
                loadWindowsUiSnapshot(
                    dispatcher = dispatcher,
                    readConfig = { configThread = Thread.currentThread(); config },
                    readSession = { sessionThread = Thread.currentThread(); session },
                    readAppRouting = { routingThread = Thread.currentThread(); routing },
                )
            }

            assertTrue(configThread !== uiThread)
            assertEquals(configThread, sessionThread)
            assertEquals(configThread, routingThread)
            assertEquals(config, snapshot.config)
            assertEquals(session, snapshot.session)
            assertEquals(routing, snapshot.appRouting)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun successfulActionClearsBusyWithoutReportingFailure() = runBlocking {
        val busyChanges = mutableListOf<Boolean>()
        var completed = false
        var failureReported = false
        runWindowsUiAction(busyChanges::add, { failureReported = true }) {
            completed = true
        }
        assertTrue(completed)
        assertFalse(failureReported)
        assertEquals(listOf(true, false), busyChanges)
    }

    @Test
    fun failedActionUsesGenericMessageAndAlwaysClearsBusy() = runBlocking {
        val busyChanges = mutableListOf<Boolean>()
        var message = ""

        runWindowsUiAction(
            setBusy = busyChanges::add,
            onFailure = { message = it },
        ) {
            throw IllegalStateException("credential-must-not-appear")
        }

        assertEquals(listOf(true, false), busyChanges)
        assertEquals(WINDOWS_UI_FAILURE_MESSAGE, message)
        assertFalse(message.contains("credential"))
    }

    @Test
    fun cancelledActionRethrowsCancellationAndClearsBusy() = runBlocking {
        val busyChanges = mutableListOf<Boolean>()
        var failureReported = false

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            runBlocking {
                runWindowsUiAction(
                    setBusy = busyChanges::add,
                    onFailure = { failureReported = true },
                ) {
                    throw kotlinx.coroutines.CancellationException("cancelled")
                }
            }
        }

        assertEquals(listOf(true, false), busyChanges)
        assertFalse(failureReported)
    }
}
