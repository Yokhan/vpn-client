package com.yokhan.gates.windows.service

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Immutable description of the local Xray XHTTP sidecar that a validated
 * sing-box configuration depends on.
 *
 * The Xray JSON is intentionally kept as canonical text so persistence and
 * configuration digests round-trip exactly. [toString] never includes it.
 */
data class XhttpSidecarSpec(
    val outboundTag: String,
    val executableName: String,
    val executableSha256: String,
    val listenPort: Int,
    val required: Boolean,
    val xrayConfigJson: String,
) {
    override fun toString(): String =
        "XhttpSidecarSpec(outboundTag=$outboundTag, listenPort=$listenPort, required=$required)"

    fun toJsonObject(): JsonObject = JsonObject().apply {
        addProperty("schema", XhttpSidecarSpecParser.SCHEMA)
        addProperty("outbound_tag", outboundTag)
        addProperty("executable", executableName)
        addProperty("executable_sha256", executableSha256)
        addProperty("listen_port", listenPort)
        addProperty("required", required)
        add("xray_config", XhttpSidecarSpecParser.parseCanonical(xrayConfigJson))
    }
}

sealed interface XhttpSidecarParseResult {
    data class Accepted(val spec: XhttpSidecarSpec?) : XhttpSidecarParseResult
    data class Rejected(val error: ConfigValidationError) : XhttpSidecarParseResult
}

/**
 * Validates the `gates_xhttp` transport block that the Windows app embeds in a
 * service-bound sing-box configuration.
 *
 * The sidecar is deliberately narrow: one loopback SOCKS inbound, one VLESS
 * outbound using XHTTP, and no DNS, routing, stats or file-producing sections.
 * This keeps sing-box as the only TUN and DNS owner.
 */
object XhttpSidecarSpecParser {
    const val SCHEMA = 1
    const val EXECUTABLE_NAME = "xray.exe"
    const val MAX_XRAY_CONFIG_BYTES = 64 * 1024
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535
    const val CONTROL_PORT = 38417

    private const val MAX_EXTRA_BYTES = 8 * 1024
    private const val MAX_PATH_LENGTH = 256

    private val gson = Gson()
    private val specKeys = setOf(
        "schema",
        "outbound_tag",
        "executable",
        "executable_sha256",
        "listen_port",
        "required",
        "xray_config",
    )
    private val xrayTopLevelKeys = setOf("log", "inbounds", "outbounds")
    private val streamSettingKeys = setOf(
        "network",
        "security",
        "tlsSettings",
        "realitySettings",
        "xhttpSettings",
        "sockopt",
    )
    private val sockoptKeys = setOf(
        "interface",
        "tcpFastOpen",
        "tcpKeepAliveIdle",
        "tcpKeepAliveInterval",
        "domainStrategy",
    )
    private val xhttpModeValues = setOf("auto", "packet-up", "stream-up", "stream-one")
    private val logLevels = setOf("warning", "error", "none")
    private val fingerprints = setOf(
        "chrome",
        "firefox",
        "safari",
        "ios",
        "android",
        "edge",
        "360",
        "qq",
        "random",
        "randomized",
    )
    private val outboundTagPattern = Regex("^[A-Za-z0-9._-]{1,64}$")
    private val executableNamePattern = Regex("^[A-Za-z0-9._-]{1,64}\\.exe$")
    private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")
    private val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val ipv4Pattern = Regex(
        "^(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}$",
    )
    private val ipv6Chars = Regex("^[0-9A-Fa-f:.]+$")
    private val hostnamePattern = Regex(
        "^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$",
    )
    private val publicKeyPattern = Regex("^[A-Za-z0-9_-]{16,64}$")
    private val shortIdPattern = Regex("^[0-9a-fA-F]{0,16}$")

    fun parse(root: JsonObject): XhttpSidecarParseResult = try {
        parseUnchecked(root)
    } catch (_: Exception) {
        invalid()
    }

