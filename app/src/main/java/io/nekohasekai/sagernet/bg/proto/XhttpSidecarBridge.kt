package io.nekohasekai.sagernet.bg.proto

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.xray.XrayRouteSpec
import io.nekohasekai.sagernet.fmt.xray.XrayStreamSecurity

/**
 * Rewrites a raw sing-box profile so an XHTTP route can run through the Xray sidecar.
 *
 * The child never dials the entry itself: sing-box keeps a loopback `direct` inbound whose
 * [override_address]/[override_port] point at the real entry, and Xray's VLESS outbound targets
 * that loopback port. This is the Android loop-avoidance contract because the app's own sockets
 * are inside the VPN.
 */
internal object XhttpSidecarBridge {

    const val ROUTE_TAG = "xhttp"
    const val MAPPING_TAG = "xhttp-entry"
    const val LOOPBACK = "127.0.0.1"
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    data class Plan(
        val configJson: String,
        val spec: XrayRouteSpec,
        val entryHost: String,
        val entryPort: Int,
    )

    /** Entry host of the active xhttp outbound, used to exclude it from the VPN routes. */
    fun entryHost(rawConfig: String): String? {
        val root = runCatching { JsonParser.parseString(rawConfig).asJsonObject }.getOrNull() ?: return null
        val outbounds = root.getAsJsonArray("outbounds") ?: return null
        val outbound = outbounds.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            .firstOrNull(::isXhttpOutbound) ?: return null
        return outbound.get("server")?.asString?.takeIf { it.isNotBlank() }
    }

    fun build(rawConfig: String, sidecarPort: Int, mappingPort: Int): Plan? {
        val root = runCatching { JsonParser.parseString(rawConfig).asJsonObject }.getOrNull() ?: return null
        val outbounds = root.getAsJsonArray("outbounds") ?: return null
        val index = outbounds.indexOfFirst { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.let(::isXhttpOutbound) == true
        }
        if (index < 0) return null
        val outbound = outbounds.get(index).asJsonObject
        val tag = outbound.get("tag")?.asString ?: return null
        if (tag != ROUTE_TAG) return null
        val server = outbound.get("server")?.asString?.takeIf { it.isNotBlank() } ?: return null
        val serverPort = outbound.get("server_port")?.asInt ?: return null
        if (serverPort !in 1..65535) return null
        val uuid = outbound.get("uuid")?.asString?.takeIf { it.isNotBlank() } ?: return null
        val tls = outbound.getAsJsonObject("tls") ?: return null
        if (tls.get("enabled")?.asBoolean != true) return null
        val serverName = tls.get("server_name")?.asString?.takeIf { it.isNotBlank() } ?: return null
        val reality = tls.getAsJsonObject("reality")
        if (reality?.get("enabled")?.asBoolean != true) return null
        val publicKey = reality.get("public_key")?.asString?.takeIf { it.isNotBlank() } ?: return null
        val shortId = reality.get("short_id")?.asString.orEmpty()
        val transport = outbound.getAsJsonObject("transport") ?: return null
        if (transport.get("type")?.asString != "xhttp") return null
        val path = transport.get("path")?.asString?.takeIf { it.startsWith("/") } ?: return null
        val mode = transport.get("mode")?.asString?.takeIf { it.isNotBlank() } ?: "auto"
        val fingerprint = tls.getAsJsonObject("utls")?.get("fingerprint")?.asString
            ?.takeIf { it.isNotBlank() } ?: "chrome"

        val spec = XrayRouteSpec(
            routeId = tag,
            sidecarPort = sidecarPort,
            mappingPort = mappingPort,
            entryHost = server,
            entryPort = serverPort,
            uuid = uuid,
            serverName = serverName,
            realityPublicKey = publicKey,
            realityShortId = shortId,
            fingerprint = fingerprint,
            path = path,
            mode = mode,
            security = XrayStreamSecurity.Reality,
        )

        outbounds.set(index, JsonObject().apply {
            addProperty("type", "socks")
            addProperty("tag", tag)
            addProperty("server", LOOPBACK)
            addProperty("server_port", sidecarPort)
            addProperty("version", "5")
        })

        // The child dials the real entry. That flow is captured by the TUN, so sing-box
        // must route the entry IP to the protected direct outbound instead of the
        // `xhttp` route that points back at the sidecar.
        if (IPV4.matches(server)) {
            val route = root.getAsJsonObject("route") ?: JsonObject().also { root.add("route", it) }
            val rules = route.getAsJsonArray("rules") ?: JsonArray().also { route.add("rules", it) }
            val direct = JsonObject().apply {
                add("ip_cidr", JsonArray().apply { add("$server/32") })
                addProperty("outbound", "direct")
            }
            val reordered = JsonArray()
            reordered.add(direct)
            rules.forEach(reordered::add)
            route.add("rules", reordered)
        }

        return Plan(root.toString(), spec, server, serverPort)
    }

    private fun isXhttpOutbound(outbound: JsonObject): Boolean =
        outbound.get("type")?.asString == "vless" &&
            outbound.getAsJsonObject("transport")?.get("type")?.asString == "xhttp"
}
