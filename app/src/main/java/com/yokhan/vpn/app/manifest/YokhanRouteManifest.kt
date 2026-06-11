package com.yokhan.gates.app.manifest

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

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

    fun activeRouteLinks(): List<String> {
        val candidates = routes
            .filter { it.enabled && it.appPrimary && it.link.isNotBlank() }

        val usable = candidates
            .filterNot { it.healthStatus() in blockedHealthStatuses }
            .ifEmpty { candidates }

        return usable
            .sortedWith(compareBy<YokhanRouteRecipe> { it.healthPenalty() }.thenBy { it.priority })
            .map { it.link }
    }

    fun toRawSubscriptionPayload(): String = activeRouteLinks().joinToString("\n")

    private fun YokhanRouteRecipe.healthStatus(): String {
        return health?.status?.lowercase()?.trim().orEmpty()
    }

    private fun YokhanRouteRecipe.healthPenalty(): Int {
        return when (healthStatus()) {
            "healthy", "unknown", "" -> 0
            "degraded" -> 1_000
            "blocked", "down", "disabled" -> 10_000
            else -> 0
        }
    }

    private companion object {
        val blockedHealthStatuses = setOf("blocked", "down", "disabled")
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
    val ruDirect: YokhanRuDirectPolicy?,
    val rawCompatibility: YokhanRawCompatibilityPolicy?,
    val client: YokhanClientPolicy?,
    val labRoutes: List<YokhanLabRoutePolicy>,
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
            ruDirect = ruDirect,
            rawCompatibility = rawCompatibility,
            client = parseClientPolicy(policy.optJSONObject("client")),
            labRoutes = parseLabRoutes(policy.optJSONArray("lab_routes")),
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
            is String -> JSONObject.quote(value)
            is Number -> JSONObject.numberToString(value)
            is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
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
            "${JSONObject.quote(key)}:${canonical(obj.get(key))}"
        }
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
