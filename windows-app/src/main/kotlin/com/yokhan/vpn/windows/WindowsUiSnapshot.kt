package com.yokhan.gates.windows

import com.yokhan.gates.shared.domain.ConnectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class WindowsUiSnapshot(
    val config: WindowsAppConfig,
    val session: WindowsSessionState,
    val appRouting: AppRoutingState,
)

internal suspend fun loadWindowsUiSnapshot(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    readConfig: () -> WindowsAppConfig,
    readSession: () -> WindowsSessionState,
    readAppRouting: () -> AppRoutingState,
): WindowsUiSnapshot = withContext(dispatcher) {
    WindowsUiSnapshot(
        config = readConfig(),
        session = readSession(),
        appRouting = readAppRouting(),
    )
}

internal const val WINDOWS_UI_FAILURE_MESSAGE = "Не удалось выполнить операцию."

internal fun windowsUiStatusAfterCommand(
    current: ConnectionStatus,
    requested: ConnectionStatus?,
    exitCode: Int,
): ConnectionStatus = when {
    requested == null -> current
    exitCode == 0 -> requested
    else -> ConnectionStatus.Disconnected
}

internal class WindowsRuntimeCommandGeneration {
    private var generation = 0L

    fun capture(): Long = generation

    fun commandStarted() {
        generation += 1
    }

    fun isUnchanged(capturedGeneration: Long): Boolean = generation == capturedGeneration
}

internal suspend fun runWindowsUiAction(
    setBusy: (Boolean) -> Unit,
    onFailure: (String) -> Unit,
    action: suspend () -> Unit,
) {
    setBusy(true)
    try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        onFailure(WINDOWS_UI_FAILURE_MESSAGE)
    } finally {
        setBusy(false)
    }
}
