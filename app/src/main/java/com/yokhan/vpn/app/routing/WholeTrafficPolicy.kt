package com.yokhan.gates.app.routing

import io.nekohasekai.sagernet.IPv6Mode

/**
 * Compliance policy for the VPN data path.
 *
 * Whole-traffic mode is the compliant default: every app is routed through the
 * tunnel. Per-app allow/deny splits, RU/stealth auto-bypass lists, server
 * exclude lists, LAN access and user-defined direct rules are advanced
 * (non-compliant) behavior and are applied only when the user explicitly turns
 * whole-traffic mode off. Transport/bootstrap paths (protected proxy sockets,
 * proxy server DNS resolution, internal health checks) are outside this policy.
 */
data class WholeTrafficPolicy(
    val wholeTrafficMode: Boolean,
) {
    /** Per-app allow/disallow split routing. */
    val applyPerAppSplit: Boolean get() = !wholeTrafficMode

    /** RU bypass, stealth bypass and server-provided exclude packages. */
    val applyAutoDirectRules: Boolean get() = !wholeTrafficMode

    /** "Bypass LAN" route exclusions and the core private-IP direct rule. */
    val applyLanBypass: Boolean get() = !wholeTrafficMode

    /** Allow inbound listeners on the LAN (0.0.0.0). */
    val allowLanAccess: Boolean get() = !wholeTrafficMode

    /** Allow inbound listeners on a non-loopback interface (USB bridge). */
    val allowExternalListeners: Boolean get() = !wholeTrafficMode

    /**
     * Allow any local mixed-proxy listener, including a loopback one. In
     * whole-traffic mode the tunnel is the only data path, so no unauthenticated
     * local proxy surface may be opened next to it.
     */
    val allowInboundListeners: Boolean get() = !wholeTrafficMode

    /** User routing rules that point at the direct/bypass outbound. */
    val applyUserDirectRules: Boolean get() = !wholeTrafficMode

    /**
     * IPv6 is always captured by the tunnel interface. When the IPv6 mode is
     * DISABLE, the core must reject it instead of letting Android leak it via
     * the underlying network.
     */
    companion object {
        /** Whether IPv6 packets must be rejected by the core. */
        fun shouldBlockIpv6(ipv6Mode: Int): Boolean = ipv6Mode == IPv6Mode.DISABLE
    }
}
