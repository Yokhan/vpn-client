package com.yokhan.gates.app.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.nekohasekai.sagernet.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val payload = JSONObject()
            .put("platform", "android")
            .put("package", BuildConfig.APPLICATION_ID)
            .put("version_name", BuildConfig.VERSION_NAME)
            .put("version_code", BuildConfig.VERSION_CODE)
            .put("message", message.take(MAX_MESSAGE_LENGTH))
            .put("diagnostics", JSONObject(diagnosticsJson))

        val localFile = saveLocalCopy(appContext, payload)
        val failures = mutableListOf<String>()
        for (url in reportUrls()) {
            val result = try {
                postJson(appContext, url, payload.toString())
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
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 12000
}
