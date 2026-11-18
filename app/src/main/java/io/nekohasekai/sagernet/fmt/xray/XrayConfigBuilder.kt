package io.nekohasekai.sagernet.fmt.xray

/**
 * Builds the Xray child document and enforces the single-datapath allowlist.
 *
 * The generated document may contain only:
 *  - `log`
 *  - one loopback `socks` inbound
 *  - one `vless` outbound whose address is loopback (the sing-box mapping inbound)
 *
 * It must never contain `dns`, `fakedns`, `tun`, `routing`, `observatory`, `stats`, `api`,
 * `sockopt.dialerProxy`/`sockopt.interface`, a non-loopback listen address, or `0.0.0.0`.
 * Keeping this list executable is the machine-checkable form of "Xray never owns TUN or DNS".
 */
object XrayConfigBuilder {

    const val LOOPBACK_V4 = "127.0.0.1"
    const val LOOPBACK_V6 = "::1"

    private val LOOPBACK_HOSTS = setOf(LOOPBACK_V4, LOOPBACK_V6)

    private val ALLOWED_MODES = setOf("auto", "packet-up", "stream-up", "stream-one")

    private val ALLOWED_FINGERPRINTS = setOf("chrome", "firefox", "safari", "ios", "android", "edge", "random")

    /** Raw JSON keys that must never appear in the child document. */
    private val FORBIDDEN_KEYS = listOf(
        "\"dns\"",
        "\"fakedns\"",
        "\"tun\"",
        "\"routing\"",
        "\"observatory\"",
        "\"stats\"",
        "\"api\"",
        "\"dialerProxy\"",
        "\"sockopt\"",
    )

    /** Raw addresses that would create a second ingress/egress surface. */
    private val FORBIDDEN_ADDRESSES = listOf("\"0.0.0.0\"", "\"::\"")

    /**
     * Pure build. Throws [XrayConfigException] with a field-level message (never a value) when the
     * spec cannot be represented safely.
     */
    fun build(spec: XrayRouteSpec): String {
        val problems = validate(spec)
        if (problems.isNotEmpty()) {
            throw XrayConfigException("invalid xray route: " + problems.joinToString("; "))
        }
        val json = encode(spec)
        assertSingleDatapath(json)
        return json
    }

    fun validate(spec: XrayRouteSpec): List<String> {
        val problems = mutableListOf<String>()

        if (!isUsablePort(spec.sidecarPort) || !isUsablePort(spec.mappingPort)) {
            problems += "port outside the valid range"
        } else if (spec.sidecarPort == spec.mappingPort) {
            problems += "sidecar and mapping ports must differ"
        }

        if (spec.uuid.isBlank()) problems += "uuid is blank"
        if (spec.serverName.isBlank()) problems += "serverName is blank"

        if (spec.security == XrayStreamSecurity.Reality) {
            if (spec.realityPublicKey.isBlank()) problems += "reality public key is blank"
            if (spec.realityShortId.length > 16) problems += "reality short id is too long"
        }
        if (spec.security != XrayStreamSecurity.None && spec.fingerprint !in ALLOWED_FINGERPRINTS) {
            problems += "unsupported uTLS fingerprint"
        }

        if (!spec.path.startsWith("/")) problems += "path must start with /"
        if (spec.mode !in ALLOWED_MODES) problems += "unsupported xhttp mode"

        if (containsControlChars(spec.uuid) ||
            containsControlChars(spec.serverName) ||
            containsControlChars(spec.realityPublicKey) ||
            containsControlChars(spec.realityShortId) ||
            containsControlChars(spec.path)
        ) {
            problems += "value contains control characters"
        }

        if (spec.alpn.any { it.isBlank() || containsControlChars(it) }) {
            problems += "alpn contains a blank or malformed entry"
        }

        return problems
    }

