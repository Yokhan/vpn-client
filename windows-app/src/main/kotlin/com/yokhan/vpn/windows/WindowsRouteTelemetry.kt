package com.yokhan.gates.windows

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.yokhan.gates.shared.domain.routing.ConnectionFailureStage
import com.yokhan.gates.shared.domain.routing.ConnectionQualityClass
import com.yokhan.gates.shared.domain.routing.ConnectionQualitySample
import com.yokhan.gates.shared.domain.routing.classifyConnectionQuality

data class WindowsCurlQualitySample(
    val targetId: String,
    val isSuccessful: Boolean,
    val ttfbMs: Long,
    val durationMs: Long,
    val bytesTransferred: Long,
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
}

fun parseWindowsCurlQualitySample(
    url: String,
    result: RuntimeCommandResult,
): WindowsCurlQualitySample? {
    val targetId = targetIdForUrl(url) ?: return null
    val values = CURL_VALUE_PATTERN.findAll(result.output).associate {
        it.groupValues[1] to it.groupValues[2]
    }
    val durationMs = values["total"].secondsToMillis()
    val output = result.output.lowercase()
    return WindowsCurlQualitySample(
        targetId = targetId,
        isSuccessful = result.exitCode == 0,
        ttfbMs = values["ttfb"].secondsToMillis(),
        durationMs = durationMs,
        bytesTransferred = values["bytes"].decimalToLong(),
        throughputKbps = (values["speed"].decimalToLong() * BITS_PER_BYTE) / BYTES_PER_KILOBYTE,
        isTimedOut = result.exitCode != 0 && ("timeout" in output || "timed out" in output),
        errorCode = errorCodeFor(result),
    )
}

fun buildWindowsQualityTelemetryPayload(
    routeId: String,
    samples: List<WindowsCurlQualitySample>,
): JsonObject {
    val qualityClass = qualityClassFor(samples)
    return basePayload(routeId, qualityClass).apply {
        add("quality_samples", JsonArray().apply { samples.forEach { add(it.toJson()) } })
    }
}

fun buildWindowsUnknownTelemetryPayload(routeId: String): JsonObject =
    basePayload(routeId, ConnectionQualityClass.Unknown).apply {
        addProperty("failure_stage", "unknown")
        add("quality_samples", JsonArray())
    }

private fun basePayload(routeId: String, qualityClass: ConnectionQualityClass) = JsonObject().apply {
    addProperty("schema", TELEMETRY_SCHEMA)
    if (routeId.isNotBlank()) addProperty("active_route", routeId)
    addProperty("result", resultFor(qualityClass))
    addProperty("network_type", "windows")
    addProperty("failure_stage", failureStageFor(qualityClass))
    add(
        "path",
        JsonObject().apply {
            addProperty("edge_id", "default")
            addProperty("transport_id", transportId(routeId))
        },
    )
}

private fun WindowsCurlQualitySample.toJson() = JsonObject().apply {
    addProperty("target_id", targetId)
    addProperty("ok", isSuccessful)
    addProperty("failure_stage", "transfer")
    addProperty("ttfb_ms", ttfbMs)
    addProperty("duration_ms", durationMs)
    addProperty("bytes_transferred", bytesTransferred)
    addProperty("throughput_kbps", throughputKbps)
    addProperty("timed_out", isTimedOut)
    addProperty("error_code", errorCode)
}

private fun qualityClassFor(samples: List<WindowsCurlQualitySample>): ConnectionQualityClass {
    val classes = samples.map { classifyConnectionQuality(it.toDomain()).qualityClass }
    return QUALITY_PRIORITY.firstOrNull { it in classes } ?: ConnectionQualityClass.Unknown
}

private fun targetIdForUrl(url: String): String? = when {
    "i.ytimg.com" in url -> "youtube_cdn_static"
    "youtube.com/generate_204" in url -> "youtube_204"
    "google.com/generate_204" in url || "gstatic.com/generate_204" in url -> "gstatic_204"
    "api.telegram.org" in url -> "telegram_home"
    "cloak.yokhanfitness.ru" in url -> "cloak_root"
    "naive.yokhanfitness.ru" in url -> "naive_home"
    else -> null
}

private fun resultFor(qualityClass: ConnectionQualityClass): String = when (qualityClass) {
    ConnectionQualityClass.Healthy -> "success"
    ConnectionQualityClass.Degraded, ConnectionQualityClass.Stalled -> "degraded"
    ConnectionQualityClass.Down -> "failure"
    ConnectionQualityClass.Unknown -> "unknown"
}

private fun failureStageFor(qualityClass: ConnectionQualityClass): String = when (qualityClass) {
    ConnectionQualityClass.Degraded, ConnectionQualityClass.Stalled,
    ConnectionQualityClass.Down -> "transfer"
    else -> "unknown"
}

private fun transportId(routeId: String): String = when (routeId) {
    "bridge", "relay" -> "vless-reality-tcp"
    "hy2" -> "hysteria2"
    "naive" -> "naive-https"
    "direct" -> "direct"
    else -> "unknown"
}

private fun errorCodeFor(result: RuntimeCommandResult): String = when {
    result.exitCode == 0 -> ""
    "timeout" in result.output.lowercase() || "timed out" in result.output.lowercase() -> "timeout"
    "reset" in result.output.lowercase() -> "reset"
    else -> "other"
}

private fun String?.secondsToMillis(): Long =
    ((this?.toDoubleOrNull() ?: 0.0) * MILLIS_PER_SECOND).toLong().coerceAtLeast(0)

private fun String?.decimalToLong(): Long = (this?.toDoubleOrNull() ?: 0.0).toLong().coerceAtLeast(0)

private val CURL_VALUE_PATTERN = Regex("(ttfb|total|bytes|speed)=([0-9.]+)")
private const val TELEMETRY_SCHEMA = "gates-route-telemetry-v2"
private const val BITS_PER_BYTE = 8L
private const val BYTES_PER_KILOBYTE = 1_000L
private const val MILLIS_PER_SECOND = 1_000.0
private val QUALITY_PRIORITY = listOf(
    ConnectionQualityClass.Stalled,
    ConnectionQualityClass.Down,
    ConnectionQualityClass.Degraded,
    ConnectionQualityClass.Healthy,
    ConnectionQualityClass.Unknown,
)
