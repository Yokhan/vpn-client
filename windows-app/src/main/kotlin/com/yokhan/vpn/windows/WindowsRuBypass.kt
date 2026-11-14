package com.yokhan.gates.windows

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File

internal val WINDOWS_RU_DIRECT_DOMAIN_SUFFIXES: List<String> = listOf(
    "ru",
    "su",
    "xn--p1ai",
    "by",
    "kz",
    "yandex.net",
    "yandex.com",
    "yastatic.net",
    "vk.com",
    "userapi.com",
    "mycdn.me",
    "okcdn.ru",
    "2gis.com",
    "gosuslugi.ru",
    "sberbank.ru",
    "tbank.ru",
    "tinkoff.ru",
    "alfabank.ru",
    "vtb.ru",
    "nalog.ru",
    "rzd.ru",
    "ozon.ru",
    "wildberries.ru",
    "avito.ru",
    "kinopoisk.ru",
    "dzen.ru",
    "rutube.ru",
)

internal val WINDOWS_RU_DESKTOP_PROCESS_NAMES: List<String> = listOf(
    "YandexDisk2.exe",
    "YandexMusic.exe",
    "Telemost.exe",
    "VKPlayLoader.exe",
    "GameCenter.exe",
    "vkcalls.exe",
    "1cv8.exe",
    "1cv8c.exe",
    "1cv8s.exe",
    "saby.exe",
    "sbis3plugin.exe",
    "CryptoCSP.exe",
    "Kontur.Diag.exe",
    "2gis.exe",
)

private val RU_PROCESS_KEYWORDS: List<String> = listOf(
    "yandex",
    "vk",
    "mail.ru",
    "mygames",
    "gamecenter",
    "1cv8",
    "saby",
    "sbis",
    "kontur",
    "cryptocsp",
    "cryptopro",
    "sber",
    "tinkoff",
    "gosuslugi",
    "2gis",
    "rutube",
    "kinopoisk",
    "max.exe",
    "telemost",
    "alice",
)

private val YANDEX_BROWSER_PATH_REGEX = "(?i).*\\\\Yandex\\\\YandexBrowser\\\\Application\\\\browser\\.exe$"
private val YANDEX_BROWSER_CANONICAL_SUFFIX = "\\yandex\\yandexbrowser\\application\\browser.exe"

internal fun isRuCompatibilityCandidate(
    name: String,
    path: String = "",
    displayName: String = "",
): Boolean {
    val fileName = name.ifBlank { File(path).name }.lowercase()
    if (WINDOWS_RU_DESKTOP_PROCESS_NAMES.any { it.equals(fileName, ignoreCase = true) }) {
        return true
    }
    val combined = "$fileName ${path.lowercase()} ${displayName.lowercase()}"
    return RU_PROCESS_KEYWORDS.any { keyword -> combined.contains(keyword) }
}

internal fun WindowsAppConfig.withRuBypassChanged(enabled: Boolean): WindowsAppConfig =
    copy(
        enableRuBypass = enabled,
        wholeTrafficMode = if (enabled) false else wholeTrafficMode,
    )

internal fun WindowsAppConfig.withWholeTrafficChanged(enabled: Boolean): WindowsAppConfig =
    copy(
        wholeTrafficMode = enabled,
        enableRuBypass = if (enabled) false else enableRuBypass,
    )

internal fun effectiveTunExcludeAddresses(config: WindowsAppConfig): List<String> {
    val raw = config.tunRouteExcludeAddress.ifEmpty { WindowsAppConfig().tunRouteExcludeAddress }
    if (config.bypassLan) return raw
    val loopbackOnly = raw.filter { it.startsWith("127.") || it == "::1/128" }
    return loopbackOnly.ifEmpty { listOf("127.0.0.0/8") }
}

internal fun windowsLoopbackDirectRule(): JsonObject =
    JsonObject().apply {
        add("domain", JsonArray().apply { add("localhost") })
        add("domain_suffix", JsonArray().apply { add("localhost") })
        add("ip_cidr", JsonArray().apply { add("127.0.0.0/8"); add("::1/128") })
        addProperty("outbound", "direct")
    }

