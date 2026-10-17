package com.yokhan.gates.app.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.ktx.useCancellable
import libcore.Libcore
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticEvent
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticEventKind
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticNetwork
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticPath
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticTarget
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticTransport
import com.yokhan.gates.shared.domain.diagnostics.IncidentStatus
import com.yokhan.gates.shared.domain.diagnostics.RecoveryDeliveryQueue
import com.yokhan.gates.shared.domain.diagnostics.RecoveryDeliveryState
import com.yokhan.gates.shared.domain.diagnostics.RecoveryDeliveryStore
import com.yokhan.gates.shared.domain.diagnostics.RecoveryReport
import com.yokhan.gates.shared.domain.diagnostics.compactRecoveryReport
import io.nekohasekai.sagernet.database.DataStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex

internal class AndroidRecoveryDeliveryStore(private val root: File) : RecoveryDeliveryStore {
    private val atomicFile = AtomicFile(File(root, STORE_FILE))

    override fun load(): RecoveryDeliveryState? = synchronized(lockFor(root)) {
        val file = atomicFile.baseFile
        validateStorePaths()
        val siblings = storeSiblings(file)
        if (siblings.none(File::exists)) return@synchronized null
        val input = atomicFile.openRead()
        val bytes = input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > RecoveryDeliveryQueue.MAX_STORE_BYTES) {
                    throw CorruptRecoveryDeliveryStoreException("delivery store exceeds limit")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        try {
            stateFromJson(JSONObject(String(bytes, StandardCharsets.UTF_8)))
        } catch (error: CorruptRecoveryDeliveryStoreException) {
            throw error
        } catch (error: Exception) {
            throw CorruptRecoveryDeliveryStoreException("delivery store is invalid", error)
        }
    }

