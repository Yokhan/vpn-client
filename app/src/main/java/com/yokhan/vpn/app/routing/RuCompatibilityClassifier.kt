package com.yokhan.gates.app.routing

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import moe.matsuri.nb4a.utils.NGUtil
import java.util.Locale

class RuCompatibilityClassifier(
    private val context: Context,
) {

    fun directPackages(
        installedPackages: Collection<String>,
        includeRuBypass: Boolean = !DataStore.wholeTrafficMode && DataStore.enableRuBypass,
        includeStealth: Boolean = !DataStore.wholeTrafficMode && DataStore.stealthMode,
        serverRules: String? = DataStore.serverExcludePackages.takeIf { !DataStore.wholeTrafficMode },
        installerPackages: Map<String, String?> = emptyMap(),
    ): Set<String> {
        if (!includeRuBypass && !includeStealth && serverRules.isNullOrBlank()) return emptySet()
        val rules = loadRules(includeRuBypass, includeStealth, serverRules)
        return installedPackages
            .asSequence()
            .filter { packageName ->
                classify(
                    packageName = packageName,
                    rules = rules,
                    enableRuHeuristics = includeRuBypass,
                    installerPackage = installerPackages[packageName],
                ).route == RuCompatibilityRoute.Direct
            }
            .toSet()
    }

    fun explainInstalledPackages(
        installedPackages: Collection<String>,
        includeRuBypass: Boolean = !DataStore.wholeTrafficMode && DataStore.enableRuBypass,
        includeStealth: Boolean = !DataStore.wholeTrafficMode && DataStore.stealthMode,
        serverRules: String? = DataStore.serverExcludePackages.takeIf { !DataStore.wholeTrafficMode },
        installerPackages: Map<String, String?> = emptyMap(),
    ): List<RuCompatibilityDecision> {
        val rules = loadRules(includeRuBypass, includeStealth, serverRules)
        return installedPackages
            .asSequence()
            .map { packageName ->
                classify(
                    packageName = packageName,
                    rules = rules,
                    enableRuHeuristics = includeRuBypass,
                    installerPackage = installerPackages[packageName],
                )
            }
            .sortedWith(
                compareByDescending<RuCompatibilityDecision> { it.route == RuCompatibilityRoute.Direct }
                    .thenBy { it.packageName }
            )
            .toList()
    }

    fun countRules(
        includeRuBypass: Boolean = !DataStore.wholeTrafficMode && DataStore.enableRuBypass,
        includeStealth: Boolean = !DataStore.wholeTrafficMode && DataStore.stealthMode,
        serverRules: String? = DataStore.serverExcludePackages.takeIf { !DataStore.wholeTrafficMode },
    ): RuCompatibilityRuleCount {
        val rules = loadRules(includeRuBypass, includeStealth, serverRules)
        return RuCompatibilityRuleCount(
            exactDirect = rules.exactDirect.size,
            prefixDirect = rules.prefixDirect.size,
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

    internal data class RuCompatibilityRules(
        val exactDirect: Set<String>,
        val prefixDirect: Set<String>,
    )

    companion object {
        const val RUSTORE_INSTALLER_PACKAGE = "ru.vk.store"

        private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val PACKAGE_PREFIX_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*\\.")

        private val PROTECTED_HEURISTIC_PREFIXES = listOf(
            "android",
            "com.android.",
            "com.google.",
            "com.sec.",
            "com.qualcomm.",
            "com.mediatek.",
            "com.miui.",
            "com.xiaomi.",
            "com.huawei.",
            "com.heytap.",
            "com.oppo.",
            "com.vivo.",
            "org.chromium.",
            "org.mozilla.",
            "org.telegram.",
            "com.whatsapp",
            "com.openai.",
            "com.anthropic.",
            "com.discord",
            "com.opera.",
            "com.brave.",
            "com.vivaldi.",
            "com.yokhan.",
            "io.nekohasekai.",
        )

        private val RU_BRAND_TOKENS = setOf(
            "sberbank",
            "sberbankid",
            "sberpay",
            "sbermarket",
            "tinkoff",
            "tbank",
            "alfabank",
            "vtb",
            "vtb24",
            "gazprombank",
            "gazprompay",
            "raiffeisen",
            "sovcombank",
            "pochtabank",
            "rosbank",
            "otpbank",
            "mirpay",
            "yoomoney",
            "gosuslugi",
            "nalog",
            "fns",
            "mosmetro",
            "wildberries",
            "ozon",
            "avito",
            "lamoda",
            "yandex",
            "kinopoisk",
            "rutube",
            "vkusvill",
            "pyaterochka",
            "perekrestok",
            "magnit",
            "samokat",
            "megamarket",
            "domclick",
            "cian",
            "dublgis",
            "dgis",
            "cdek",
            "rostelecom",
            "kassir",
            "sportmaster",
            "letoile",
            "mvideo",
            "citilink",
            "litres",
            "vkontakte",
        )

        internal fun classify(
            packageName: String,
            rules: RuCompatibilityRules,
            enableRuHeuristics: Boolean,
            installerPackage: String?,
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
            if (enableRuHeuristics && !isProtectedFromHeuristics(packageName)) {
                val lower = packageName.lowercase(Locale.ROOT)
                val dotSegments = lower.split('.')
                dotSegments.firstOrNull { it == "ru" || it == "su" }?.let { tld ->
                    return RuCompatibilityDecision(
                        packageName = packageName,
                        route = RuCompatibilityRoute.Direct,
                        reason = RuCompatibilityReason.DomainSegmentRule,
                        matchedRule = ".$tld",
                    )
                }
                val tokenSegments = dotSegments.flatMap { it.split('_', '-') }
                tokenSegments.firstOrNull { it in RU_BRAND_TOKENS }?.let { token ->
                    return RuCompatibilityDecision(
                        packageName = packageName,
                        route = RuCompatibilityRoute.Direct,
                        reason = RuCompatibilityReason.BrandSignatureRule,
                        matchedRule = token,
                    )
                }
                if (installerPackage == RUSTORE_INSTALLER_PACKAGE) {
                    return RuCompatibilityDecision(
                        packageName = packageName,
                        route = RuCompatibilityRoute.Direct,
                        reason = RuCompatibilityReason.RuStoreInstaller,
                        matchedRule = RUSTORE_INSTALLER_PACKAGE,
                    )
                }
            }
            return RuCompatibilityDecision(
                packageName = packageName,
                route = RuCompatibilityRoute.Tunnel,
                reason = RuCompatibilityReason.DefaultRoute,
                matchedRule = null,
            )
        }

        private fun isProtectedFromHeuristics(packageName: String): Boolean {
            return PROTECTED_HEURISTIC_PREFIXES.any { prefix ->
                packageName == prefix || packageName.startsWith(prefix)
            }
        }

        fun resolveInstallers(
            packageManager: PackageManager,
            packages: Collection<String>,
        ): Map<String, String?> {
            if (packages.isEmpty()) return emptyMap()
            return buildMap(packages.size) {
                for (packageName in packages) {
                    if (isProtectedFromHeuristics(packageName)) continue
                    val installer = runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            packageManager.getInstallSourceInfo(packageName).installingPackageName
                        } else {
                            @Suppress("DEPRECATION")
                            packageManager.getInstallerPackageName(packageName)
                        }
                    }.getOrNull()
                    if (!installer.isNullOrBlank()) {
                        put(packageName, installer)
                    }
                }
            }
        }
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
    DomainSegmentRule,
    BrandSignatureRule,
    RuStoreInstaller,
    DefaultRoute,
}
