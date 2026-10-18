package com.yokhan.gates.windows

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File

internal data class WindowsAppRoutingTarget(val processName: String = "", val processPath: String = "") {
    val label: String get() = processPath.ifBlank { processName }
}

internal fun parseWindowsAppRoutingTarget(raw: String): WindowsAppRoutingTarget? {
    return try {
        val input = raw.trim()
        if (input.isEmpty()) return null
        val quoted = input.startsWith('"') || input.endsWith('"')
        if (quoted && !(input.startsWith('"') && input.endsWith('"') && input.length > 1)) return null
        val value = if (quoted) input.substring(1, input.length - 1) else input
        if (value.any { it.isISOControl() || it == '"' } || !value.endsWith(".exe", ignoreCase = true)) return null

        val hasPath = value.contains('/') || value.contains('\\')
        if (!hasPath) {
            if (value.any { it in "<>:\\|?*" } || value.endsWith('.') || value.endsWith(' ')) return null
            WindowsAppRoutingTarget(processName = value)
        } else {
            val file = File(value)
            if (!file.isAbsolute || !isFullyQualifiedWindowsPath(value) || hasInvalidWindowsPathSegment(value)) return null
            WindowsAppRoutingTarget(processPath = file.toPath().normalize().toString())
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun isFullyQualifiedWindowsPath(value: String): Boolean {
    if (File.separatorChar != '\\') return true
    val driveRooted = value.length >= 3 && value[0].isLetter() && value[1] == ':' && value[2] in "/\\"
    val unc = (value.startsWith("\\\\") || value.startsWith("//")) &&
        value.split('/', '\\').count(String::isNotEmpty) >= 3
    return driveRooted || unc
}

private fun hasInvalidWindowsPathSegment(value: String): Boolean {
    if (File.separatorChar != '\\') return false
    val segments = value.split('/', '\\')
    return segments.withIndex().any { (index, segment) ->
        val isDrive = index == 0 && segment.length == 2 && segment[1] == ':'
        (segment.any { it in "<>:\\|?*" } && !(isDrive && segment[0].isLetter())) ||
            segment.endsWith('.') || segment.endsWith(' ')
    }
}

internal fun windowsProcessRoutingValue(name: String, path: String): String =
    parseWindowsAppRoutingTarget(path)?.label ?: name

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
