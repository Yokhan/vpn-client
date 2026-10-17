package com.yokhan.gates.windows

import com.google.gson.JsonParser
import com.google.gson.JsonArray
import java.net.URI

private val WINDOWS_PROXY_ROUTE_IDS = setOf("bridge", "relay", "naive", "hy2", "nl-tcp")

internal fun isWindowsProxyRouteId(value: String): Boolean =
    value.trim().lowercase() in WINDOWS_PROXY_ROUTE_IDS

internal fun windowsAutoRouteCandidates(
    routeOrder: List<String>,
    availableProxyRoutes: List<String>,
    includeUnlistedRoutes: Boolean,
): List<String> {
    val available = availableProxyRoutes.filter { it != "direct" }.distinct()
    val ordered = routeOrder.filter { it != "direct" && it in available }
    return if (includeUnlistedRoutes) ordered + available.filterNot { it in ordered } else ordered
}

internal fun validWindowsProxyRouteTags(outbounds: JsonArray?): List<String> {
    if (outbounds == null) return emptyList()
    return outbounds.mapNotNull { value ->
        runCatching {
            val outbound = value.asJsonObject
            val type = outbound.get("type")?.asString
            val tag = outbound.get("tag")?.asString.orEmpty()
            val isProxyType = type in setOf("vless", "naive", "hysteria2")
            val hasAddress = !outbound.get("server")?.asString.isNullOrBlank()
            val hasValidPort = outbound.get("server_port")?.asInt in 1..65535
            tag.takeIf { isProxyType && isWindowsProxyRouteId(it) && hasAddress && hasValidPort }
        }.getOrNull()
    }.distinct()
}

internal fun subscriptionJsonEndpoint(input: String): String {
    val uri = URI(input.trim())
    require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())
    require(uri.rawUserInfo == null && uri.rawFragment == null)
    require(!input.contains("REPLACE_WITH_PERSONAL_TOKEN"))
    val path = uri.rawPath.orEmpty().trimEnd('/')
    val endpointPath = if (Regex("^/(?:gates/)?api/sub/[^/]+$").matches(path)) {
        "$path/singbox"
    } else {
        path
    }
    return buildString {
        append("https://")
        append(uri.rawAuthority)
        append(endpointPath)
        uri.rawQuery?.let { append('?'); append(it) }
    }
}

internal fun hasImportedTunnelProfile(json: String): Boolean = runCatching {
    val root = JsonParser.parseString(json).asJsonObject
    val outbounds = root.getAsJsonArray("outbounds") ?: return false
    outbounds.any { value ->
        val outbound = value.asJsonObject
        outbound.get("type")?.asString in setOf("vless", "naive", "hysteria2") &&
            !outbound.get("tag")?.asString.isNullOrBlank() &&
            !outbound.get("server")?.asString.isNullOrBlank() &&
            outbound.get("server_port")?.asInt in 1..65535
    }
}.getOrDefault(false)

internal fun redactedSubscriptionUrl(url: String): String = if (url.isBlank()) "" else "<redacted>"

internal fun windowsImportedProfileConfig(config: WindowsAppConfig, source: String, json: String): WindowsAppConfig {
    val outbounds = JsonParser.parseString(json).asJsonObject.getAsJsonArray("outbounds")
    val proxyTags = outbounds.mapNotNull { value ->
        val outbound = value.asJsonObject
        outbound.get("tag")?.asString?.takeIf {
            outbound.get("type")?.asString in setOf("vless", "hysteria2", "naive") && it.isNotBlank()
        }
    }.distinct()
    val tags = outbounds.mapNotNull { it.asJsonObject.get("tag")?.asString }.toSet()
    require(config.activeRoute == "auto" || config.activeRoute in tags) { "Selected route is missing from profile" }
    return config.copy(profileSource = source,
        routeOrder = if (source == "manual" && "auto" !in tags) proxyTags else config.routeOrder)
}

internal fun redactedCliArguments(command: String, arguments: List<String>): List<String> =
    if (command == "import-sub" && arguments.isNotEmpty()) listOf("<redacted>") else arguments