    private fun parseUnchecked(root: JsonObject): XhttpSidecarParseResult {
        if (!root.has("gates_xhttp")) return XhttpSidecarParseResult.Accepted(null)
        val sidecar = root.get("gates_xhttp")
        if (sidecar == null || !sidecar.isJsonObject) return invalid()
        val spec = sidecar.asJsonObject
        if (spec.keySet() != specKeys) return invalid()
        if (!isInt(spec.get("schema"), SCHEMA)) return invalid()
        val outboundTag = string(spec.get("outbound_tag"))?.takeIf(outboundTagPattern::matches) ?: return invalid()
        val executableName = string(spec.get("executable"))?.takeIf(executableNamePattern::matches) ?: return invalid()
        if (!executableName.equals(EXECUTABLE_NAME, ignoreCase = true)) return invalid()
        val sha256 = string(spec.get("executable_sha256"))?.takeIf(sha256Pattern::matches) ?: return invalid()
        val listenPort = int(spec.get("listen_port")) ?: return invalid()
        if (listenPort !in MIN_PORT..MAX_PORT || listenPort == CONTROL_PORT) return invalid()
        val required = boolean(spec.get("required")) ?: return invalid()
        val xrayConfig = spec.get("xray_config")?.takeIf { it.isJsonObject }?.asJsonObject ?: return invalid()

        val outbounds = root.getAsJsonArray("outbounds") ?: return invalid()
        val inboundPorts = root.getAsJsonArray("inbounds")
            ?.mapNotNull { element -> element.takeIf { element -> element.isJsonObject }?.asJsonObject?.get("listen_port")?.let { value -> int(value) } }
            ?.toSet()
            ?: emptySet()
        if (listenPort in inboundPorts) return invalid()

        val sidecarOutbound = outbounds.firstOrNull { outbound ->
            string(outbound.asJsonObjectOrNull()?.get("tag")) == outboundTag
        }?.asJsonObjectOrNull() ?: return invalid()
        if (!isLocalSocksOutbound(sidecarOutbound, listenPort)) return invalid()
        val socksOutbounds = outbounds.count { outbound ->
            string(outbound.asJsonObjectOrNull()?.get("type")) == "socks"
        }
        if (socksOutbounds != 1) return invalid()

        val route = root.getAsJsonObject("route")
        val finalRoute = string(route?.get("final"))
        val scopedBootstrapRequired = finalRoute == "block" && isReferenced(route, outbounds, outboundTag)
        if (required && finalRoute != outboundTag && !scopedBootstrapRequired) return invalid()
        if (!required && !isReferenced(route, outbounds, outboundTag)) return invalid()

        val tunInterfaceNames = root.getAsJsonArray("inbounds")
            ?.mapNotNull { element -> element.takeIf { it.isJsonObject }?.asJsonObject }
            ?.filter { inbound -> string(inbound.get("type")) == "tun" }
            ?.mapNotNull { inbound -> string(inbound.get("interface_name"))?.takeIf(String::isNotBlank) }
            ?.toSet()
            ?: emptySet()
        val error = validateXrayConfig(xrayConfig, listenPort, tunInterfaceNames)
        if (error != null) return XhttpSidecarParseResult.Rejected(error)

        val canonical = gson.toJson(xrayConfig)
        if (canonical.toByteArray(Charsets.UTF_8).size > MAX_XRAY_CONFIG_BYTES) return invalid()
        return XhttpSidecarParseResult.Accepted(
            XhttpSidecarSpec(
                outboundTag = outboundTag,
                executableName = executableName.lowercase(),
                executableSha256 = sha256.lowercase(),
                listenPort = listenPort,
                required = required,
                xrayConfigJson = canonical,
            ),
        )
    }

    fun parseCanonical(json: String): JsonObject =
        com.google.gson.JsonParser.parseString(json).asJsonObject

    private fun validateXrayConfig(
        config: JsonObject,
        listenPort: Int,
        tunInterfaceNames: Set<String>,
    ): ConfigValidationError? {
        if (!config.keySet().all { it in xrayTopLevelKeys }) return invalidError()
        validateLog(config.getAsJsonObject("log"))?.let { return it }

        val inbounds = config.getAsJsonArray("inbounds") ?: return invalidError()
        if (inbounds.size() != 1) return invalidError()
        val inbound = inbounds.firstOrNull()?.takeIf { element -> element.isJsonObject }?.asJsonObject ?: return invalidError()
        if (string(inbound.get("protocol")) != "socks") return invalidError()
        if (string(inbound.get("listen")) != "127.0.0.1") return invalidError()
        if (int(inbound.get("port")) != listenPort) return invalidError()
        if (inbound.has("tag") && string(inbound.get("tag")).isNullOrBlank()) return invalidError()
        val settings = inbound.getAsJsonObject("settings") ?: return invalidError()
        if (!settings.keySet().all { it in setOf("auth", "udp", "userLevel") }) return invalidError()
        if (string(settings.get("auth")) != "noauth") return invalidError()
        if (boolean(settings.get("udp")) == null) return invalidError()
        if (settings.has("userLevel") && (int(settings.get("userLevel")) ?: -1) < 0) return invalidError()

        val outbounds = config.getAsJsonArray("outbounds") ?: return invalidError()
        if (outbounds.size() != 1) return invalidError()
        val outbound = outbounds.firstOrNull()?.takeIf { element -> element.isJsonObject }?.asJsonObject ?: return invalidError()
        if (string(outbound.get("protocol")) != "vless") return invalidError()
        if (outbound.has("mux")) return invalidError()
        validateVlessSettings(outbound.getAsJsonObject("settings"))?.let { return it }
        validateStreamSettings(outbound.getAsJsonObject("streamSettings"), tunInterfaceNames)?.let { return it }
        return null
    }

