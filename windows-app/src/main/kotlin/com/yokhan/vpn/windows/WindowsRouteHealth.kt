package com.yokhan.gates.windows

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yokhan.vpn.shared.domain.SmartRouteCandidate
import com.yokhan.vpn.shared.domain.SmartRouteContext
import com.yokhan.vpn.shared.domain.selectSmartRoute

data class WindowsRouteHealthEntry(
    val routeId: String,
    val status: String,
    val score: Int,
)

data class WindowsRouteHealthSnapshot(
    val routes: Map<String, WindowsRouteHealthEntry> = emptyMap(),
    val preferredRoute: String = "",
    val avoidRoutes: Set<String> = emptySet(),
    val evaluationMode: String = "advisory_only",
    val autoSwitchEnabled: Boolean = false,
) {
    fun isEmpty(): Boolean = routes.isEmpty() && preferredRoute.isBlank() && avoidRoutes.isEmpty()

    fun scoreFor(routeId: String): Int =
        routes[routeId.lowercase()]?.score ?: DEFAULT_UNKNOWN_ROUTE_SCORE

    fun shouldAvoid(routeId: String): Boolean {
        val normalized = routeId.lowercase()
        if (normalized in avoidRoutes) return true
        return routes[normalized]?.status in BLOCKED_ROUTE_STATUSES
    }
}

fun parseWindowsRouteHealthPayload(rawJson: String): WindowsRouteHealthSnapshot {
    val root = runCatching { JsonParser.parseString(rawJson).asJsonObject }.getOrNull()
        ?: return WindowsRouteHealthSnapshot()
    val routes = parseRouteEntries(root.getAsJsonObject("routes"))
    val recommendation = root.getAsJsonObject("recommendation")
    val quality = recommendation?.getAsJsonObject("quality")
    return WindowsRouteHealthSnapshot(
        routes = routes,
        preferredRoute = normalizeRouteId(recommendation?.get("preferred_route")?.asString.orEmpty()),
        avoidRoutes = parseAvoidRoutes(recommendation),
        evaluationMode = quality?.get("evaluation_mode")?.asString.orEmpty()
            .ifBlank { "advisory_only" },
        autoSwitchEnabled = quality?.get("auto_switch_enabled")?.asBoolean == true,
    )
}

fun buildWindowsAutoOutbound(
    candidateRoutes: List<String>,
    @Suppress("UNUSED_PARAMETER") snapshot: WindowsRouteHealthSnapshot,
    @Suppress("UNUSED_PARAMETER") healthcheckUrl: String,
): JsonObject {
    val candidates = JsonArray().apply { candidateRoutes.forEach(::add) }
    return JsonObject().apply {
        addProperty("type", "selector")
        addProperty("tag", "auto")
        add("outbounds", candidates)
        if (candidateRoutes.isNotEmpty()) {
            addProperty("default", candidateRoutes.first())
        }
    }
}

fun orderWindowsRoutesByHealth(
    candidateRoutes: List<String>,
    snapshot: WindowsRouteHealthSnapshot,
): List<String> {
    val candidates = candidateRoutes
        .map(::normalizeRouteId)
        .filter { it.isNotBlank() }
        .distinct()
    if (candidates.isEmpty() || snapshot.isEmpty()) return candidates

    return selectSmartRoute(
        candidates.mapIndexed { index, routeId ->
            SmartRouteCandidate(
                routeId = routeId,
                declaredOrder = index,
                fleetScore = snapshot.scoreFor(routeId),
            )
        },
        SmartRouteContext(nowEpochSeconds = 0),
    ).orderedRouteIds
}

fun preferredWindowsRouteForTelemetry(
    candidateRoutes: List<String>,
    snapshot: WindowsRouteHealthSnapshot,
): String = orderWindowsRoutesByHealth(candidateRoutes, snapshot).firstOrNull().orEmpty()

fun detectWindowsRuntimeRoute(logText: String): String {
    val pattern = Regex("outbound/[^\\r\\n\\[]+\\[(bridge|relay|naive|hy2|nl-tcp|se-hy2)]", RegexOption.IGNORE_CASE)
    return pattern.findAll(logText).lastOrNull()?.groupValues?.get(1)?.lowercase().orEmpty()
}

private fun parseRouteEntries(routes: JsonObject?): Map<String, WindowsRouteHealthEntry> {
    if (routes == null) return emptyMap()
    return routes.entrySet().mapNotNull { (routeId, value) ->
        if (!value.isJsonObject) return@mapNotNull null
        val normalizedRouteId = normalizeRouteId(routeId)
        if (normalizedRouteId.isBlank()) return@mapNotNull null
        val route = value.asJsonObject
        normalizedRouteId to WindowsRouteHealthEntry(
            routeId = normalizedRouteId,
            status = normalizeStatus(route.get("status")?.asString.orEmpty()),
            score = routeScore(route),
        )
    }.toMap()
}

private fun parseAvoidRoutes(recommendation: JsonObject?): Set<String> {
    val avoid = recommendation?.getAsJsonArray("avoid") ?: return emptySet()
    return avoid.mapNotNull { element ->
        normalizeRouteId(element.asString).takeIf { it.isNotBlank() }
    }.toSet()
}

private fun routeScore(route: JsonObject): Int {
    val rawScore = runCatching { route.get("score")?.asInt }.getOrNull()
    if (rawScore != null) return rawScore.coerceIn(0, 100)
    return when (normalizeStatus(route.get("status")?.asString.orEmpty())) {
        "healthy" -> 100
        "degraded" -> 35
        "blocked", "down", "disabled" -> 0
        else -> DEFAULT_UNKNOWN_ROUTE_SCORE
    }
}

private fun normalizeRouteId(value: String): String =
    value.trim().lowercase().takeIf { it in KNOWN_ROUTE_IDS }.orEmpty()

private fun normalizeStatus(value: String): String =
    value.trim().lowercase().ifBlank { "unknown" }

private val KNOWN_ROUTE_IDS = setOf("bridge", "relay", "naive", "hy2", "nl-tcp", "se-hy2", "xhttp")
private val BLOCKED_ROUTE_STATUSES = setOf("blocked", "down", "disabled")
private const val DEFAULT_UNKNOWN_ROUTE_SCORE = 50
