package com.yokhan.gates.windows

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yokhan.gates.shared.ui.dashboard.DashboardUiLabels

internal enum class WindowsProtectionState {
    UNAVAILABLE, DISABLED, BLOCKED, ARMED, ERROR;

    val retainsProtection: Boolean get() = this in setOf(BLOCKED, ARMED, ERROR)
}

internal fun parseWindowsProtectionState(result: RuntimeCommandResult): WindowsProtectionState =
    if (result.exitCode != 0) WindowsProtectionState.UNAVAILABLE
    else WindowsProtectionState.entries.firstOrNull { it.name == result.output.trim() }
        ?: WindowsProtectionState.UNAVAILABLE

internal fun windowsProtectionLabel(state: WindowsProtectionState): String = when (state) {
    WindowsProtectionState.UNAVAILABLE -> "Состояние защиты недоступно"
    WindowsProtectionState.DISABLED -> "Защита при обрыве выключена"
    WindowsProtectionState.BLOCKED -> "Трафик выбранных EXE заблокирован"
    WindowsProtectionState.ARMED -> "Выбранные EXE защищены при обрыве"
    WindowsProtectionState.ERROR -> "Ошибка защиты. Не считайте соединение защищённым"
}

internal fun windowsProtectionDashboardLabels(state: WindowsProtectionState, enabled: Boolean,
    connected: Boolean): DashboardUiLabels = when {
    state == WindowsProtectionState.BLOCKED -> DashboardUiLabels.Russian.copy(
        disconnected = "ТРАФИК ЗАБЛОКИРОВАН", connectAction = "ОТКЛЮЧИТЬ VPN",
        orbIdle = "СТОП", tapConnect = "Защита при обрыве")
    state == WindowsProtectionState.ERROR -> DashboardUiLabels.Russian.copy(
        disconnected = "ЗАЩИТА НЕ ПОДТВЕРЖДЕНА", connectAction = "ОТКЛЮЧИТЬ VPN",
        orbIdle = "СТОП", tapConnect = "Ошибка защиты")
    state == WindowsProtectionState.ARMED && !connected -> DashboardUiLabels.Russian.copy(
        disconnected = "ЗАЩИТА АКТИВНА", connectAction = "ОТКЛЮЧИТЬ VPN",
        orbIdle = "СТОП", tapConnect = "Защита при обрыве")
    state == WindowsProtectionState.UNAVAILABLE && enabled -> DashboardUiLabels.Russian.copy(
        disconnected = "ЗАЩИТА НЕ ПОДТВЕРЖДЕНА", tapConnect = "Служба недоступна")
    else -> DashboardUiLabels.Russian
}

internal fun windowsProtectionConfig(configJson: String, config: WindowsAppConfig): String {
    if (!config.killSwitchEnabled) return configJson
    require(config.runtimeMode == "tun") { "GATES_PROTECTION_REQUIRES_TUN" }
    val paths = config.appProxyProcessPaths.distinctBy(String::lowercase)
    require(paths.isNotEmpty() && paths.size <= 128 && paths.all(::isWindowsProtectionExe)) {
        "GATES_PROTECTION_REQUIRES_EXE_PATHS"
    }
    val names = paths.map { it.substringAfterLast('\\').lowercase() }.toSet()
    require(config.appProxyProcessNames.all { it.lowercase() in names }) {
        "GATES_PROTECTION_REQUIRES_EXE_PATHS"
    }
    require(config.appDirectProcessPaths.none { direct -> paths.any { it.equals(direct, true) } }) {
        "GATES_PROTECTION_CONFLICT"
    }
    val root = JsonParser.parseString(configJson).asJsonObject
    require(!root.has("gates_protection")) { "GATES_PROTECTION_CONFIG_CONFLICT" }
    root.add("gates_protection", JsonObject().apply {
        addProperty("schema", 1)
        add("protected_executables", JsonArray().apply { paths.forEach(::add) })
    })
    return root.toString()
}

private fun isWindowsProtectionExe(path: String): Boolean {
    if (!Regex("^[A-Za-z]:\\\\").containsMatchIn(path) || !path.endsWith(".exe", true)) return false
    if (path.drop(2).any { it.isISOControl() || it in "<>:\"|?*/" }) return false
    return path.drop(3).split('\\').all {
        it.isNotEmpty() && it !in setOf(".", "..") && !it.endsWith('.') && !it.endsWith(' ')
    }
}