    private fun validateLog(log: JsonObject?): ConfigValidationError? {
        if (log == null) return null
        if (!log.keySet().all { it == "loglevel" }) return invalidError()
        val level = string(log.get("loglevel")) ?: return invalidError()
        if (level !in logLevels) return invalidError()
        return null
    }

    private fun validateVlessSettings(settings: JsonObject?): ConfigValidationError? {
        if (settings == null || !settings.keySet().all { it in setOf("vnext") }) return invalidError()
        val vnext = settings.getAsJsonArray("vnext") ?: return invalidError()
        if (vnext.size() != 1) return invalidError()
        val server = vnext.firstOrNull()?.takeIf { element -> element.isJsonObject }?.asJsonObject ?: return invalidError()
        if (!server.keySet().all { it in setOf("address", "port", "users") }) return invalidError()
        val address = string(server.get("address")) ?: return invalidError()
        if (!isIpLiteral(address)) return invalidError()
        val serverPort = int(server.get("port")) ?: return invalidError()
        if (serverPort !in 1..MAX_PORT) return invalidError()
        val users = server.getAsJsonArray("users") ?: return invalidError()
        if (users.size() != 1) return invalidError()
        val user = users.firstOrNull()?.takeIf { element -> element.isJsonObject }?.asJsonObject ?: return invalidError()
        if (!user.keySet().all { it in setOf("id", "encryption", "flow") }) return invalidError()
        val id = string(user.get("id")) ?: return invalidError()
        if (!uuidPattern.matches(id)) return invalidError()
        if (string(user.get("encryption")) != "none") return invalidError()
        // XHTTP does not support Vision/flow; accepting it would produce a
        // configuration that Xray rejects at runtime.
        val flow = string(user.get("flow")).orEmpty()
        if (flow.isNotEmpty()) return invalidError()
        return null
    }

    private fun validateStreamSettings(
        stream: JsonObject?,
        tunInterfaceNames: Set<String>,
    ): ConfigValidationError? {
        if (stream == null || !stream.keySet().all { it in streamSettingKeys }) return invalidError()
        if (string(stream.get("network")) != "xhttp") return invalidError()
        val security = string(stream.get("security")) ?: return invalidError()
        when (security) {
            "reality" -> validateReality(stream.getAsJsonObject("realitySettings"))?.let { return it }
            "tls" -> validateTls(stream.getAsJsonObject("tlsSettings"))?.let { return it }
            else -> return invalidError()
        }
        if (security == "reality" && stream.has("tlsSettings")) return invalidError()
        if (security == "tls" && stream.has("realitySettings")) return invalidError()
        validateXhttp(stream.getAsJsonObject("xhttpSettings"))?.let { return it }
        val sockopt = stream.getAsJsonObject("sockopt")
        val interfaceName = string(sockopt?.get("interface"))
        if (interfaceName.isNullOrBlank() || tunInterfaceNames.any { it.equals(interfaceName, ignoreCase = true) }) {
            return ConfigValidationError(
                ConfigErrorCode.XHTTP_PHYSICAL_BIND_REQUIRED,
                "XHTTP sidecar requires a physical interface binding",
            )
        }
        validateSockopt(sockopt)?.let { return it }
        return null
    }

    private fun validateReality(reality: JsonObject?): ConfigValidationError? {
        if (reality == null) return invalidError()
        if (!reality.keySet().all { it in setOf("serverName", "fingerprint", "publicKey", "shortId", "spiderX") }) {
            return invalidError()
        }
        val serverName = string(reality.get("serverName")) ?: return invalidError()
        if (!isHostname(serverName)) return invalidError()
        val fingerprint = string(reality.get("fingerprint")) ?: return invalidError()
        if (fingerprint !in fingerprints) return invalidError()
        val publicKey = string(reality.get("publicKey")) ?: return invalidError()
        if (!publicKeyPattern.matches(publicKey)) return invalidError()
        val shortId = string(reality.get("shortId")) ?: return invalidError()
        if (!shortIdPattern.matches(shortId)) return invalidError()
        val spiderX = string(reality.get("spiderX")).orEmpty()
        if (spiderX.isNotEmpty() && (spiderX.length > 256 || spiderX.any { it.code < 0x20 })) return invalidError()
        return null
    }

