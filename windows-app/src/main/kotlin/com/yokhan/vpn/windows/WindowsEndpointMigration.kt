package com.yokhan.gates.windows

import com.google.gson.JsonObject
import com.google.gson.JsonArray

internal const val GATES_AUTH_ISSUER = "https://auth.yokhan.xyz/realms/yokhan"
internal const val GATES_APP_API_BASE_URL = "https://app.yokhan.xyz/gates"
internal const val GATES_DOWNLOAD_PAGE_URL = "https://fitness.yokhan.xyz/download"
internal const val GATES_BUG_REPORT_URL = "$GATES_APP_API_BASE_URL/api/app/bug-report"
private const val LEGACY_WINDOWS_AUTH_ISSUER = "https://auth.yokhanfitness.ru/realms/yokhan"
private const val LEGACY_WINDOWS_APP_API_BASE_URL = "https://cloak.yokhanfitness.ru"

internal fun migrateWindowsDownloadPage(value: String): String =
    if (value.trimEnd('/') in setOf(
            "https://yokhanfitness.ru/hub/gates",
            "https://app.yokhan.xyz/hub/gates",
        )) GATES_DOWNLOAD_PAGE_URL else value

internal fun migrateWindowsReportUrl(value: String): String =
    if (value.trimEnd('/') in setOf(
            "https://cloak.yokhanfitness.ru/api/app/bug-report",
            "https://yokhanfitness.ru/api/app/bug-report",
        )) GATES_BUG_REPORT_URL else value

internal fun migrateWindowsAppApiBaseUrl(value: String): String =
    if (value.trimEnd('/') == LEGACY_WINDOWS_APP_API_BASE_URL) GATES_APP_API_BASE_URL else value

internal fun migrateWindowsAuthIssuer(value: String): String =
    if (value.trimEnd('/') == LEGACY_WINDOWS_AUTH_ISSUER) GATES_AUTH_ISSUER else value

internal fun windowsAuthIssuerMatches(storedIssuer: String?, expectedIssuer: String): Boolean =
    !storedIssuer.isNullOrBlank() && storedIssuer.trimEnd('/') == expectedIssuer.trimEnd('/')

internal fun migrateWindowsConfigEndpoints(source: JsonObject): Boolean {
    var changed = false
    listOf(
        "appApiBaseUrl" to ::migrateWindowsAppApiBaseUrl,
        "authIssuer" to ::migrateWindowsAuthIssuer,
        "downloadPageUrl" to ::migrateWindowsDownloadPage,
    ).forEach { (key, migrate) ->
        val value = source.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString ?: return@forEach
        val migrated = migrate(value)
        if (migrated != value) {
            source.addProperty(key, migrated)
            changed = true
        }
    }
    val reports = source.get("reportUrls")?.takeIf { it.isJsonArray }?.asJsonArray
    if (reports != null && reports.all { it.isJsonPrimitive && it.asJsonPrimitive.isString }) {
        val before = reports.map { it.asString }
        val after = before.map(::migrateWindowsReportUrl)
        if (after != before) {
            source.add("reportUrls", JsonArray().apply {
                after.forEachIndexed { index, url ->
                    if (url != GATES_BUG_REPORT_URL || index == after.indexOf(url)) add(url)
                }
            })
            changed = true
        }
    }
    return changed
}
