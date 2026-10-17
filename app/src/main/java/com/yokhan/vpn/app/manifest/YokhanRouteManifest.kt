package com.yokhan.gates.app.manifest

import android.net.Uri
import com.yokhan.vpn.shared.domain.SmartRouteCandidate
import com.yokhan.vpn.shared.domain.SmartRouteContext
import com.yokhan.vpn.shared.domain.selectSmartRoute
import com.yokhan.gates.app.routing.YokhanRouteLocalObservationState
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class YokhanRouteManifest(
    val version: Int,
    val manifestId: String,
    val channel: String,
    val subject: YokhanRouteManifestSubject,
    val issuedAt: Long,
    val expiresAt: Long,
    val minAppVersion: Int,
    val routes: List<YokhanRouteRecipe>,
    val fallback: YokhanRouteFallback,
    val policy: YokhanManifestPolicy?,
    val diagnostics: YokhanManifestDiagnostics?,
    val signature: YokhanRouteManifestSignature?,
) {
    fun isExpired(nowSeconds: Long): Boolean = expiresAt <= nowSeconds

    fun activeRouteLinks(
        localState: YokhanRouteLocalObservationState? = null,
        nowSeconds: Long = issuedAt,
    ): List<String> {
        val candidates = routes
            .filter { it.enabled && it.appPrimary && it.link.isNotBlank() }
        val routeOrder = policy?.routeOrder
            ?.mapIndexed { index, routeId -> routeId.lowercase().trim() to index }
            ?.toMap()
            .orEmpty()
        val fallbackOrder = routes
            .mapIndexed { index, route -> route.id.lowercase().trim() to index }
            .toMap()
        val byId = candidates.associateBy { it.id.lowercase().trim() }
        val smartCandidates = candidates.map { route ->
                val routeId = route.id.lowercase().trim()
                SmartRouteCandidate(
                    routeId = routeId,
                    declaredOrder = routeOrder[routeId] ?: fallbackOrder[routeId] ?: Int.MAX_VALUE,
                    fleetScore = route.healthScore(),
                    isEnabled = route.enabled,
                )
            }
        val decision = selectSmartRoute(
            localState?.toSmartRouteCandidates(smartCandidates, nowSeconds) ?: smartCandidates,
            localState?.toSmartRouteContext(
                nowEpochSeconds = nowSeconds,
                switchCooldownSeconds = policy?.selection?.switchCooldownSeconds ?: 600L,
                minimumSwitchImprovement = policy?.selection?.switchMargin ?: 15,
            )
                ?: SmartRouteContext(nowEpochSeconds = nowSeconds),
        )
        return decision.orderedRouteIds.mapNotNull { routeId -> byId[routeId]?.link }
    }

    fun toRawSubscriptionPayload(): String = activeRouteLinks().joinToString("\n")

    fun routingFingerprint(): String {
        val shape = routes.joinToString("|") { route ->
            listOf(
                route.id,
                route.family,
                route.policyVersion,
                route.enabled,
                route.ingress?.host.orEmpty(),
                route.ingress?.port ?: 0,
                route.ingress?.transport.orEmpty(),
                route.ingress?.sni.orEmpty(),
            ).joinToString(":")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(shape.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun YokhanRouteRecipe.healthStatus(): String {
        return health?.status?.lowercase()?.trim().orEmpty()
    }

    private fun YokhanRouteRecipe.healthScore(): Int {
        health?.score?.let { return it.coerceIn(0, 100) }
        return when (healthStatus()) {
            "healthy" -> 100
            "unknown", "" -> 50
            "degraded" -> 35
            "blocked", "down", "disabled" -> 0
            else -> 50
        }
    }

}

data class YokhanRouteManifestSubject(
    val username: String,
    val device: String,
)

data class YokhanRouteRecipe(
    val id: String,
    val family: String,
    val label: String,
    val enabled: Boolean,
    val priority: Int,
    val stability: String,
    val compatibility: Boolean,
    val appPrimary: Boolean,
    val link: String,
    val policyVersion: Int,
    val ingress: YokhanRouteEndpoint?,
    val expectedExit: YokhanRouteEndpoint?,
    val health: YokhanRouteHealth?,
)

data class YokhanRouteFallback(
    val usePreviousKnownGood: Boolean,
    val compatibilitySubscriptionUrl: String,
    val previousKnownGood: YokhanPreviousKnownGood?,
)

data class YokhanPreviousKnownGood(
    val enabled: Boolean,
    val maxAgeSeconds: Long,
)

data class YokhanManifestPolicy(
    val version: Int,
    val routeOrder: List<String>,
    val selection: YokhanRouteSelectionPolicy?,
    val ruDirect: YokhanRuDirectPolicy?,
    val rawCompatibility: YokhanRawCompatibilityPolicy?,
    val client: YokhanClientPolicy?,
    val preferredRoute: String,
    val labRoutes: List<YokhanLabRoutePolicy>,
)

data class YokhanRouteSelectionPolicy(
    val mode: String,
    val tieBreaker: String,
    val avoidStatuses: List<String>,
    val probeBeforeConnect: Boolean,
    val localProbeLimit: Int,
    val usePreviousKnownGood: Boolean,
    val serverHealthRole: String,
    val switchMargin: Int,
    val switchCooldownSeconds: Long,
    val challengerSuccessesRequired: Int,
    val quarantineInitialSeconds: Long,
    val quarantineMaxSeconds: Long,
    val networkBuckets: List<String>,
    val quality: YokhanRouteQualityPolicy?,
)

data class YokhanRouteQualityPolicy(
    val telemetrySchema: Int,
    val evaluationMode: String,
    val autoSwitchEnabled: Boolean,
    val activeProbeEnabled: Boolean,
    val degradedThroughputKbps: Long,
    val degradedTtfbMs: Long,
    val stallMaxBytes: Long,
    val stallMinDurationMs: Long,
    val softFailuresRequired: Int,
    val verifyAfterSwitch: Boolean,
)

data class YokhanRuDirectPolicy(
    val required: Boolean,
    val version: String,
)

data class YokhanRawCompatibilityPolicy(
    val isolated: Boolean,
    val risk: String,
)

data class YokhanClientPolicy(
    val version: String,
    val dns: YokhanDnsPolicy?,
    val fingerprint: YokhanFingerprintPolicy?,
    val tlsFragmentation: YokhanTlsFragmentationPolicy?,
)

data class YokhanDnsPolicy(
    val mode: String,
    val foreignResolver: String,
    val ruDirectResolver: String,
)

data class YokhanFingerprintPolicy(
    val mode: String,
    val default: String,
    val allowed: List<String>,
    val rollout: String,
)

data class YokhanTlsFragmentationPolicy(
    val mode: String,
    val rollout: String,
)

data class YokhanLabRoutePolicy(
    val id: String,
    val family: String,
    val enabled: Boolean,
    val channel: String,
)

data class YokhanManifestDiagnostics(
    val expectedExit: YokhanRouteEndpoint?,
    val requiredReportFields: List<String>,
    val probeTargets: List<String>,
)

data class YokhanRouteEndpoint(
    val role: String,
    val host: String,
    val port: Int,
    val transport: String,
    val sni: String,
)

data class YokhanRouteHealth(
    val status: String,
    val lastCheckedAt: Long,
    val score: Int?,
)

data class YokhanRouteManifestSignature(
    val alg: String,
    val keyId: String,
    val value: String,
)

object YokhanRouteManifestParser {

    fun parse(rawJson: String): YokhanRouteManifest {
        val root = JSONObject(rawJson)
        val subject = root.optJSONObject("subject") ?: JSONObject()
        val fallback = root.optJSONObject("fallback") ?: JSONObject()
        val signature = root.optJSONObject("signature")?.let {
            YokhanRouteManifestSignature(
                alg = it.optString("alg"),
                keyId = it.optString("key_id"),
                value = it.optString("value"),
            )
        }

        return YokhanRouteManifest(
            version = root.optInt("version"),
            manifestId = root.optString("manifest_id"),
            channel = root.optString("channel"),
            subject = YokhanRouteManifestSubject(
                username = subject.optString("username"),
                device = subject.optString("device"),
            ),
            issuedAt = root.optLong("issued_at"),
            expiresAt = root.optLong("expires_at"),
            minAppVersion = root.optInt("min_app_version"),
            routes = parseRoutes(root.optJSONArray("routes")),
            fallback = YokhanRouteFallback(
                usePreviousKnownGood = fallback.optBoolean("use_previous_known_good"),
                compatibilitySubscriptionUrl = fallback.optString("compat_subscription_url"),
                previousKnownGood = parsePreviousKnownGood(fallback.optJSONObject("previous_known_good")),
            ),
            policy = parsePolicy(root.optJSONObject("policy")),
            diagnostics = parseDiagnostics(root.optJSONObject("diagnostics")),
            signature = signature,
        )
    }

    fun canonicalUnsignedPayload(rawJson: String): ByteArray {
        return canonicalObject(JSONObject(rawJson), skipSignature = true).toByteArray(Charsets.UTF_8)
    }

    private fun parseRoutes(routes: JSONArray?): List<YokhanRouteRecipe> {
        if (routes == null) return emptyList()
        return (0 until routes.length()).mapNotNull { index ->
            val route = routes.optJSONObject(index) ?: return@mapNotNull null
            YokhanRouteRecipe(
                id = route.optString("id"),
                family = route.optString("family"),
                label = route.optString("label"),
                enabled = route.optBoolean("enabled", true),
                priority = route.optInt("priority"),
                stability = route.optString("stability"),
                compatibility = route.optBoolean("compatibility"),
                appPrimary = route.optBoolean("app_primary"),
                link = route.optString("link"),
                policyVersion = route.optInt("policy_version"),
                ingress = parseEndpoint(route.optJSONObject("ingress")),
                expectedExit = parseEndpoint(route.optJSONObject("expected_exit")),
                health = route.optJSONObject("health")?.let { health ->
                    YokhanRouteHealth(
                        status = health.optString("status"),
                        lastCheckedAt = health.optLong("last_checked_at"),
                        score = health.takeIf { it.has("score") }?.optInt("score"),
                    )
                },
            )
        }
    }

    private fun parsePreviousKnownGood(value: JSONObject?): YokhanPreviousKnownGood? {
        if (value == null) return null
        return YokhanPreviousKnownGood(
            enabled = value.optBoolean("enabled"),
            maxAgeSeconds = value.optLong("max_age_seconds"),
        )
    }

    private fun parsePolicy(policy: JSONObject?): YokhanManifestPolicy? {
        if (policy == null) return null
        val ruDirect = policy.optJSONObject("ru_direct")?.let {
            YokhanRuDirectPolicy(
                required = it.optBoolean("required"),
                version = it.optString("version"),
            )
        }
        val rawCompatibility = policy.optJSONObject("raw_compatibility")?.let {
            YokhanRawCompatibilityPolicy(
                isolated = it.optBoolean("isolated"),
                risk = it.optString("risk"),
            )
        }
        return YokhanManifestPolicy(
            version = policy.optInt("version"),
            routeOrder = parseStringArray(policy.optJSONArray("route_order")),
            selection = parseSelectionPolicy(policy.optJSONObject("selection")),
            ruDirect = ruDirect,
            rawCompatibility = rawCompatibility,
            client = parseClientPolicy(policy.optJSONObject("client")),
            preferredRoute = policy.optString("preferred_route"),
            labRoutes = parseLabRoutes(policy.optJSONArray("lab_routes")),
        )
    }

    private fun parseSelectionPolicy(value: JSONObject?): YokhanRouteSelectionPolicy? {
        if (value == null) return null
        return YokhanRouteSelectionPolicy(
            mode = value.optString("mode"),
            tieBreaker = value.optString("tie_breaker"),
            avoidStatuses = parseStringArray(value.optJSONArray("avoid_statuses")),
            probeBeforeConnect = value.optBoolean("probe_before_connect"),
            localProbeLimit = value.optInt("local_probe_limit", 2),
            usePreviousKnownGood = value.optBoolean("use_previous_known_good"),
            serverHealthRole = value.optString("server_health_role", "advisory"),
            switchMargin = value.optInt("switch_margin", 15),
            switchCooldownSeconds = value.optLong("switch_cooldown_seconds", 600L),
            challengerSuccessesRequired = value.optInt("challenger_successes_required", 2),
            quarantineInitialSeconds = value.optLong("quarantine_initial_seconds", 900L),
            quarantineMaxSeconds = value.optLong("quarantine_max_seconds", 7200L),
            networkBuckets = parseStringArray(value.optJSONArray("network_buckets")),
            quality = parseQualityPolicy(value.optJSONObject("quality")),
        )
    }

    private fun parseQualityPolicy(value: JSONObject?): YokhanRouteQualityPolicy? {
        if (value == null) return null
        return YokhanRouteQualityPolicy(
            telemetrySchema = value.optInt("telemetry_schema", 2),
            evaluationMode = value.optString("evaluation_mode", "advisory_only"),
            autoSwitchEnabled = value.optBoolean("auto_switch_enabled", false),
            activeProbeEnabled = value.optBoolean("active_probe_enabled", false),
            degradedThroughputKbps = value.optLong("degraded_throughput_kbps", 256L),
            degradedTtfbMs = value.optLong("degraded_ttfb_ms", 3_000L),
            stallMaxBytes = value.optLong("stall_max_bytes", 32_768L),
            stallMinDurationMs = value.optLong("stall_min_duration_ms", 5_000L),
            softFailuresRequired = value.optInt("soft_failures_required", 2),
            verifyAfterSwitch = value.optBoolean("verify_after_switch", true),
        )
    }

    private fun parseClientPolicy(value: JSONObject?): YokhanClientPolicy? {
        if (value == null) return null
        return YokhanClientPolicy(
            version = value.optString("version"),
            dns = value.optJSONObject("dns")?.let {
                YokhanDnsPolicy(
                    mode = it.optString("mode"),
                    foreignResolver = it.optString("foreign_resolver"),
                    ruDirectResolver = it.optString("ru_direct_resolver"),
                )
            },
            fingerprint = value.optJSONObject("fingerprint")?.let {
                YokhanFingerprintPolicy(
                    mode = it.optString("mode"),
                    default = it.optString("default"),
                    allowed = parseStringArray(it.optJSONArray("allowed")),
                    rollout = it.optString("rollout"),
                )
            },
            tlsFragmentation = value.optJSONObject("tls_fragmentation")?.let {
                YokhanTlsFragmentationPolicy(
                    mode = it.optString("mode"),
                    rollout = it.optString("rollout"),
                )
            },
        )
    }

    private fun parseLabRoutes(array: JSONArray?): List<YokhanLabRoutePolicy> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val route = array.optJSONObject(index) ?: return@mapNotNull null
            YokhanLabRoutePolicy(
                id = route.optString("id"),
                family = route.optString("family"),
                enabled = route.optBoolean("enabled"),
                channel = route.optString("channel"),
            )
        }
    }

    private fun parseDiagnostics(diagnostics: JSONObject?): YokhanManifestDiagnostics? {
        if (diagnostics == null) return null
        return YokhanManifestDiagnostics(
            expectedExit = parseEndpoint(diagnostics.optJSONObject("expected_exit")),
            requiredReportFields = parseStringArray(diagnostics.optJSONArray("required_report_fields")),
            probeTargets = parseStringArray(diagnostics.optJSONArray("probe_targets")),
        )
    }

    private fun parseEndpoint(endpoint: JSONObject?): YokhanRouteEndpoint? {
        if (endpoint == null) return null
        return YokhanRouteEndpoint(
            role = endpoint.optString("role"),
            host = endpoint.optString("host"),
            port = endpoint.optInt("port"),
            transport = endpoint.optString("transport"),
            sni = endpoint.optString("sni"),
        )
    }

    private fun parseStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotBlank() }
        }
    }

    private fun canonical(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> canonicalObject(value, skipSignature = false)
            is JSONArray -> canonicalArray(value)
            is String -> canonicalString(value)
            is Number -> JSONObject.numberToString(value)
            is Boolean -> value.toString()
            else -> canonicalString(value.toString())
        }
    }

    private fun canonicalArray(array: JSONArray): String {
        return (0 until array.length()).joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]",
        ) { index ->
            canonical(array.get(index))
        }
    }

    private fun canonicalObject(obj: JSONObject, skipSignature: Boolean): String {
        val keys = mutableListOf<String>()
        val iterator = obj.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (skipSignature && key == "signature") continue
            keys.add(key)
        }
        keys.sort()
        return keys.joinToString(
            separator = ",",
            prefix = "{",
            postfix = "}",
        ) { key ->
            "${canonicalString(key)}:${canonical(obj.get(key))}"
        }
    }

    private fun canonicalString(value: String): String {
        return JSONObject.quote(value).replace("\\/", "/")
    }
}

