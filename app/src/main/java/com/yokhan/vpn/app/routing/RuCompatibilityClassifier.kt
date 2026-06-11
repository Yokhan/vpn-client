package com.yokhan.gates.app.routing

import android.content.Context
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import moe.matsuri.nb4a.utils.NGUtil

class RuCompatibilityClassifier(
    private val context: Context,
) {

    fun directPackages(
        installedPackages: Collection<String>,
        includeRuBypass: Boolean = DataStore.enableRuBypass,
        includeStealth: Boolean = DataStore.stealthMode,
        serverRules: String? = DataStore.serverExcludePackages,
    ): Set<String> {
        if (!includeRuBypass && !includeStealth && serverRules.isNullOrBlank()) return emptySet()
        val rules = loadRules(includeRuBypass, includeStealth, serverRules)
        return installedPackages
            .asSequence()
            .filter { packageName ->
                rules.exactDirect.contains(packageName) ||
                    rules.prefixDirect.any { packageName.startsWith(it) }
            }
            .toSet()
    }

    fun explainInstalledPackages(
        installedPackages: Collection<String>,
        includeRuBypass: Boolean = DataStore.enableRuBypass,
        includeStealth: Boolean = DataStore.stealthMode,
        serverRules: String? = DataStore.serverExcludePackages,
    ): List<RuCompatibilityDecision> {
        val rules = loadRules(includeRuBypass, includeStealth, serverRules)
        return installedPackages
            .asSequence()
            .map { packageName -> explain(packageName, rules) }
            .sortedWith(
                compareByDescending<RuCompatibilityDecision> { it.route == RuCompatibilityRoute.Direct }
                    .thenBy { it.packageName }
            )
            .toList()
    }

    fun countRules(
        includeRuBypass: Boolean = DataStore.enableRuBypass,
        includeStealth: Boolean = DataStore.stealthMode,
        serverRules: String? = DataStore.serverExcludePackages,
    ): RuCompatibilityRuleCount {
        val rules = loadRules(includeRuBypass, includeStealth, serverRules)
        return RuCompatibilityRuleCount(
            exactDirect = rules.exactDirect.size,
            prefixDirect = rules.prefixDirect.size,
        )
    }

    private fun explain(
        packageName: String,
        rules: RuCompatibilityRules,
    ): RuCompatibilityDecision {
        if (packageName in rules.exactDirect) {
            return RuCompatibilityDecision(
                packageName = packageName,
                route = RuCompatibilityRoute.Direct,
                reason = RuCompatibilityReason.ExactRule,
                matchedRule = packageName,
            )
        }
        rules.prefixDirect.firstOrNull { packageName.startsWith(it) }?.let { prefix ->
            return RuCompatibilityDecision(
                packageName = packageName,
                route = RuCompatibilityRoute.Direct,
                reason = RuCompatibilityReason.PrefixRule,
                matchedRule = "$prefix*",
            )
        }
        return RuCompatibilityDecision(
            packageName = packageName,
            route = RuCompatibilityRoute.Tunnel,
            reason = RuCompatibilityReason.DefaultRoute,
            matchedRule = null,
        )
    }

    private fun loadRules(
        includeRuBypass: Boolean,
        includeStealth: Boolean,
        serverRules: String?,
    ): RuCompatibilityRules {
        val exactDirect = linkedSetOf<String>()
        val prefixDirect = linkedSetOf<String>()

        if (includeRuBypass) {
            exactDirect.addAll(loadAssetLines("ru_bypass_packages.txt").mapNotNull { it.asExactPackage() })
            prefixDirect.addAll(loadAssetLines("ru_bypass_prefixes.txt").mapNotNull { it.asPrefixRule() })
        }
        if (includeStealth) {
            exactDirect.addAll(loadAssetLines("stealth_bypass_packages.txt").mapNotNull { it.asExactPackage() })
            prefixDirect.addAll(loadAssetLines("stealth_bypass_prefixes.txt").mapNotNull { it.asPrefixRule() })
        }
        serverRules.orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filterRuleLines()
            .forEach { line ->
                val prefix = line.asPrefixRule()
                if (prefix != null) {
                    prefixDirect.add(prefix)
                } else {
                    line.asExactPackage()?.let(exactDirect::add)
                }
            }

        return RuCompatibilityRules(
            exactDirect = exactDirect,
            prefixDirect = prefixDirect,
        )
    }

    private fun loadAssetLines(assetName: String): List<String> {
        return try {
            NGUtil.readTextFromAssets(context, assetName)
                .lineSequence()
                .map { it.trim() }
                .filterRuleLines()
                .toList()
        } catch (e: Exception) {
            Logs.w(e)
            emptyList()
        }
    }

    private fun Sequence<String>.filterRuleLines(): Sequence<String> {
        return filter { line ->
            line.isNotEmpty() && !line.startsWith("#")
        }
    }

    private fun String.asExactPackage(): String? {
        if (startsWith("prefix:", ignoreCase = true)) return null
        if (endsWith(".*")) return null
        return takeIf { PACKAGE_PATTERN.matches(it) }
    }

    private fun String.asPrefixRule(): String? {
        val rawPrefix = when {
            startsWith("prefix:", ignoreCase = true) -> substringAfter(':')
            endsWith(".*") -> removeSuffix(".*")
            endsWith(".") -> this
            else -> return null
        }.trim()
        if (rawPrefix.isBlank()) return null
        val normalized = if (rawPrefix.endsWith(".")) rawPrefix else "$rawPrefix."
        return normalized.takeIf { PACKAGE_PREFIX_PATTERN.matches(it) }
    }

    private data class RuCompatibilityRules(
        val exactDirect: Set<String>,
        val prefixDirect: Set<String>,
    )

    private companion object {
        val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        val PACKAGE_PREFIX_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*\\.")
    }
}

data class RuCompatibilityDecision(
    val packageName: String,
    val route: RuCompatibilityRoute,
    val reason: RuCompatibilityReason,
    val matchedRule: String?,
)

data class RuCompatibilityRuleCount(
    val exactDirect: Int,
    val prefixDirect: Int,
)

enum class RuCompatibilityRoute {
    Direct,
    Tunnel,
}

enum class RuCompatibilityReason {
    ExactRule,
    PrefixRule,
    DefaultRoute,
}
