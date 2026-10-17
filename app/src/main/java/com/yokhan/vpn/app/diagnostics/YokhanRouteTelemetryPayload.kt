package com.yokhan.gates.app.diagnostics

import com.yokhan.gates.shared.domain.routing.ConnectionFailureStage
import com.yokhan.gates.shared.domain.routing.ConnectionQualityClass
import com.yokhan.gates.shared.domain.routing.ConnectionQualityPolicy
import com.yokhan.gates.shared.domain.routing.ConnectionQualitySample
import com.yokhan.gates.shared.domain.routing.classifyConnectionQuality
import org.json.JSONArray
import org.json.JSONObject

object YokhanRouteTelemetryPayload {

    fun fromDiagnostics(diagnostics: JSONObject): JSONObject? {
        val payload = attach(JSONObject(), diagnostics)
        return payload.takeIf { it.has("active_route") }
    }

    fun attach(target: JSONObject, diagnostics: JSONObject): JSONObject {
        val route = activeRoute(diagnostics)
        if (route.isBlank()) return target
        val network = diagnostics.optJSONObject("runtime")
            ?.optJSONObject("network")
            ?: JSONObject()
        val probes = probePayload(diagnostics.optJSONArray("probes"))
        val qualitySamples = qualitySamplePayload(diagnostics.optJSONArray("sustained"))
        val qualityClass = qualityClassFor(qualitySamples, qualityPolicy(diagnostics))

        return target
            .put("schema", TELEMETRY_SCHEMA)
            .put("active_route", route)
            .put("result", resultFor(probes, qualityClass))
            .put("network_type", networkType(network.optJSONArray("transports")))
            .put("carrier", carrier(network))
            .put("error_code", dominantError(probes))
            .put("failure_stage", failureStageFor(qualityClass))
            .put("path", routePath(diagnostics, route))
            .put("quality_samples", JSONArray(qualitySamples.map { it.toJson() }))
            .put("probes", probes)
    }

    private fun activeRoute(diagnostics: JSONObject): String {
        val routes = diagnostics.optJSONObject("routes") ?: return ""
        return routes.optString("active_route_family")
            .ifBlank { routes.optString("active_protocol") }
            .lowercase()
    }

    private fun networkType(transports: JSONArray?): String {
        val values = stringArray(transports).map { it.lowercase() }
        return when {
            values.any { "wifi" in it } -> "wifi"
            values.any { "cell" in it || "mobile" in it } -> "cellular"
            values.any { "ethernet" in it } -> "ethernet"
            else -> values.firstOrNull().orEmpty().ifBlank { "unknown" }
        }
    }

    private fun carrier(network: JSONObject): String {
        val raw = network.optString("carrier_name")
            .ifBlank { network.optString("sim_operator_name") }
            .ifBlank { network.optString("network_operator") }
            .lowercase()
        return when {
            "beeline" in raw || "билайн" in raw -> "beeline"
            "mts" in raw || "мтс" in raw -> "mts"
            "megafon" in raw || "мегафон" in raw -> "megafon"
            "tele2" in raw || "теле2" in raw || raw == "t2" -> "t2"
            "yota" in raw || "йота" in raw -> "yota"
            "rostelecom" in raw || "ростелеком" in raw -> "rostelecom"
            raw.isBlank() -> "unknown"
            else -> "other"
        }
    }

    private fun probePayload(probes: JSONArray?): JSONObject {
        val output = JSONObject()
        if (probes == null) return output
        val count = minOf(probes.length(), MAX_PROBES)
        for (index in 0 until count) {
            val item = probes.optJSONObject(index) ?: continue
            val id = item.optString("id").ifBlank { "probe_$index" }
            output.put(
                id,
                JSONObject()
                    .put("ok", item.optBoolean("ok"))
                    .put("latency_ms", item.optLong("elapsed_ms"))
                    .put("error_code", item.optString("error").take(MAX_TEXT)),
            )
        }
        return output
    }

    private fun resultFor(
        probes: JSONObject,
        qualityClass: ConnectionQualityClass,
    ): String {
        when (qualityClass) {
            ConnectionQualityClass.Healthy -> return "success"
            ConnectionQualityClass.Degraded, ConnectionQualityClass.Stalled -> return "degraded"
            ConnectionQualityClass.Down -> return "failure"
            ConnectionQualityClass.Unknown -> Unit
        }
        if (probes.length() == 0) return "unknown"
        val failed = probes.keys().asSequence().count { key ->
            probes.optJSONObject(key)?.optBoolean("ok") == false
        }
        return when {
            failed == 0 -> "success"
            failed == probes.length() -> "failure"
            else -> "degraded"
        }
    }

    private fun qualitySamplePayload(sustained: JSONArray?): List<TelemetryQualitySample> {
        if (sustained == null) return emptyList()
        return (0 until minOf(sustained.length(), MAX_QUALITY_SAMPLES)).mapNotNull { index ->
            sustained.optJSONObject(index)?.toQualitySample()
        }.filter { it.category == FOREIGN_DOWNLOAD_CATEGORY }
    }