object YokhanRouteManifestUrls {

    fun fromSubscriptionLink(subscriptionLink: String?, manifestVersion: Int = 1): String? {
        val link = subscriptionLink?.trim().orEmpty()
        if (link.isBlank()) return null
        val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null

        val raw = link.substringBefore('#').substringBefore('?').trimEnd('/')
        if (!raw.contains("/api/sub/")) return null
        val manifestUrl = when {
            raw.endsWith("/manifest") -> raw
            raw.endsWith("/links") || raw.endsWith("/singbox") -> raw.substringBeforeLast('/') + "/manifest"
            else -> "$raw/manifest"
        }
        return withVersion(manifestUrl, manifestVersion)
    }

    private fun withVersion(manifestUrl: String, manifestVersion: Int): String {
        if (manifestVersion <= 1) return manifestUrl
        val separator = if ("?" in manifestUrl) "&" else "?"
        return "$manifestUrl${separator}v=$manifestVersion"
    }
}

sealed class YokhanRouteManifestResult {
    data class Updated(val manifest: YokhanRouteManifest) : YokhanRouteManifestResult()
    data class Cached(val manifest: YokhanRouteManifest) : YokhanRouteManifestResult()
    data class Unavailable(val reason: String, val cached: YokhanRouteManifest?) : YokhanRouteManifestResult()
}
