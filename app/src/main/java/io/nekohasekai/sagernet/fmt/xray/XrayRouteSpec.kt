package io.nekohasekai.sagernet.fmt.xray

/**
 * Everything the Xray child needs, with no Android globals and no secret-bearing logging.
 *
 * [sidecarPort] is the loopback SOCKS5 listener that the sing-box core connects to.
 * [mappingPort] is the loopback `direct` inbound that sing-box owns
 * (`override_address`/`override_port` point at the real entry). The child therefore never dials
 * the entry itself, which is the Android loop-avoidance contract: a child socket to the entry
 * would re-enter the TUN because `VpnService` includes the app itself.
 *
 * Secrets ([uuid], [serverName], [realityPublicKey], [realityShortId], [path]) never appear in
 * [toString] and must never be logged by callers.
 */
data class XrayRouteSpec(
    val routeId: String,
    val sidecarPort: Int,
    val mappingPort: Int,
    /** Real entry the child dials; sing-box routes this IP outside the TUN. */
    val entryHost: String = "",
    val entryPort: Int = 0,
    val uuid: String,
    val serverName: String,
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val fingerprint: String = "chrome",
    val path: String = "/",
    val mode: String = "auto",
    val security: XrayStreamSecurity = XrayStreamSecurity.Reality,
    val alpn: List<String> = emptyList(),
    /** `false` until QUIC/UDP behaviour is decided on a device (report §8, NO-GO 11). */
    val udp: Boolean = false,
) {
    override fun toString(): String = buildString {
        append("XrayRouteSpec(routeId=")
        append(routeId)
        append(", sidecarPort=")
        append(sidecarPort)
        append(", mappingPort=")
        append(mappingPort)
        append(", security=")
        append(security)
        append(", mode=")
        append(mode)
        append(", udp=")
        append(udp)
        append(", secrets=<redacted>)")
    }
}

enum class XrayStreamSecurity {
    Reality,
    Tls,
    None,
}

/** Identity of the mapping inbound sing-box must emit for this route. */
data class XrayMappingExpectation(
    val listenHost: String = XrayConfigBuilder.LOOPBACK_V4,
    val listenPort: Int,
    val overrideAddress: String,
    val overridePort: Int,
)

fun XrayRouteSpec.mappingExpectation(entryHost: String, entryPort: Int): XrayMappingExpectation =
    XrayMappingExpectation(
        listenPort = mappingPort,
        overrideAddress = entryHost,
        overridePort = entryPort,
    )