    private fun validateTls(tls: JsonObject?): ConfigValidationError? {
        if (tls == null) return invalidError()
        if (!tls.keySet().all { it in setOf("serverName", "fingerprint", "allowInsecure", "alpn") }) {
            return invalidError()
        }
        val serverName = string(tls.get("serverName")) ?: return invalidError()
        if (!isHostname(serverName)) return invalidError()
        if (tls.has("allowInsecure") && boolean(tls.get("allowInsecure")) != false) return invalidError()
        val fingerprint = string(tls.get("fingerprint"))
        if (fingerprint != null && fingerprint !in fingerprints) return invalidError()
        val alpn = tls.getAsJsonArray("alpn")
        if (alpn != null) {
            val values = alpn.mapNotNull { element -> string(element) }
            if (values.size != alpn.size() || values.isEmpty() || values.size > 4) return invalidError()
            if (values.any { it !in setOf("h2", "http/1.1", "h3") }) return invalidError()
        }
        return null
    }

    private fun validateXhttp(xhttp: JsonObject?): ConfigValidationError? {
        if (xhttp == null) return invalidError()
        if (!xhttp.keySet().all { it in setOf("host", "path", "mode", "extra") }) return invalidError()
        val path = string(xhttp.get("path")) ?: return invalidError()
        if (path.length > MAX_PATH_LENGTH || !path.startsWith("/")) return invalidError()
        if (path.any { it.code <= 0x20 || it == '"' || it == '\\' }) return invalidError()
        val host = string(xhttp.get("host")).orEmpty()
        if (host.isNotEmpty() && !isHostname(host)) return invalidError()
        val mode = string(xhttp.get("mode")).orEmpty()
        if (mode.isNotEmpty() && mode !in xhttpModeValues) return invalidError()
        val extra = xhttp.get("extra")
        if (extra != null) {
            if (!extra.isJsonObject) return invalidError()
            if (extra.asJsonObject.has("downloadSettings")) return invalidError()
            if (gson.toJson(extra).toByteArray(Charsets.UTF_8).size > MAX_EXTRA_BYTES) return invalidError()
        }
        return null
    }

    private fun validateSockopt(sockopt: JsonObject?): ConfigValidationError? {
        if (sockopt == null) return null
        if (!sockopt.keySet().all { it in sockoptKeys }) return invalidError()
        val interfaceName = string(sockopt.get("interface"))
        if (interfaceName != null) {
            if (interfaceName.isBlank() || interfaceName.length > 128) return invalidError()
            if (interfaceName.any { it.code < 0x20 || it in "\\/\"|?" }) return invalidError()
        }
        if (sockopt.has("tcpFastOpen") && boolean(sockopt.get("tcpFastOpen")) == null) return invalidError()
        listOf("tcpKeepAliveIdle", "tcpKeepAliveInterval").forEach { key ->
            if (sockopt.has(key) && int(sockopt.get(key)) == null) return invalidError()
        }
        val strategy = string(sockopt.get("domainStrategy"))
        if (strategy != null && strategy != "AsIs") return invalidError()
        return null
    }

    private fun isLocalSocksOutbound(outbound: JsonObject, listenPort: Int): Boolean {
        if (string(outbound.get("type")) != "socks") return false
        if (string(outbound.get("server")) != "127.0.0.1") return false
        if (int(outbound.get("server_port")) != listenPort) return false
        if (outbound.has("username") || outbound.has("password")) return false
        val version = string(outbound.get("version"))
        if (version != null && version != "5") return false
        return true
    }

    private fun isReferenced(route: JsonObject?, outbounds: JsonArray, tag: String): Boolean {
        if (string(route?.get("final")) == tag) return true
        if (route?.getAsJsonArray("rules")?.any { element ->
                string(element.takeIf { element -> element.isJsonObject }?.asJsonObject?.get("outbound")) == tag
            } == true
        ) {
            return true
        }
        return outbounds.any { element ->
            val outbound = element.takeIf { element -> element.isJsonObject }?.asJsonObject ?: return@any false
            outbound.getAsJsonArray("outbounds")?.any { string(it) == tag } == true
        }
    }

    private fun isIpLiteral(value: String): Boolean {
        if (ipv4Pattern.matches(value)) return true
        return value.length in 2..45 && ':' in value && ipv6Chars.matches(value)
    }

    private fun isHostname(value: String): Boolean =
        value.length <= 253 && hostnamePattern.matches(value)

    private fun string(element: JsonElement?): String? =
        element?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun int(element: JsonElement?): Int? =
        element?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { runCatching { it.asInt }.getOrNull() }

    private fun isInt(element: JsonElement?, expected: Int): Boolean = int(element) == expected

    private fun boolean(element: JsonElement?): Boolean? =
        element?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonElement?.asJsonObjectOrNull(): JsonObject? =
        this?.takeIf { element -> element.isJsonObject }?.asJsonObject

    private fun invalid(): XhttpSidecarParseResult =
        XhttpSidecarParseResult.Rejected(invalidError())

    private fun invalidError(): ConfigValidationError = ConfigValidationError(
        ConfigErrorCode.INVALID_XHTTP_SIDECAR,
        "XHTTP sidecar metadata is invalid",
    )
}