    override fun save(state: RecoveryDeliveryState) = synchronized(lockFor(root)) {
        RecoveryDeliveryQueue.validateState(state)
        val bytes = stateToJson(state).toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= RecoveryDeliveryQueue.MAX_STORE_BYTES) { "delivery store exceeds limit" }
        val directory = root.absoluteFile
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            error("delivery store directory unavailable")
        }
        validateStorePaths()
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun validateStorePaths() {
        try {
            validatePath(root.absoluteFile)
            if (root.exists()) require(root.isDirectory) { "invalid delivery store directory" }
            val siblings = storeSiblings(atomicFile.baseFile)
            siblings.forEach(::validatePath)
            siblings.filter(File::exists).forEach { file ->
                require(file.isFile && file.length() <= RecoveryDeliveryQueue.MAX_STORE_BYTES) {
                    "invalid delivery store sidecar"
                }
            }
            require(!File(atomicFile.baseFile.path + ".new").exists()) {
                "unfinished delivery store write"
            }
        } catch (error: CorruptRecoveryDeliveryStoreException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw CorruptRecoveryDeliveryStoreException("delivery store path is invalid", error)
        }
    }

    private fun validatePath(file: File) {
        val absolute = file.absoluteFile
        if (absolute.exists() && absolute.canonicalFile != absolute) {
            throw CorruptRecoveryDeliveryStoreException("delivery store path rejected")
        }
        try {
            val mode = Os.lstat(absolute.path).st_mode
            if ((mode and OsConstants.S_IFMT) == OsConstants.S_IFLNK) {
                throw CorruptRecoveryDeliveryStoreException("delivery store symlink rejected")
            }
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.ENOENT) throw error
        }
    }

    private fun storeSiblings(base: File): List<File> = listOf(
        base,
        File(base.path + ".bak"),
        File(base.path + ".new"),
    )

    private fun stateToJson(state: RecoveryDeliveryState) = JSONObject()
        .put("schema", STORE_SCHEMA)
        .put("pending", JSONArray().also { rows -> state.pending.forEach { pending ->
            rows.put(JSONObject()
                .put("report", reportToJson(pending.report))
                .put("attempts", pending.attempts)
                .put("last_attempt_millis", pending.lastAttemptMillis)
                .put("next_attempt_millis", pending.nextAttemptMillis))
        } })
        .put("acknowledged_ids", JSONArray(state.acknowledgedIds))
        .put("capacity_reached", state.capacityReached)

    private fun stateFromJson(json: JSONObject): RecoveryDeliveryState {
        json.requireKeys("schema", "pending", "acknowledged_ids", "capacity_reached")
        require(json.requiredInt("schema") == STORE_SCHEMA)
        val pending = json.getJSONArray("pending").let { rows ->
            require(rows.length() <= RecoveryDeliveryQueue.MAX_PENDING)
            List(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                row.requireKeys("report", "attempts", "last_attempt_millis", "next_attempt_millis")
                com.yokhan.gates.shared.domain.diagnostics.PendingRecoveryReport(
                    reportFromJson(row.getJSONObject("report")),
                    row.requiredInt("attempts"),
                    row.requiredLong("last_attempt_millis"),
                    row.requiredLong("next_attempt_millis"),
                )
            }
        }
        val acknowledged = json.getJSONArray("acknowledged_ids").let { rows ->
            require(rows.length() <= RecoveryDeliveryQueue.MAX_ACKNOWLEDGED)
            List(rows.length()) { rows.getString(it) }
        }
        val reached = json.opt("capacity_reached") as? Boolean ?: error("invalid delivery capacity flag")
        return RecoveryDeliveryState(pending, acknowledged, reached).also {
            RecoveryDeliveryQueue.validateState(it)
        }
    }

    private fun reportToJson(report: RecoveryReport) = JSONObject()
        .put("id", report.id)
        .put("session_id", report.sessionId)
        .put("triggered_at_millis", report.triggeredAtMillis)
        .put("status", report.status.name)
        .put("recovered", report.recovered)
        .put("truncated", report.truncated)
        .put("event_count", report.eventCount)
        .put("events", JSONArray().also { rows -> report.events.forEach { rows.put(eventToJson(it)) } })

    private fun reportFromJson(json: JSONObject): RecoveryReport {
        json.requireKeys("id", "session_id", "triggered_at_millis", "status", "recovered", "truncated", "event_count", "events")
        return RecoveryReport(
            id = json.requiredString("id"),
            sessionId = json.requiredString("session_id"),
            triggeredAtMillis = json.requiredLong("triggered_at_millis"),
            status = IncidentStatus.valueOf(json.requiredString("status")),
            recovered = json.opt("recovered") as? Boolean ?: error("invalid recovery flag"),
            truncated = json.opt("truncated") as? Boolean ?: error("invalid truncation flag"),
            eventCount = json.requiredInt("event_count"),
            events = json.getJSONArray("events").let { rows ->
                require(rows.length() <= 24)
                List(rows.length()) { eventFromJson(rows.getJSONObject(it)) }
            },
        )
    }

    private fun eventToJson(event: DiagnosticEvent) = JSONObject()
        .put("timestamp_millis", event.timestampMillis)
        .put("monotonic_millis", event.monotonicMillis)
        .put("session_id", event.sessionId)
        .put("kind", event.kind.name)
        .put("target", event.target.name)
        .put("duration_millis", event.durationMillis ?: JSONObject.NULL)
        .put("transport", event.transport.name)
        .put("network", event.network.name)
        .put("path", event.path.name)
        .put("code", event.code ?: JSONObject.NULL)
        .put("dns_millis", event.dnsMillis ?: JSONObject.NULL)
        .put("connect_millis", event.connectMillis ?: JSONObject.NULL)
        .put("tls_millis", event.tlsMillis ?: JSONObject.NULL)

    private fun eventFromJson(json: JSONObject): DiagnosticEvent {
        json.requireKeys("timestamp_millis", "monotonic_millis", "session_id", "kind", "target", "duration_millis", "transport", "network", "path", "code", "dns_millis", "connect_millis", "tls_millis")
        return DiagnosticEvent(
            timestampMillis = json.requiredLong("timestamp_millis"),
            monotonicMillis = json.requiredLong("monotonic_millis"),
            sessionId = json.requiredString("session_id"),
            kind = DiagnosticEventKind.valueOf(json.requiredString("kind")),
            target = DiagnosticTarget.valueOf(json.requiredString("target")),
            durationMillis = json.optionalLong("duration_millis"),
            transport = DiagnosticTransport.valueOf(json.requiredString("transport")),
            network = DiagnosticNetwork.valueOf(json.requiredString("network")),
            path = DiagnosticPath.valueOf(json.requiredString("path")),
            code = json.optionalInt("code"),
            dnsMillis = json.optionalLong("dns_millis"),
            connectMillis = json.optionalLong("connect_millis"),
            tlsMillis = json.optionalLong("tls_millis"),
        )
    }

    private fun JSONObject.requireKeys(vararg expected: String) {
        require(keys().asSequence().toSet() == expected.toSet()) { "unknown delivery store fields" }
    }

    private fun JSONObject.requiredString(key: String): String = opt(key) as? String
        ?: error("invalid delivery string")

    private fun JSONObject.requiredLong(key: String): Long = (opt(key) as? Number)?.toLong()?.also {
        require((opt(key) as Number).toDouble() == it.toDouble())
    } ?: error("invalid delivery number")

    private fun JSONObject.requiredInt(key: String): Int = requiredLong(key).toInt().also {
        require(it.toLong() == requiredLong(key))
    }

    private fun JSONObject.optionalLong(key: String): Long? = when (val value = opt(key)) {
        JSONObject.NULL, null -> null
        is Number -> value.toLong().also { require(value.toDouble() == it.toDouble()) }
        else -> error("invalid delivery number")
    }

    private fun JSONObject.optionalInt(key: String): Int? = optionalLong(key)?.toInt()?.also {
        require(it.toLong() == optionalLong(key))
    }

    private companion object {
        const val STORE_FILE = "queue.json"
        const val STORE_SCHEMA = 1
        val locks = HashMap<String, Any>()
        fun lockFor(root: File): Any = synchronized(locks) {
            locks.getOrPut(root.absolutePath) { Any() }
        }
    }
}

