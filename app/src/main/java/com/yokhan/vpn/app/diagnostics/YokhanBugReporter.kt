package com.yokhan.gates.app.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.nekohasekai.sagernet.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object YokhanBugReporter {

    suspend fun send(
        context: Context,
        message: String,
        diagnosticsJson: String,
    ): BugReportResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val diagnostics = parseDiagnostics(diagnosticsJson)
        val fullPayload = buildPayload(message, diagnostics)
        val remotePayload = if (payloadSize(fullPayload) > MAX_REMOTE_PAYLOAD_BYTES) {
            buildPayload(message, compactDiagnostics(diagnostics))
        } else {
            fullPayload
        }

        val localFile = saveLocalCopy(appContext, fullPayload)
        val failures = mutableListOf<String>()
        for (url in reportUrls()) {
            val result = try {
                postJson(appContext, url, remotePayload.toString())
            } catch (error: Exception) {
                failures += "$url: ${error.message ?: error.javaClass.simpleName}"
                continue
            }
            if (result.statusCode in 200..299) {
                val reportId = runCatching {
                    JSONObject(result.body).optString("report_id")
                }.getOrDefault("")
                return@withContext BugReportResult(
                    uploaded = true,
                    reportId = reportId,
                    localFile = localFile,
                    details = result.body,
                )
            }
            failures += "$url: HTTP ${result.statusCode} ${result.body.take(300)}"
        }

        BugReportResult(
            uploaded = false,
            reportId = "",
            localFile = localFile,
            details = failures.joinToString("\n"),
        )
    }

    private fun buildPayload(message: String, diagnostics: JSONObject): JSONObject {
        return JSONObject()
            .put("platform", "android")
            .put("package", BuildConfig.APPLICATION_ID)
            .put("version_name", BuildConfig.VERSION_NAME)
            .put("version_code", BuildConfig.VERSION_CODE)
            .put("message", message.take(MAX_MESSAGE_LENGTH))
            .put("diagnostics", diagnostics)
    }

    private fun parseDiagnostics(diagnosticsJson: String): JSONObject {
        return runCatching { JSONObject(diagnosticsJson) }.getOrElse { error ->
            JSONObject()
                .put("parse_error", error.message ?: error.javaClass.simpleName)
                .put("raw", diagnosticsJson.take(MAX_DIAGNOSTICS_RAW_CHARS))
        }
    }

    private fun payloadSize(payload: JSONObject): Int {
        return payload.toString().toByteArray(StandardCharsets.UTF_8).size
    }

    private fun compactDiagnostics(source: JSONObject): JSONObject {
        val compact = JSONObject()
            .put("compact", true)
        copyInto(source, compact, listOf("captured_at_millis"))

        source.optJSONObject("app")?.let {
            compact.put("app", copyObject(it, listOf("package", "version_name", "version_code", "sdk")))
        }

        source.optJSONObject("runtime")?.let { runtime ->
            val runtimeCompact = copyObject(
                runtime,
                listOf(
                    "service_state",
                    "service_mode",
                    "selected_proxy_id",
                    "selected_group_id",
                    "mixed_port",
                    "stealth_mode",
                    "ru_bypass_enabled",
                    "boot_elapsed_realtime_ms",
                    "always_on_vpn_package",
                    "always_on_vpn_lockdown",
                    "always_on_vpn_matches_current_package",
                    "always_on_vpn_matches_production_package",
                    "battery_optimization_ignored",
                    "production_battery_optimization_ignored",
                ),
            )
            runtime.optJSONObject("network")?.let { network ->
                runtimeCompact.put(
                    "network",
                    copyObject(
                        network,
                        listOf(
                            "transports",
                            "downstream_kbps",
                            "upstream_kbps",
                            "metered",
                            "validated",
                            "vpn_transport_active",
                            "carrier_name",
                            "sim_operator_name",
                            "network_operator",
                        ),
                    ),
                )
            }
            compact.put("runtime", runtimeCompact)
        }

        source.optJSONObject("routes")?.let { routes ->
            val routesCompact = copyObject(
                routes,
                listOf(
                    "group_name",
                    "group_type",
                    "active_profile",
                    "active_protocol",
                    "profile_count",
                    "active_route_family",
                    "detected_route_order",
                    "missing_route_families",
                    "duplicate_route_families",
                    "route_order_ok",
                ),
            )
            routes.optJSONArray("profiles")?.let { profiles ->
                routesCompact.put(
                    "profiles",
                    compactArray(
                        profiles,
                        listOf("id", "name", "protocol", "route_family", "user_order", "active"),
                        limit = 24,
                    ),
                )
            }
            compact.put("routes", routesCompact)
        }

        source.optJSONObject("manifest")?.let { manifest ->
            val manifestCompact = copyObject(
                manifest,
                listOf(
                    "enabled",
                    "public_keys_configured",
                    "requested_version",
                    "cache_present",
                    "cached_version",
                    "cached_manifest_id",
                    "cached_channel",
                    "cached_expires_at",
                    "cached_active_routes",
                    "policy_version",
                    "policy_route_order",
                    "ru_direct_version",
                    "raw_compatibility_isolated",
                    "dns_policy_mode",
                    "fingerprint_mode",
                    "fingerprint_default",
                    "fingerprint_allowed",
                    "tls_fragmentation_mode",
                    "diagnostics_expected_exit",
                    "diagnostics_probe_targets",
                    "previous_known_good_enabled",
                ),
            )
            manifest.optJSONArray("cached_route_endpoints")?.let { endpoints ->
                manifestCompact.put(
                    "cached_route_endpoints",
                    compactArray(
                        endpoints,
                        listOf("id", "policy_version", "ingress", "expected_exit", "health_status"),
                        limit = 16,
                    ),
                )
            }
            compact.put("manifest", manifestCompact)
        }

        compact.put(
            "probes",
            compactArray(
                source.optJSONArray("probes"),
                listOf("id", "name", "category", "ok", "elapsed_ms", "transport", "status_code", "error"),
                limit = 20,
            ),
        )
        compact.put(
            "stability",
            compactArray(
                source.optJSONArray("stability"),
                listOf(
                    "id",
                    "name",
                    "category",
                    "attempts",
                    "successes",
                    "failures",
                    "success_rate_percent",
                    "p95_ms",
                    "max_ms",
                    "status_codes",
                    "error_samples",
                ),
                limit = 20,
            ),
        )
        compact.put(
            "sustained",
            compactArray(
                source.optJSONArray("sustained"),
                listOf(
                    "id",
                    "name",
                    "category",
                    "attempts",
                    "successes",
                    "failures",
                    "bytes_read_total",
                    "min_kbps",
                    "p50_kbps",
                    "max_kbps",
                    "p95_elapsed_ms",
                    "p95_first_byte_ms",
                    "status_codes",
                    "error_samples",
                ),
                limit = 20,
            ),
        )
        compact.put(
            "udp_connectivity",
            compactArray(
                source.optJSONArray("udp_connectivity"),
                listOf("id", "name", "category", "host", "port", "ok", "elapsed_ms", "error", "note"),
                limit = 20,
            ),
        )
        return compact
    }

    private fun copyObject(source: JSONObject, keys: List<String>): JSONObject {
        return JSONObject().also { target -> copyInto(source, target, keys) }
    }

    private fun copyInto(source: JSONObject, target: JSONObject, keys: List<String>) {
        keys.forEach { key ->
            if (source.has(key)) {
                target.put(key, source.opt(key) ?: JSONObject.NULL)
            }
        }
    }

    private fun compactArray(source: JSONArray?, keys: List<String>, limit: Int): JSONArray {
        val target = JSONArray()
        if (source == null) return target
        val count = minOf(source.length(), limit)
        for (index in 0 until count) {
            val value = source.optJSONObject(index)
            if (value != null) {
                target.put(copyObject(value, keys))
            } else {
                target.put(source.opt(index))
            }
        }
        if (source.length() > limit) {
            target.put(JSONObject().put("truncated_count", source.length() - limit))
        }
        return target
    }

    private fun postJson(context: Context, url: String, body: String): HttpResult {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val connection = openReportConnection(context, url)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("X-Yokhan-Client", "GATES-Android")
            connection.setRequestProperty("X-Yokhan-App-Version", BuildConfig.VERSION_NAME)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val responseBody = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            return HttpResult(status, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun openReportConnection(context: Context, url: String): HttpURLConnection {
        val target = URL(url)
        physicalNetworks(context).forEach { network ->
            val connection = runCatching {
                network.openConnection(target) as HttpURLConnection
            }.getOrNull()
            if (connection != null) return connection
        }
        return target.openConnection() as HttpURLConnection
    }

    private fun physicalNetworks(context: Context): List<Network> {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return manager.allNetworks.filter { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@filter false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private fun saveLocalCopy(context: Context, payload: JSONObject): File {
        val dir = File(context.filesDir, "bug_reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "yokhan_bug_report_$stamp.json").also { file ->
            file.writeText(payload.toString(2), StandardCharsets.UTF_8)
        }
    }

    private fun reportUrls(): List<String> {
        val base = BuildConfig.YOKHAN_APP_API_BASE_URL.trimEnd('/')
        return listOf(
            "$base/api/app/bug-report",
            "https://yokhanfitness.ru/api/app/bug-report",
        ).distinct()
    }

    data class BugReportResult(
        val uploaded: Boolean,
        val reportId: String,
        val localFile: File,
        val details: String,
    )

    private data class HttpResult(
        val statusCode: Int,
        val body: String,
    )

    private const val MAX_MESSAGE_LENGTH = 2000
    private const val MAX_DIAGNOSTICS_RAW_CHARS = 12000
    private const val MAX_REMOTE_PAYLOAD_BYTES = 56 * 1024
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 12000
}