internal fun windowsLanDirectRule(): JsonObject =
    JsonObject().apply {
        add("domain_suffix", JsonArray().apply {
            add("local")
            add("lan")
            add("home.arpa")
        })
        addProperty("ip_is_private", true)
        addProperty("outbound", "direct")
    }

internal fun windowsQuicRejectRule(): JsonObject =
    JsonObject().apply {
        add("protocol", JsonArray().apply { add("quic") })
        addProperty("action", "reject")
    }

internal fun windowsRuBypassRouteRules(config: WindowsAppConfig): List<JsonObject> {
    if (config.wholeTrafficMode || !config.enableRuBypass) return emptyList()
    val rules = mutableListOf<JsonObject>()
    val blockedProcessNames = config.appProxyProcessNames.map { it.lowercase() }.toSet()
    val blockedPathNames = config.appProxyProcessPaths
        .map { File(it).name.lowercase() }
        .toSet()

    val allowedRuProcesses = WINDOWS_RU_DESKTOP_PROCESS_NAMES.filter { name ->
        val lower = name.lowercase()
        lower !in blockedProcessNames && lower !in blockedPathNames
    }
    if (allowedRuProcesses.isNotEmpty() && !config.killSwitchEnabled) {
        rules += JsonObject().apply {
            add("process_name", JsonArray().apply { allowedRuProcesses.forEach(::add) })
            addProperty("outbound", "direct")
        }
    }

    val yandexBrowserProxied = config.appProxyProcessPaths.any {
        it.replace('/', '\\').lowercase().endsWith(YANDEX_BROWSER_CANONICAL_SUFFIX)
    } || "browser.exe" in blockedProcessNames
    if (!yandexBrowserProxied && !config.killSwitchEnabled) {
        rules += JsonObject().apply {
            add("process_path_regex", JsonArray().apply { add(YANDEX_BROWSER_PATH_REGEX) })
            addProperty("outbound", "direct")
        }
    }

    rules += JsonObject().apply {
        add("domain_suffix", JsonArray().apply { WINDOWS_RU_DIRECT_DOMAIN_SUFFIXES.forEach(::add) })
        addProperty("outbound", "direct")
    }
    return rules
}

internal fun applyWindowsRuDnsRules(dns: JsonObject, config: WindowsAppConfig) {
    val servers = dns.getAsJsonArray("servers") ?: return
    val hasDirectDns = servers.any { element ->
        element.isJsonObject && element.asJsonObject.get("tag")?.asString == "direct-dns"
    }
    if (!hasDirectDns) return

    val existingRules = dns.getAsJsonArray("rules") ?: JsonArray()
    val filteredRules = JsonArray()
    existingRules.forEach { element ->
        if (!element.isJsonObject) {
            filteredRules.add(element)
            return@forEach
        }
        val rule = element.asJsonObject
        val targetServer = rule.get("server")?.asString
        val isAutoRuDnsRule = targetServer == "direct-dns" &&
            rule.has("domain_suffix") &&
            !rule.has("outbound") &&
            !rule.has("clash_mode")
        if (config.wholeTrafficMode && targetServer == "direct-dns" && !rule.has("outbound")) {
            return@forEach
        }
        if (isAutoRuDnsRule) {
            return@forEach
        }
        filteredRules.add(rule)
    }

    if (!config.wholeTrafficMode && config.enableRuBypass) {
        val ruDnsRule = JsonObject().apply {
            add("domain_suffix", JsonArray().apply { WINDOWS_RU_DIRECT_DOMAIN_SUFFIXES.forEach(::add) })
            addProperty("server", "direct-dns")
        }
        val merged = JsonArray()
        merged.add(ruDnsRule)
        filteredRules.forEach { merged.add(it) }
        dns.add("rules", merged)
    } else {
        dns.add("rules", filteredRules)
    }
}

internal fun shouldKeepUpstreamRuleForWholeTraffic(element: JsonObject): Boolean {
    if (element.get("outbound")?.asString != "direct") return true
    if (element.has("outbound")) {
        val domain = element.getAsJsonArray("domain")
        val isLoopback = domain?.any { it.isJsonPrimitive && it.asString == "localhost" } == true
        if (isLoopback) return true
    }
    return false
}