internal class CorruptRecoveryDeliveryStoreException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal object AndroidRecoveryDelivery {
    private val queueLock = Any()
    private val uploadMutex = Mutex()
    private val queues = HashMap<String, RecoveryDeliveryQueue>()

    fun start(context: Context) {
        if (DataStore.automaticRecoveryReports) runCatching { ensurePeriodic(context) }
    }

    fun capture(context: Context, store: AndroidIncidentStore = AndroidIncidentReports.store(context)) {
        runCatching {
            val snapshot = store.readOnlySnapshot() ?: return
            synchronized(queueLock) {
                val queue = queue(context)
                val before = queue.snapshot()
                snapshot.archives.forEach { archive ->
                    compactRecoveryReport(archive)?.let(queue::enqueue)
                }
                if (queue.snapshot() == before || !DataStore.automaticRecoveryReports) return
            }
            ensurePeriodic(context)
            schedule(context)
        }
    }

    fun settingsChanged(context: Context) {
        val manager = RemoteWorkManager.getInstance(context.applicationContext)
        if (DataStore.automaticRecoveryReports) {
            ensurePeriodic(context)
            schedule(context)
        } else {
            manager.cancelUniqueWork(WORK_NAME)
            manager.cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }

    private fun queue(context: Context): RecoveryDeliveryQueue {
        val directory = File(context.applicationContext.noBackupFilesDir, "recovery_delivery")
        return queues.getOrPut(directory.absolutePath) {
            RecoveryDeliveryQueue(AndroidRecoveryDeliveryStore(directory))
        }
    }

    private fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<RecoveryUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        RemoteWorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME, ExistingWorkPolicy.KEEP, listOf(request),
        )
    }

    private fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<RecoveryUploadWorker>(PERIODIC_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(IS_PERIODIC_WORK to true))
            .build()
        RemoteWorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }

    internal suspend fun perform(context: Context): Boolean {
        uploadMutex.lock()
        try {
            if (!DataStore.automaticRecoveryReports) return false
            val queue = synchronized(queueLock) { queue(context) }
            var handled = 0
            while (handled < RecoveryDeliveryQueue.MAX_PENDING) {
                if (!DataStore.automaticRecoveryReports) return hasPending(queue)
                if (!hasUsableDefaultNetwork(context)) return hasPending(queue)
                val now = System.currentTimeMillis().coerceAtLeast(0)
                val due = synchronized(queueLock) {
                    queue.nextDue(now, enabled = true, networkReady = true)
                } ?: return hasPending(queue)
                if (!DataStore.automaticRecoveryReports) return hasPending(queue)
                val delivered = try {
                    post(reportPayload(due.report))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                val stillPending = synchronized(queueLock) {
                    val current = queue.snapshot().pending.firstOrNull { it.report.id == due.report.id }
                    val changedWhileSending = current != null && current.report != due.report
                    queue.complete(
                        due.report.id,
                        System.currentTimeMillis().coerceAtLeast(0),
                        delivered && !changedWhileSending,
                    )
                    queue.snapshot().pending.isNotEmpty()
                }
                if (!delivered || !stillPending) return stillPending
                handled += 1
            }
            return hasPending(queue)
        } finally {
            uploadMutex.unlock()
        }
    }

    private fun hasPending(queue: RecoveryDeliveryQueue): Boolean =
        synchronized(queueLock) { queue.snapshot().pending.isNotEmpty() }

    private fun hasUsableDefaultNetwork(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            return manager.activeNetworkInfo?.isConnected == true
        }
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    internal fun reportPayload(report: RecoveryReport): ByteArray {
        val events = JSONArray().also { rows -> report.events.forEach { event ->
            rows.put(JSONObject()
                .put("at_ms", event.timestampMillis)
                .put("elapsed_ms", event.monotonicMillis)
                .put("kind", event.kind.name.lowercase())
                .put("target", event.target.name.lowercase())
                .put("duration_ms", event.durationMillis ?: JSONObject.NULL)
                .put("transport", event.transport.name.lowercase())
                .put("network", event.network.name.lowercase())
                .put("path", event.path.name.lowercase())
                .put("code", event.code ?: JSONObject.NULL)
                .put("dns_ms", event.dnsMillis ?: JSONObject.NULL)
                .put("connect_ms", event.connectMillis ?: JSONObject.NULL)
                .put("tls_ms", event.tlsMillis ?: JSONObject.NULL))
        } }
        val diagnostics = JSONObject()
            .put("schema", "gates-recovery-v1")
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("app_version_code", BuildConfig.VERSION_CODE)
            .put("core_version", coreVersion())
            .put("incident_id", report.id)
            .put("session_id", report.sessionId)
            .put("triggered_at_ms", report.triggeredAtMillis)
            .put("status", report.status.name.lowercase())
            .put("recovered", report.recovered)
            .put("truncated", report.truncated)
            .put("event_count", report.eventCount)
            .put("events", events)
        val envelope = JSONObject()
            .put("platform", "android")
            .put("message", "Automatic connection diagnostics")
            .put("diagnostics", diagnostics)
        return envelope.toString().toByteArray(StandardCharsets.UTF_8).also {
            require(it.size < RecoveryDeliveryQueue.MAX_PAYLOAD_BYTES) { "recovery payload exceeds limit" }
        }
    }

    private suspend fun post(payload: ByteArray): Boolean = try {
        withTimeout(HTTP_TIMEOUT_MS.toLong()) {
            val started = SystemClock.elapsedRealtime()
            val connection = (URL(REPORT_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setFixedLengthStreamingMode(payload.size)
            }
            try {
                connection.useCancellable {
                    check(url.host == REPORT_HOST && url.protocol == "https")
                    check(SystemClock.elapsedRealtime() - started < HTTP_TIMEOUT_MS)
                    outputStream.use { it.write(payload) }
                    check(SystemClock.elapsedRealtime() - started < HTTP_TIMEOUT_MS)
                    readTimeout = remainingTimeout(started)
                    val status = responseCode
                    val body = if (status in 200..299) inputStream else errorStream
                    body?.use { input ->
                        val buffer = ByteArray(1024)
                        var total = 0
                        while (true) {
                            readTimeout = remainingTimeout(started)
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_RESPONSE_BYTES) break
                            if (SystemClock.elapsedRealtime() - started > HTTP_TIMEOUT_MS) break
                        }
                    }
                    status in 200..299 && SystemClock.elapsedRealtime() - started <= HTTP_TIMEOUT_MS
                }
            } finally {
                connection.disconnect()
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        if (!currentCoroutineContext().isActive) throw timeout
        false
    }

    private fun remainingTimeout(started: Long): Int {
        val remaining = HTTP_TIMEOUT_MS - (SystemClock.elapsedRealtime() - started)
        check(remaining > 0) { "recovery request deadline exceeded" }
        return remaining.toInt()
    }

    private fun coreVersion(): String = runCatching { Libcore.versionBox() }
        .getOrNull()
        ?.takeIf { it.length <= 64 && CORE_VERSION.matches(it) }
        ?: "unknown"

    internal const val PERIODIC_INTERVAL_MILLIS = 15 * 60_000L
    private const val RETRY_BACKOFF_MILLIS = 60_000L
    private const val PERIODIC_WORK_NAME = "automatic-recovery-reports-safety"
    private const val IS_PERIODIC_WORK = "periodic"
    private val CORE_VERSION = Regex("[A-Za-z0-9._+\\-]+")
    private const val WORK_NAME = "automatic-recovery-reports"
    private const val REPORT_URL = "https://app.yokhan.xyz/gates/api/app/bug-report"
    private const val REPORT_HOST = "app.yokhan.xyz"
    private const val HTTP_TIMEOUT_MS = 10_000
    private const val MAX_RESPONSE_BYTES = 4 * 1024
}

internal class RecoveryUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val periodic = inputData.getBoolean("periodic", false)
        return try {
            val pending = AndroidRecoveryDelivery.perform(applicationContext)
            if (pending && !periodic) Result.retry() else Result.success()
        } catch (_: CorruptRecoveryDeliveryStoreException) {
            Result.failure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (periodic) Result.success() else Result.retry()
        }
    }
}
