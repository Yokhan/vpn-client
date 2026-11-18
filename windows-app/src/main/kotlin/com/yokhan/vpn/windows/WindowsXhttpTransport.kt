package com.yokhan.gates.windows

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets

internal data class WindowsXhttpBind(
    val interfaceName: String? = null,
    val ipv4: String? = null,
)

internal data class WindowsXhttpPlan(
    val outboundTag: String,
    val listenPort: Int,
    val serverIp: String,
    val serverIps: List<String>,
    val security: String,
    val xrayConfigJson: String,
)

private val gson = Gson()
private val xhttpTagPattern = Regex("^[A-Za-z0-9._-]{1,64}$")
private val ipv4Pattern = Regex(
    "^(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}$",
)
private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")

internal const val WINDOWS_XHTTP_ROUTE_TAG = "xhttp"
internal const val WINDOWS_XHTTP_EXECUTABLE = "xray.exe"

private const val WINDOWS_XHTTP_BIND_REQUIRED =
    "XHTTP requires a physical interface binding to prevent a routing loop"

/**
 * XHTTP runs a second user-space process whose traffic would otherwise be
 * captured by the sing-box TUN. Binding that process to the physical adapter
 * is a transport invariant, not a user preference, so an active XHTTP route
 * without a concrete interface fails closed instead of risking a loop.
 */
internal fun requireWindowsXhttpBind(
    bind: WindowsXhttpBind?,
    tunInterfaceName: String? = null,
): WindowsXhttpBind {
    val physical = requireNotNull(bind?.takeIf { !it.interfaceName.isNullOrBlank() }) {
        WINDOWS_XHTTP_BIND_REQUIRED
    }
    require(tunInterfaceName.isNullOrBlank() || !physical.interfaceName.equals(tunInterfaceName, ignoreCase = true)) {
        WINDOWS_XHTTP_BIND_REQUIRED
    }
    return physical
}

/**
 * Returns the first VLESS outbound that declares an XHTTP transport.
 *
 * The sing-box profile is used as the carrier for the XHTTP route because
 * sing-box itself cannot execute XHTTP. B3 converts it to a loopback SOCKS
 * outbound plus an embedded Xray configuration; A3/D1 own the final profile
 * schema, so this shape is documented as an integration assumption.
 */
internal fun windowsXhttpOutbound(root: JsonObject): JsonObject? {
    val outbounds = root.getAsJsonArray("outbounds") ?: return null
    return outbounds.mapNotNull { value ->
        value.takeIf { it.isJsonObject }?.asJsonObject
    }.firstOrNull { outbound ->
        outbound.get("type")?.asString == "vless" &&
            outbound.getAsJsonObject("transport")?.get("type")?.asString == "xhttp"
    }
}

internal fun windowsXhttpOutboundTag(root: JsonObject): String? {
    val outbound = windowsXhttpOutbound(root) ?: return null
    val tag = outbound.get("tag")?.asString.orEmpty()
    return tag.takeIf { it == WINDOWS_XHTTP_ROUTE_TAG }
}

internal fun windowsXhttpEnabled(root: JsonObject): Boolean = windowsXhttpOutbound(root) != null

/**
 * Removes an unselected XHTTP outbound before the sing-box document is written.
 * The raw profile keeps the route, so selecting `xhttp` regenerates the config
 * with the sidecar. This avoids starting Xray for profiles that cannot use it
 * and keeps the generated sing-box config free of dead loopback references.
 */
internal fun stripWindowsXhttpOutbound(root: JsonObject): Boolean {
    val outbounds = root.getAsJsonArray("outbounds") ?: return false
    val index = outbounds.indexOfFirst { value ->
        value.takeIf { it.isJsonObject }?.asJsonObject?.let { outbound ->
            outbound.get("type")?.asString == "vless" &&
                outbound.getAsJsonObject("transport")?.get("type")?.asString == "xhttp"
        } == true
    }
    if (index < 0) return false
    val tag = outbounds.get(index).asJsonObject.get("tag")?.asString.orEmpty()
    outbounds.remove(index)
    root.getAsJsonObject("route")?.let { route ->
        if (route.get("final")?.asString == tag) route.addProperty("final", "direct")
        route.getAsJsonArray("rules")?.forEach { element ->
            val rule = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (rule.get("outbound")?.asString == tag) rule.addProperty("outbound", "direct")
        }
    }
    outbounds.forEach { element ->
        val outbound = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
        val candidates = outbound.getAsJsonArray("outbounds") ?: return@forEach
        for (candidateIndex in candidates.size() - 1 downTo 0) {
            if (candidates.get(candidateIndex).takeIf { it.isJsonPrimitive }?.asString == tag) {
                candidates.remove(candidateIndex)
            }
        }
    }
    return true
}

/**
 * Replaces the XHTTP outbound with a loopback SOCKS outbound and returns the
 * plan that the service needs in order to start and supervise Xray.
 *
 * The entry address is resolved here, before the TUN exists, and the resulting
 * IPs are excluded from the TUN capture. This avoids the bootstrapping loop
 * where Xray cannot resolve its entry because the entry resolution itself
 * needs the tunnel.
 */