    /** Structural guard on the serialized document, applied to every build. */
    fun assertSingleDatapath(json: String) {
        FORBIDDEN_KEYS.firstOrNull { json.contains(it) }?.let {
            throw XrayConfigException("child config contains a forbidden key")
        }
        FORBIDDEN_ADDRESSES.firstOrNull { json.contains(it) }?.let {
            throw XrayConfigException("child config contains a non-loopback listen address")
        }
        if (!json.contains("\"listen\":\"$LOOPBACK_V4\"") && !json.contains("\"listen\":\"$LOOPBACK_V6\"")) {
            throw XrayConfigException("socks inbound is not loopback-only")
        }
    }

    private fun isUsablePort(port: Int): Boolean = port in 1024..65535

    private fun containsControlChars(value: String): Boolean = value.any { it.code < 0x20 || it.code == 0x7f }

    private fun encode(spec: XrayRouteSpec): String {
        val users = linkedMapOf<String, Any?>(
            "id" to spec.uuid,
            "encryption" to "none",
            "flow" to "",
        )
        val vnext = linkedMapOf<String, Any?>(
            "address" to spec.entryHost.ifBlank { LOOPBACK_V4 },
            "port" to (spec.entryPort.takeIf { it > 0 } ?: spec.mappingPort),
            "users" to listOf(users),
        )
        val streamSettings = linkedMapOf<String, Any?>(
            "network" to "xhttp",
            "security" to securityName(spec.security),
        )
        if (spec.security == XrayStreamSecurity.Reality) {
            streamSettings["realitySettings"] = linkedMapOf<String, Any?>(
                "serverName" to spec.serverName,
                "publicKey" to spec.realityPublicKey,
                "shortId" to spec.realityShortId,
                "fingerprint" to spec.fingerprint,
            ).also { reality ->
                if (spec.alpn.isNotEmpty()) reality["alpn"] = spec.alpn
            }
        } else if (spec.security == XrayStreamSecurity.Tls) {
            streamSettings["tlsSettings"] = linkedMapOf<String, Any?>(
                "serverName" to spec.serverName,
                "fingerprint" to spec.fingerprint,
            ).also { tls ->
                if (spec.alpn.isNotEmpty()) tls["alpn"] = spec.alpn
            }
        }
        val xhttp = linkedMapOf<String, Any?>(
            "path" to spec.path,
            "mode" to spec.mode,
            "extra" to linkedMapOf<String, Any?>(
                "xmux" to linkedMapOf<String, Any?>(
                    "maxConnections" to "4",
                    "hKeepAlivePeriod" to 30,
                ),
            ),
        )
        if (spec.serverName.isNotBlank()) {
            xhttp["host"] = spec.serverName
        }
        streamSettings["xhttpSettings"] = xhttp

        val inbound = linkedMapOf<String, Any?>(
            "tag" to "sidecar-in",
            "listen" to LOOPBACK_V4,
            "port" to spec.sidecarPort,
            "protocol" to "socks",
            "settings" to linkedMapOf<String, Any?>(
                "auth" to "noauth",
                "udp" to spec.udp,
            ),
        )
        val outbound = linkedMapOf<String, Any?>(
            "tag" to "xhttp-out",
            "protocol" to "vless",
            "settings" to linkedMapOf<String, Any?>("vnext" to listOf(vnext)),
            "streamSettings" to streamSettings,
        )

        val document = linkedMapOf<String, Any?>(
            "log" to linkedMapOf<String, Any?>("loglevel" to "warning"),
            "inbounds" to listOf(inbound),
            "outbounds" to listOf(outbound),
        )
        return encodeJson(document)
    }

    private fun securityName(security: XrayStreamSecurity): String = when (security) {
        XrayStreamSecurity.Reality -> "reality"
        XrayStreamSecurity.Tls -> "tls"
        XrayStreamSecurity.None -> "none"
    }

    private fun encodeJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> encodeString(value)
        is Int, is Long, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, item) ->
            encodeString(key as String) + ":" + encodeJson(item)
        }

        is List<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { encodeJson(it) }
        else -> throw XrayConfigException("unsupported json value")
    }

    private fun encodeString(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}

class XrayConfigException(message: String) : IllegalArgumentException(message)
