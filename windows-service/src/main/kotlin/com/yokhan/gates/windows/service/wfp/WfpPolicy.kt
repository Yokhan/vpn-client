package com.yokhan.gates.windows.service.wfp

import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

sealed interface WfpScope {
    data object All : WfpScope
    data class SelectedExecutables(val absoluteExePaths: List<String>) : WfpScope
}

data class WfpPolicy(
    val scope: WfpScope,
    val bypassAbsoluteExePaths: List<String>,
    val coreAbsoluteExePath: String,
    val verifiedTunLuid: Long? = null,
) {
    init {
        requireWindowsExe(coreAbsoluteExePath)
        bypassAbsoluteExePaths.forEach(::requireWindowsExe)
        require(verifiedTunLuid == null || verifiedTunLuid > 0) { "TUN interface LUID must be positive" }
        if (scope is WfpScope.SelectedExecutables) {
            require(scope.absoluteExePaths.isNotEmpty()) { "Selected-app scope must contain at least one executable" }
            scope.absoluteExePaths.forEach(::requireWindowsExe)
            val selected = scope.absoluteExePaths.map { it.lowercase(Locale.ROOT) }.toSet()
            require(selected.size == scope.absoluteExePaths.size) { "Selected executable paths must be unique" }
            require(coreAbsoluteExePath.lowercase() !in selected) {
                "The core executable cannot be included in selected-app scope"
            }
            require(bypassAbsoluteExePaths.none { it.lowercase(Locale.ROOT) in selected }) {
                "Selected-app scope cannot also configure selected executables as bypasses"
            }
        }
        require(bypassAbsoluteExePaths.map { it.lowercase(Locale.ROOT) }.toSet().size == bypassAbsoluteExePaths.size) {
            "Bypass executable paths must be unique"
        }
        if (scope is WfpScope.SelectedExecutables) {
            require(bypassAbsoluteExePaths.isEmpty()) { "Selected-app scope does not permit bypass executables" }
        }
        if (scope is WfpScope.All) {
            require(bypassAbsoluteExePaths.none { it.equals(coreAbsoluteExePath, ignoreCase = true) }) {
                "Core executable must not also be listed as a bypass"
            }
        }
    }
}

enum class WfpAleLayer(val guid: String) {
    AUTH_CONNECT_V4("c38d57d1-05a7-4c33-904f-7fbceee60e82"),
    AUTH_CONNECT_V6("4a72393b-319f-44bc-84c3-ba54dcb3b6b4"),
    AUTH_RECV_ACCEPT_V4("e1cd9fe7-f4b5-4273-96c0-592e487b8650"),
    AUTH_RECV_ACCEPT_V6("a3b42c97-9f04-4672-b87e-cee9c483257f"),
}

enum class WfpAction { PERMIT, BLOCK }

sealed interface WfpCondition {
    data class ApplicationId(val absoluteExePath: String) : WfpCondition
    data class LocalInterfaceLuid(val luid: Long) : WfpCondition {
        init { require(luid > 0) { "Interface LUID must be positive" } }
    }
    data object Loopback : WfpCondition
    data class LocalPort(val port: Int) : WfpCondition {
        init { require(port in 0..0xffff) { "Local port is out of range" } }
    }
    data class IpProtocol(val protocol: Int) : WfpCondition {
        init { require(protocol in 0..0xff) { "IP protocol is out of range" } }
    }
}

data class WfpRulePlan(
    val key: UUID,
    val layer: WfpAleLayer,
    val action: WfpAction,
    val persistent: Boolean,
    val conditions: List<WfpCondition>,
    val weight: Long,
    val purpose: String,
)

data class WfpInspection(val rules: List<InstalledWfpRule>) {
    val persistentBlockCount: Int get() = rules.count { it.persistent && it.action == WfpAction.BLOCK }
    val dynamicPermitCount: Int get() = rules.count { !it.persistent && it.action == WfpAction.PERMIT }
}

data class InstalledWfpRule(
    val key: UUID,
    val action: WfpAction,
    val persistent: Boolean,
    val layer: WfpAleLayer,
    val purpose: String,
)

object WfpPolicyPlanner {
    private const val BLOCK_WEIGHT = 0x1000L
    private const val PERMIT_WEIGHT = 0xF000L
    private val layers = WfpAleLayer.entries

    fun plan(policy: WfpPolicy, includeTunnelPermit: Boolean = policy.verifiedTunLuid != null): List<WfpRulePlan> {
        val rules = mutableListOf<WfpRulePlan>()
        when (val scope = policy.scope) {
            WfpScope.All -> {
                layers.forEach { layer ->
                    rules += rule(layer, WfpAction.BLOCK, true, emptyList(), BLOCK_WEIGHT, "all-applications-block")
                    if (includeTunnelPermit) {
                        rules += rule(
                            layer, WfpAction.PERMIT, false,
                            listOf(WfpCondition.LocalInterfaceLuid(requireNotNull(policy.verifiedTunLuid))),
                            PERMIT_WEIGHT, "verified-tun-interface",
                        )
                    }
                    (listOf(policy.coreAbsoluteExePath) + policy.bypassAbsoluteExePaths)
                        .distinctBy { it.lowercase(Locale.ROOT) }
                        .forEach { path ->
                            rules += rule(
                                layer, WfpAction.PERMIT, false,
                                listOf(WfpCondition.ApplicationId(path)), PERMIT_WEIGHT, "explicit-executable",
                            )
                        }
                }
            }
            is WfpScope.SelectedExecutables -> scope.absoluteExePaths.forEach { path ->
                layers.forEach { layer ->
                    val app = WfpCondition.ApplicationId(path)
                    rules += rule(layer, WfpAction.BLOCK, true, listOf(app), BLOCK_WEIGHT, "selected-app-block")
                    if (includeTunnelPermit) {
                        rules += rule(
                            layer, WfpAction.PERMIT, false,
                            listOf(app, WfpCondition.LocalInterfaceLuid(requireNotNull(policy.verifiedTunLuid))),
                            PERMIT_WEIGHT, "selected-app-verified-tun",
                        )
                    }
                }
            }
        }
        return rules
    }

    private fun rule(
        layer: WfpAleLayer,
        action: WfpAction,
        persistent: Boolean,
        conditions: List<WfpCondition>,
        weight: Long,
        purpose: String,
    ): WfpRulePlan {
        val identity = listOf(layer.name, action.name, persistent, purpose, conditions.joinToString()).joinToString("|")
        val key = UUID.nameUUIDFromBytes("Yokhan.GATES.WFP|$identity".toByteArray(StandardCharsets.UTF_8))
        return WfpRulePlan(key, layer, action, persistent, conditions, weight, purpose)
    }
}

private fun requireWindowsExe(path: String) {
    val absoluteLocalPath = Regex("^[A-Za-z]:\\\\").containsMatchIn(path) &&
        !path.startsWith("\\\\") &&
        path.split('\\').drop(1).none { component ->
            component.isEmpty() || component == "." || component == ".." || ':' in component
        } &&
        path.none { character -> character.code < 0x20 || character in "<>\"|?*" }
    require(absoluteLocalPath && path.substringAfterLast('\\').endsWith(".exe", ignoreCase = true)) {
        "Executable path must be an absolute local Windows .exe path without dot segments or alternate streams"
    }
}