internal fun applyWindowsXhttpTransport(
    root: JsonObject,
    config: WindowsAppConfig,
    bind: WindowsXhttpBind?,
    resolve: (String) -> List<String> = ::resolveWindowsIpv4,
): WindowsXhttpPlan? {
    val outbounds = root.getAsJsonArray("outbounds") ?: return null
    val index = outbounds.indexOfFirst { value ->
        value.takeIf { it.isJsonObject }?.asJsonObject?.let { outbound ->
            outbound.get("type")?.asString == "vless" &&
                outbound.getAsJsonObject("transport")?.get("type")?.asString == "xhttp"
        } == true
    }
    if (index < 0) return null
    val physicalBind = requireWindowsXhttpBind(bind, config.tunInterfaceName)
    val outbound = outbounds.get(index).asJsonObject
    val tag = outbound.get("tag")?.asString.orEmpty()
    require(tag == WINDOWS_XHTTP_ROUTE_TAG && xhttpTagPattern.matches(tag)) {
        "XHTTP profile must use the '$WINDOWS_XHTTP_ROUTE_TAG' route id"
    }
    val server = outbound.get("server")?.asString?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("XHTTP profile is missing its entry address")
    val serverPort = outbound.get("server_port")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 0
    require(serverPort in 1..65535) { "XHTTP profile has an invalid entry port" }
    val uuid = outbound.get("uuid")?.asString.orEmpty()
    require(uuid.isNotBlank()) { "XHTTP profile is missing its credential" }
    val serverIps = if (isWindowsIpv4Literal(server)) listOf(server) else resolve(server).distinct().take(4)
    require(serverIps.isNotEmpty()) { "XHTTP entry address could not be resolved" }
    val xrayConfig = buildWindowsXrayConfig(outbound, serverIps.first(), config.xhttpSidecarPort, physicalBind)
        ?: throw IllegalArgumentException("XHTTP profile uses an unsupported TLS configuration")
    val security = xrayConfig.getAsJsonArray("outbounds")
        .first().asJsonObject.getAsJsonObject("streamSettings").get("security").asString

    outbounds.set(index, JsonObject().apply {
        addProperty("type", "socks")
        addProperty("tag", tag)
        addProperty("server", "127.0.0.1")
        addProperty("server_port", config.xhttpSidecarPort)
        addProperty("version", "5")
    })
    excludeFromTun(root, serverIps)
    return WindowsXhttpPlan(
        outboundTag = tag,
        listenPort = config.xhttpSidecarPort,
        serverIp = serverIps.first(),
        serverIps = serverIps,
        security = security,
        xrayConfigJson = gson.toJson(xrayConfig),
    )
}

/**
 * Builds the minimal Xray client configuration: one loopback SOCKS inbound and
 * one VLESS/XHTTP outbound. No DNS, routing, stats or TUN sections are emitted,
 * so Xray cannot become a second TUN or DNS stack.
 */
internal fun buildWindowsXrayConfig(
    outbound: JsonObject,
    serverIp: String,
    listenPort: Int,
    bind: WindowsXhttpBind?,
): JsonObject? {
    val tls = outbound.getAsJsonObject("tls") ?: return null
    val reality = tls.getAsJsonObject("reality")?.takeIf { it.boolOrNull("enabled") == true }
    val tlsEnabled = tls.boolOrNull("enabled") == true
    val security = when {
        reality != null -> "reality"
        tlsEnabled -> "tls"
        else -> return null
    }
    val serverName = tls.stringOrNull("server_name")?.takeIf { it.isNotBlank() } ?: return null
    val utls = tls.getAsJsonObject("utls")
    val fingerprint = utls?.stringOrNull("fingerprint")?.takeIf { it.isNotBlank() } ?: "chrome"
    val transport = outbound.getAsJsonObject("transport") ?: return null
    val path = transport.stringOrNull("path")?.takeIf { it.startsWith("/") } ?: return null
    val host = transport.stringOrNull("host")?.takeIf { it.isNotBlank() } ?: serverName
    val mode = transport.stringOrNull("mode")?.takeIf { it.isNotBlank() } ?: "auto"
    val stream = JsonObject().apply {
        addProperty("network", "xhttp")
        addProperty("security", security)
        if (security == "reality") {
            val realitySettings = reality ?: return null
            val publicKey = realitySettings.stringOrNull("public_key")?.takeIf { it.isNotBlank() } ?: return null
            val shortId = realitySettings.stringOrNull("short_id").orEmpty()
            add("realitySettings", JsonObject().apply {
                addProperty("serverName", serverName)
                addProperty("fingerprint", fingerprint)
                addProperty("publicKey", publicKey)
                addProperty("shortId", shortId)
            })
        } else {
            add("tlsSettings", JsonObject().apply {
                addProperty("serverName", serverName)
                addProperty("fingerprint", fingerprint)
                val alpn = tls.getAsJsonArray("alpn")
                if (alpn != null && alpn.size() > 0) {
                    add("alpn", JsonArray().also { target -> alpn.forEach(target::add) })
                }
            })
        }
        add("xhttpSettings", JsonObject().apply {
            addProperty("path", path)
            addProperty("host", host)
            addProperty("mode", mode)
            val extra = transport.getAsJsonObject("extra")?.deepCopy() ?: JsonObject()
            if (!extra.has("xmux")) {
                extra.add("xmux", JsonObject().apply {
                    addProperty("maxConnections", "4")
                    addProperty("hKeepAlivePeriod", 30)
                })
            }
            add("extra", extra)
        })
        if (bind?.interfaceName?.isNotBlank() == true) {
            add("sockopt", JsonObject().apply { addProperty("interface", bind.interfaceName) })
        }
    }
    val settings = JsonObject().apply {
        add("vnext", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("address", serverIp)
                addProperty("port", outbound.intOrNull("server_port") ?: return null)
                add("users", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("id", outbound.stringOrNull("uuid").orEmpty())
                        addProperty("encryption", "none")
                    })
                })
            })
        })
    }
    return JsonObject().apply {
        add("log", JsonObject().apply { addProperty("loglevel", "warning") })
        add("inbounds", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("protocol", "socks")
                addProperty("listen", "127.0.0.1")
                addProperty("port", listenPort)
                add("settings", JsonObject().apply {
                    addProperty("auth", "noauth")
                    addProperty("udp", true)
                })
            })
        })
        add("outbounds", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("protocol", "vless")
                add("settings", settings)
                add("streamSettings", stream)
            })
        })
    }
}

