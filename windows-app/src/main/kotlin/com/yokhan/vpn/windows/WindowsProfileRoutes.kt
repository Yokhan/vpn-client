package com.yokhan.gates.windows

import com.google.gson.JsonParser

internal data class WindowsProfileRoute(
    val tag: String,
    val label: String,
    val protocol: String,
    val server: String,
)

internal fun windowsProfileRoutes(json: String): List<WindowsProfileRoute> = runCatching {
    val outbounds = JsonParser.parseString(json).asJsonObject.getAsJsonArray("outbounds")
        ?: return emptyList()
    val valid = validWindowsProxyRouteTags(outbounds).toSet()
    outbounds.mapNotNull { value ->
        val item = value.asJsonObject
        val tag = item.get("tag")?.asString.orEmpty()
        if (tag !in valid) return@mapNotNull null
        WindowsProfileRoute(
            tag = tag,
            label = when (tag) {
                "nl-tcp" -> "NL VLESS"
                "se-hy2" -> "SE Stockholm"
                "hy2" -> "Hysteria2"
                "bridge" -> "Bridge"
                "relay" -> "Relay"
                "naive" -> "Naive"
                else -> tag
            },
            protocol = when (item.get("type")?.asString) {
                "vless" -> "VLESS / TCP"
                "hysteria2" -> "HY2 / UDP"
                "naive" -> "HTTPS"
                else -> ""
            },
            server = item.get("server")?.asString.orEmpty(),
        )
    }.distinctBy { it.tag }
}.getOrDefault(emptyList())

internal fun windowsRouteTagForProfileId(id: Long, routes: List<WindowsProfileRoute>): String? =
    when {
        id == 1L -> "auto"
        id in 2L..(routes.size.toLong() + 1L) -> routes[(id - 2).toInt()].tag
        else -> null
    }
