package com.yokhan.gates.app.routing

/**
 * Pure per-app VPN routing decision used by [io.nekohasekai.sagernet.bg.VpnService].
 *
 * Computes the effective bypass/allowlist mode BEFORE merging automatic RU-bypass
 * and server exclusion packages so disabling per-app settings never drops exclusions
 * when switching from allowlist (`bypass = false`) back to default bypass mode.
 */
data class VpnAppRoutePlan(
    val applyPerAppSplit: Boolean,
    val effectiveBypass: Boolean,
    val disallowedPackages: Set<String>,
    val allowedPackages: Set<String>,
    val inTunnelDirectPackages: Set<String>,
    val blockedBySystemLockdown: Boolean,
)

object VpnAppRoutePlanner {

    fun build(
        ownPackageName: String,
        wholeTrafficMode: Boolean,
        proxyApps: Boolean,
        configuredBypass: Boolean,
        needBypassRootUid: Boolean,
        manualPackages: Set<String>,
        allApps: Collection<String>,
        autoBypassPackages: Set<String>,
        alwaysOnLockdown: Boolean = false,
    ): VpnAppRoutePlan {
        if (wholeTrafficMode) {
            return VpnAppRoutePlan(
                applyPerAppSplit = false,
                effectiveBypass = true,
                disallowedPackages = emptySet(),
                allowedPackages = emptySet(),
                inTunnelDirectPackages = emptySet(),
                blockedBySystemLockdown = false,
            )
        }

        val hasRelevantPackages = proxyApps || autoBypassPackages.isNotEmpty() || needBypassRootUid
        if (!hasRelevantPackages) {
            return VpnAppRoutePlan(
                applyPerAppSplit = false,
                effectiveBypass = true,
                disallowedPackages = emptySet(),
                allowedPackages = emptySet(),
                inTunnelDirectPackages = emptySet(),
                blockedBySystemLockdown = false,
            )
        }

        val individual = linkedSetOf<String>()
        var bypass = configuredBypass

        if (proxyApps) {
            individual.addAll(manualPackages.asSequence().filter { it.isNotBlank() })
            if (bypass && needBypassRootUid) {
                val inverted = allApps.toMutableSet()
                inverted.removeAll(individual)
                individual.clear()
                individual.addAll(inverted)
                bypass = false
            }
        } else if (needBypassRootUid) {
            individual.addAll(allApps)
            bypass = false
        } else if (autoBypassPackages.isNotEmpty()) {
            // Auto-bypass with manual per-app list turned off MUST always operate in bypass mode
            // before autoBypassPackages are merged; otherwise allowlist stale state leaves individual empty.
            bypass = true
        }

        if (autoBypassPackages.isNotEmpty()) {
            if (bypass) {
                individual.addAll(autoBypassPackages)
            } else {
                individual.removeAll(autoBypassPackages)
            }
        }

        // GATES core always owns direct protect() sockets and must stay outside its own disallowed/allowed edge bugs.
        individual.remove(ownPackageName)

        // When Android OS "Block connections without VPN" (Lockdown) is enabled,
        // addDisallowedApplication() drops 100% of internet access for excluded apps.
        // Fall back to in-tunnel sing-box UID direct rules instead of OS-level app disallow.
        if (bypass && alwaysOnLockdown) {
            return VpnAppRoutePlan(
                applyPerAppSplit = false,
                effectiveBypass = true,
                disallowedPackages = emptySet(),
                allowedPackages = emptySet(),
                inTunnelDirectPackages = individual.toSet(),
                blockedBySystemLockdown = individual.isNotEmpty(),
            )
        }

        return if (bypass) {
            VpnAppRoutePlan(
                applyPerAppSplit = individual.isNotEmpty(),
                effectiveBypass = true,
                disallowedPackages = individual.toSet(),
                allowedPackages = emptySet(),
                inTunnelDirectPackages = emptySet(),
                blockedBySystemLockdown = false,
            )
        } else {
            individual.add(ownPackageName)
            VpnAppRoutePlan(
                applyPerAppSplit = true,
                effectiveBypass = false,
                disallowedPackages = emptySet(),
                allowedPackages = individual.toSet(),
                inTunnelDirectPackages = emptySet(),
                blockedBySystemLockdown = false,
            )
        }
    }
}