internal fun windowsXhttpSpecJson(
    plan: WindowsXhttpPlan,
    required: Boolean,
    executableSha256: String,
): String {
    require(sha256Pattern.matches(executableSha256)) { "XHTTP runtime is not pinned" }
    return gson.toJson(JsonObject().apply {
        addProperty("schema", 1)
        addProperty("outbound_tag", plan.outboundTag)
        addProperty("executable", WINDOWS_XHTTP_EXECUTABLE)
        addProperty("executable_sha256", executableSha256.lowercase())
        addProperty("listen_port", plan.listenPort)
        addProperty("required", required)
        add("xray_config", JsonParser.parseString(plan.xrayConfigJson))
    })
}

internal fun readInstalledXrayPin(programFiles: String? = System.getenv("ProgramFiles")): String? {
    if (programFiles.isNullOrBlank()) return null
    val file = File(programFiles, "GATES/app/resources/yokhan-runtime/windows/xray.sha256")
    if (!file.isFile) return null
    return runCatching { file.readText(StandardCharsets.US_ASCII).trim() }
        .getOrNull()
        ?.takeIf(sha256Pattern::matches)
        ?.lowercase()
}

internal fun resolveWindowsIpv4(host: String): List<String> = runCatching {
    InetAddress.getAllByName(host)
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
}.getOrDefault(emptyList())

internal fun isWindowsIpv4Literal(value: String): Boolean = ipv4Pattern.matches(value)

/**
 * Picks a loopback port for the sidecar. The preferred port is used when free;
 * otherwise a small range is probed so a stale listener does not block the
 * transport. This is generation-time allocation: the chosen port is written to
 * both the sing-box SOCKS outbound and the sidecar spec, so no runtime config
 * rewrite is needed.
 */
internal fun chooseWindowsXhttpPort(
    preferred: Int,
    isFree: (Int) -> Boolean = ::isWindowsLoopbackPortFree,
): Int? {
    if (preferred in 1024..65535 && preferred != 38417 && isFree(preferred)) return preferred
    return WINDOWS_XHTTP_PORT_RANGE.firstOrNull(isFree)
}

private fun isWindowsLoopbackPortFree(port: Int): Boolean = try {
    java.net.ServerSocket().use { socket ->
        socket.bind(java.net.InetSocketAddress("127.0.0.1", port))
    }
    true
} catch (_: Exception) {
    false
}

private val WINDOWS_XHTTP_PORT_RANGE = (2095..2105).toList()

private fun excludeFromTun(root: JsonObject, addresses: List<String>) {
    val tun = root.getAsJsonArray("inbounds")
        ?.mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }
        ?.firstOrNull { it.get("type")?.asString == "tun" }
        ?: return
    val exclude = tun.getAsJsonArray("route_exclude_address") ?: JsonArray().also {
        tun.add("route_exclude_address", it)
    }
    val existing = exclude.mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString }.toSet()
    addresses.forEach { address ->
        val prefix = when {
            address.contains('/') -> address
            ':' in address -> "$address/128"
            else -> "$address/32"
        }
        if (prefix !in existing) exclude.add(prefix)
    }
}

private fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.boolOrNull(name: String): Boolean? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

private fun JsonObject.intOrNull(name: String): Int? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt