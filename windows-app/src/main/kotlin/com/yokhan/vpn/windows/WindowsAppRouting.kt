package com.yokhan.gates.windows

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.nio.file.InvalidPathException

internal data class WindowsAppRoutingTarget(val processName: String = "", val processPath: String = "") {
    val label: String get() = processPath.ifBlank { processName }
}

internal fun parseWindowsAppRoutingTarget(raw: String): WindowsAppRoutingTarget? {
    val value = raw.trim().trim('"')
    if (!value.endsWith(".exe", ignoreCase = true)) return null
    val file = File(value)
    if (!value.contains('/') && !value.contains('\\')) return WindowsAppRoutingTarget(processName = value)
    if (!file.isAbsolute) return null
    return try {
        WindowsAppRoutingTarget(processPath = file.toPath().normalize().toString())
    } catch (_: InvalidPathException) {
        null
    }
}

internal fun updateWindowsAppRouting(
    config: WindowsAppConfig,
    target: WindowsAppRoutingTarget,
    proxy: Boolean,
    remove: Boolean,
): WindowsAppConfig {
    fun without(values: List<String>, value: String) = values.filterNot { it.equals(value, ignoreCase = true) }
    fun selected(values: List<String>, value: String): List<String> = when {
        value.isBlank() -> values
        remove -> without(values, value)
        else -> without(values, value) + value
    }
    fun opposite(values: List<String>, value: String) = if (remove || value.isBlank()) values else without(values, value)
    return if (proxy) config.copy(
        appProxyProcessNames = selected(config.appProxyProcessNames, target.processName),
        appProxyProcessPaths = selected(config.appProxyProcessPaths, target.processPath),
        appDirectProcessNames = opposite(config.appDirectProcessNames, target.processName),
        appDirectProcessPaths = opposite(config.appDirectProcessPaths, target.processPath),
    ) else config.copy(
        appDirectProcessNames = selected(config.appDirectProcessNames, target.processName),
        appDirectProcessPaths = selected(config.appDirectProcessPaths, target.processPath),
        appProxyProcessNames = opposite(config.appProxyProcessNames, target.processName),
        appProxyProcessPaths = opposite(config.appProxyProcessPaths, target.processPath),
    )
}

internal fun windowsAppProcessRules(config: WindowsAppConfig, proxyOutbound: String): JsonArray = JsonArray().apply {
    fun rule(field: String, values: List<String>, outbound: String) {
        val clean = values.filter(String::isNotBlank)
        if (clean.isEmpty()) return
        add(JsonObject().apply {
            add(field, JsonArray().also { array -> clean.forEach(array::add) })
            addProperty("outbound", outbound)
        })
    }
    // Exact executable choices take precedence over explicit name-wide rules.
    rule("process_path", config.appProxyProcessPaths, proxyOutbound)
    rule("process_path", config.appDirectProcessPaths, "direct")
    rule("process_name", config.appDirectProcessNames, "direct")
    rule("process_name", config.appProxyProcessNames, proxyOutbound)
}