    private fun JSONObject.toQualitySample(): TelemetryQualitySample? {
        val targetId = optString("id").lowercase().takeIf { it in QUALITY_TARGET_IDS }
            ?: return null
        val attempts = optInt("attempts").coerceAtLeast(0)
        val failures = optInt("failures").coerceAtLeast(0)
        val bytes = optLong("bytes_read_total").coerceAtLeast(0) / attempts.coerceAtLeast(1)
        val errors = stringArray(optJSONArray("error_samples"))
        return TelemetryQualitySample(
            targetId = targetId,
            category = optString("category"),
            isSuccessful = attempts > 0 && failures == 0,
            ttfbMs = optLong("p95_first_byte_ms").coerceAtLeast(0),
            durationMs = optLong("p95_elapsed_ms").coerceAtLeast(0),
            bytesTransferred = bytes,
            expectedBytes = optLong("sample_size_bytes").coerceAtLeast(0),
            throughputKbps = optLong("p50_kbps").coerceAtLeast(0),
            isTimedOut = errors.any { "timeout" in it.lowercase() || "timed out" in it.lowercase() },
            errorCode = errorCode(errors.firstOrNull().orEmpty()),
        )
    }

    private fun qualityClassFor(
        samples: List<TelemetryQualitySample>,
        policy: ConnectionQualityPolicy,
    ): ConnectionQualityClass {
        val classes = samples.map { classifyConnectionQuality(it.toDomain(), policy).qualityClass }
        return QUALITY_PRIORITY.firstOrNull { it in classes } ?: ConnectionQualityClass.Unknown
    }

    private fun qualityPolicy(diagnostics: JSONObject): ConnectionQualityPolicy {
        val manifest = diagnostics.optJSONObject("manifest") ?: JSONObject()
        return ConnectionQualityPolicy(
            degradedThroughputKbps = manifest
                .optLong("quality_degraded_throughput_kbps", 256L).coerceAtLeast(1),
            degradedTtfbMs = manifest.optLong("quality_degraded_ttfb_ms", 3_000L)
                .coerceAtLeast(1),
            stallMaxBytes = manifest.optLong("quality_stall_max_bytes", 32_768L)
                .coerceAtLeast(0),
            stallMinDurationMs = manifest.optLong("quality_stall_min_duration_ms", 5_000L)
                .coerceAtLeast(1),
        )
    }

    private fun failureStageFor(qualityClass: ConnectionQualityClass): String = when (qualityClass) {
        ConnectionQualityClass.Degraded, ConnectionQualityClass.Stalled,
        ConnectionQualityClass.Down -> "transfer"
        else -> "unknown"
    }

    private fun routePath(diagnostics: JSONObject, routeId: String): JSONObject {
        val routes = diagnostics.optJSONObject("routes") ?: JSONObject()
        return JSONObject()
            .put("edge_id", "default")
            .put("transport_id", transportId(routeId, routes.optString("active_protocol")))
    }

    private fun transportId(routeId: String, protocol: String): String = when (routeId) {
        "bridge", "relay" -> "vless-reality-tcp"
        "hy2" -> "hysteria2"
        "naive" -> "naive-https"
        "direct" -> "direct"
        else -> protocol.lowercase().takeIf { it in QUALITY_TRANSPORT_IDS } ?: "unknown"
    }

    private fun errorCode(value: String): String = when {
        value.isBlank() -> ""
        "timeout" in value.lowercase() || "timed out" in value.lowercase() -> "timeout"
        "reset" in value.lowercase() -> "reset"
        "tls" in value.lowercase() || "certificate" in value.lowercase() -> "tls"
        else -> "other"
    }

    private fun dominantError(probes: JSONObject): String {
        return probes.keys().asSequence()
            .mapNotNull { probes.optJSONObject(it)?.optString("error_code") }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun stringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotBlank() }
        }
    }

    private const val MAX_PROBES = 32
    private const val MAX_QUALITY_SAMPLES = 8
    private const val MAX_TEXT = 120
    private const val TELEMETRY_SCHEMA = "gates-route-telemetry-v2"
    private const val FOREIGN_DOWNLOAD_CATEGORY = "foreign_download"
    private val QUALITY_TARGET_IDS = setOf("youtube_cdn_static", "telegram_static")
    private val QUALITY_TRANSPORT_IDS = setOf(
        "vless-reality-tcp",
        "hysteria2",
        "naive-https",
        "direct",
    )
    private val QUALITY_PRIORITY = listOf(
        ConnectionQualityClass.Stalled,
        ConnectionQualityClass.Down,
        ConnectionQualityClass.Degraded,
        ConnectionQualityClass.Healthy,
        ConnectionQualityClass.Unknown,
    )
}

private data class TelemetryQualitySample(
    val targetId: String,
    val category: String,
    val isSuccessful: Boolean,
    val ttfbMs: Long,
    val durationMs: Long,
    val bytesTransferred: Long,
    val expectedBytes: Long,
    val throughputKbps: Long,
    val isTimedOut: Boolean,
    val errorCode: String,
) {
    fun toDomain() = ConnectionQualitySample(
        targetId = targetId,
        isSuccessful = isSuccessful,
        failureStage = ConnectionFailureStage.Transfer,
        ttfbMs = ttfbMs,
        durationMs = durationMs,
        bytesTransferred = bytesTransferred,
        throughputKbps = throughputKbps,
        isTimedOut = isTimedOut,
    )

    fun toJson(): JSONObject = JSONObject()
        .put("target_id", targetId)
        .put("ok", isSuccessful)
        .put("failure_stage", "transfer")
        .put("ttfb_ms", ttfbMs)
        .put("duration_ms", durationMs)
        .put("bytes_transferred", bytesTransferred)
        .put("expected_bytes", expectedBytes)
        .put("throughput_kbps", throughputKbps)
        .put("timed_out", isTimedOut)
        .put("error_code", errorCode)
}
