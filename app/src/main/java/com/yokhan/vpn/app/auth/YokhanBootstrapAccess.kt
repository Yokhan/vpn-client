package com.yokhan.gates.app.auth

import android.content.Context
import androidx.room.withTransaction
import com.yokhan.gates.app.backup.ProfileMutations
import com.yokhan.gates.shared.domain.access.WiseTreeAccess
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.proxy.config.ConfigBean
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import kotlin.math.ceil

internal data class BootstrapClaimPayload(
    val subscriptionUrl: String,
    val expiresAtMillis: Long,
)

data class BootstrapClaimResult(
    val profileCount: Int,
    val expiresAtMillis: Long,
)

internal const val GATES_BOOTSTRAP_CLOAK_BASE = "https://cloak.yokhan.xyz/gates"
internal const val GATES_BOOTSTRAP_PRIMARY_BASE = "https://app.yokhan.xyz/gates"
private const val BOOTSTRAP_PREFS = "gates_bootstrap_access"
private const val KEY_EXPIRES_AT_MS = "expires_at_ms"
private const val BOOTSTRAP_DEFAULT_TTL_MS = 15L * 60L * 1000L
private const val BOOTSTRAP_PROFILE_NAME = "Edge-xhttp (Древо)"

internal fun candidateBootstrapApiBases(configuredBase: String): List<String> =
    listOf(
        configuredBase.trim().trimEnd('/'),
        GATES_BOOTSTRAP_CLOAK_BASE,
        GATES_BOOTSTRAP_PRIMARY_BASE,
    )
        .filter { it.startsWith("https://", ignoreCase = true) }
        .distinct()

internal fun candidateBootstrapSubscriptionUrls(
    subscriptionUrl: String,
    configuredBase: String = BuildConfig.YOKHAN_APP_API_BASE_URL,
): List<String> {
    val trimmed = subscriptionUrl.trim()
    val pathIdx = trimmed.indexOf("/api/bootstrap/sub/")
    if (pathIdx < 0) return listOf(trimmed).filter { it.isNotBlank() }
    val suffix = trimmed.substring(pathIdx)
    return (
        listOf(trimmed) +
            candidateBootstrapApiBases(configuredBase).map { base ->
                "${base.trimEnd('/')}$suffix"
            }
        )
        .filter { it.startsWith("https://", ignoreCase = true) }
        .distinct()
}

internal fun bootstrapClaimErrorMessage(statusCode: Int): String = when (statusCode) {
    403 -> "Древо молчит: пароль не подошёл. Попробуйте ещё раз."
    429 -> "Разовый доступ для этого устройства уже использован. Следующий — через 24 часа."
    503 -> "Разовый доступ временно недоступен."
    else -> "Разовый доступ не выдан: HTTP $statusCode."
}

internal fun parseBootstrapClaimPayload(
    rawJson: String,
    nowMillis: Long = System.currentTimeMillis(),
): BootstrapClaimPayload {
    val obj = runCatching { JsonParser.parseString(rawJson).asJsonObject }
        .getOrElse { throw IllegalArgumentException("Некорректный ответ сервера разового доступа.", it) }
    val subscriptionUrl = obj.get("subscription_url")
        ?.takeIf { it.isJsonPrimitive }
        ?.asString
        ?.trim()
        .orEmpty()
    require(subscriptionUrl.startsWith("https://", ignoreCase = true)) {
        "Сервер не вернул корректную ссылку разового доступа."
    }
    val expiresEpochSec = obj.get("expires_at")
        ?.takeIf { it.isJsonPrimitive }
        ?.let { runCatching { it.asLong }.getOrNull() }
        ?: 0L
    val expiresAtMillis = (expiresEpochSec * 1000L).takeIf { it > nowMillis }
        ?: (nowMillis + BOOTSTRAP_DEFAULT_TTL_MS)
    return BootstrapClaimPayload(
        subscriptionUrl = subscriptionUrl,
        expiresAtMillis = expiresAtMillis,
    )
}

internal fun remainingBootstrapMinutes(
    expiresAtMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
): Int? {
    val remainingMs = expiresAtMillis - nowMillis
    if (remainingMs <= 0L) return null
    return ceil(remainingMs / 60_000.0).toInt().coerceAtLeast(1)
}

/**
 * Wise Tree pre-login bootstrap access for Android, aligned with Windows `/api/bootstrap/claim`
 * and `/api/bootstrap/revoke` contract.
 */
object YokhanBootstrapAccess {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val deviceIdRegex = Regex("^[A-Za-z0-9._:-]{8,128}$")

    fun activeRemainingMinutes(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int? {
        val expiresAt = context.applicationContext
            .getSharedPreferences(BOOTSTRAP_PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_EXPIRES_AT_MS, 0L)
        return remainingBootstrapMinutes(expiresAt, nowMillis)
    }

    fun clearLocalState(context: Context) {
        context.applicationContext
            .getSharedPreferences(BOOTSTRAP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_EXPIRES_AT_MS)
            .apply()
    }

    suspend fun claim(
        context: Context,
        code: String,
        codeVerifier: (String) -> Boolean = WiseTreeAccess::verifyCode,
        ensureEmbeddedTunnelConnected: (suspend () -> Boolean)? = null,
        stopEmbeddedTunnel: (() -> Unit)? = null,
    ): Result<BootstrapClaimResult> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val deviceId = bootstrapDeviceId(appContext)
        claimWithDeps(
            code = code,
            codeVerifier = codeVerifier,
            deviceId = deviceId,
            ensureEmbeddedTunnelConnected = ensureEmbeddedTunnelConnected,
            stopEmbeddedTunnel = stopEmbeddedTunnel,
            claimRequester = { path, bodyJson, id ->
                postWithFallback(path = path, bodyJson = bodyJson, deviceId = id)
            },
            subFetcher = ::fetchBootstrapSubscription,
            profileUpdater = { payload, expiresAt ->
                importBootstrapProfiles(appContext, payload, expiresAt)
            },
            profileCleaner = {
                clearLocalState(appContext)
                clearBootstrapProfiles(appContext)
            },
        )
    }

    internal suspend fun claimWithDeps(
        code: String,
        codeVerifier: (String) -> Boolean = WiseTreeAccess::verifyCode,
        deviceId: String,
        ensureEmbeddedTunnelConnected: (suspend () -> Boolean)? = null,
        stopEmbeddedTunnel: (() -> Unit)? = null,
        claimRequester: (path: String, bodyJson: String, deviceId: String) -> Pair<Int, String>,
        subFetcher: (String) -> String,
        profileUpdater: suspend (payload: String, expiresAtMillis: Long) -> Int,
        profileCleaner: suspend () -> Unit,
    ): Result<BootstrapClaimResult> = withContext(Dispatchers.IO) {
        val normalizedCode = WiseTreeAccess.normalizeCode(code)
        if (normalizedCode.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Введите пароль Древа."))
        }
        if (!codeVerifier(normalizedCode)) {
            return@withContext Result.failure(IllegalStateException(bootstrapClaimErrorMessage(403)))
        }
        val requestJson = JsonObject().apply {
            addProperty("code", normalizedCode)
            addProperty("device_id", deviceId)
            addProperty("platform", "android")
        }.toString()

        val directResponse = try {
            claimRequester("/api/bootstrap/claim", requestJson, deviceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (directResponse != null && directResponse.first < 500) {
            val (statusCode, responseBody) = directResponse
            if (statusCode !in 200..299) {
                return@withContext Result.failure(IllegalStateException(bootstrapClaimErrorMessage(statusCode)))
            }
            return@withContext finishClaimWithPayload(responseBody, subFetcher, profileUpdater)
        }

        if (ensureEmbeddedTunnelConnected == null) {
            return@withContext Result.failure(
                IllegalStateException("Не удалось связаться с сервером разового доступа."),
            )
        }

        val seedExpiresAt = System.currentTimeMillis() + BOOTSTRAP_DEFAULT_TTL_MS
        runCatching {
            profileUpdater(
                WiseTreeAccess.buildEmbeddedBootstrapProfileJson(autoDetectInterface = false),
                seedExpiresAt,
            )
        }.getOrElse { error ->
            return@withContext Result.failure(
                IllegalStateException("Не удалось подготовить временный профиль: ${error.message ?: error.javaClass.simpleName}", error),
            )
        }
        val tunnelReady = try {
            ensureEmbeddedTunnelConnected()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!tunnelReady) {
            runCatching { stopEmbeddedTunnel?.invoke() }
            runCatching { profileCleaner() }
            return@withContext Result.failure(
                IllegalStateException("Не удалось запустить временный туннель. Проверьте разрешение VPN."),
            )
        }

        var tunnelResponse: Pair<Int, String>? = null
        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            val attemptPair = try {
                claimRequester("/api/bootstrap/claim", requestJson, deviceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (attemptPair != null) {
                tunnelResponse = attemptPair
                if (attemptPair.first < 500) break
            }
            if (attempt < maxAttempts) {
                delay(200L)
            }
        }

        if (tunnelResponse == null || tunnelResponse.first >= 500) {
            runCatching { stopEmbeddedTunnel?.invoke() }
            runCatching { profileCleaner() }
            return@withContext Result.failure(
                IllegalStateException("Не удалось связаться с сервером разового доступа."),
            )
        }
        if (tunnelResponse.first !in 200..299) {
            runCatching { stopEmbeddedTunnel?.invoke() }
            runCatching { profileCleaner() }
            return@withContext Result.failure(
                IllegalStateException(bootstrapClaimErrorMessage(tunnelResponse.first)),
            )
        }
        return@withContext finishClaimWithPayload(tunnelResponse.second, subFetcher, profileUpdater).also { res ->
            if (res.isFailure) {
                runCatching { stopEmbeddedTunnel?.invoke() }
                runCatching { profileCleaner() }
            }
        }
    }

    private suspend fun finishClaimWithPayload(
        responseBody: String,
        fetchSub: (String) -> String,
        updateProfiles: suspend (String, Long) -> Int,
    ): Result<BootstrapClaimResult> {
        val parsed = runCatching { parseBootstrapClaimPayload(responseBody) }
            .getOrElse { return Result.failure(it) }
        val profileBody = try {
            fetchSub(parsed.subscriptionUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Result.failure(
                IllegalStateException("Не удалось получить временный профиль: ${error.message ?: error.javaClass.simpleName}", error),
            )
        }
        val importedCount = updateProfiles(profileBody, parsed.expiresAtMillis)
        return Result.success(
            BootstrapClaimResult(
                profileCount = importedCount,
                expiresAtMillis = parsed.expiresAtMillis,
            ),
        )
    }

    private suspend fun clearBootstrapProfiles(appContext: Context) {
        ProfileMutations.withLock {
            DataStore.flushConfiguration()
            DataStore.refreshConfiguration()
            val group = YokhanKeycloakAuth.ensureAppGroup(appContext)
            SagerDatabase.instance.withTransaction {
                val existing = SagerDatabase.proxyDao.getByGroup(group.id)
                val removedIds = existing
                    .filter { it.displayName() == BOOTSTRAP_PROFILE_NAME }
                    .map { it.id }
                    .toSet()
                removedIds.forEach { SagerDatabase.proxyDao.deleteById(it) }
                val fallbackId = existing.firstOrNull { it.id !in removedIds }?.id ?: 0L
                if (DataStore.selectedProxy in removedIds) DataStore.selectedProxy = fallbackId
                if (DataStore.currentProfile in removedIds) DataStore.currentProfile = fallbackId
            }
            GroupManager.postReload(group.id)
        }
    }

    suspend fun revokeBestEffort(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(BOOTSTRAP_PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_EXPIRES_AT_MS)) return@withContext
        clearLocalState(appContext)
        val deviceId = existingBootstrapDeviceId(appContext) ?: return@withContext
        val payload = JsonObject().apply { addProperty("device_id", deviceId) }.toString()
        runCatching {
            postWithFallback(
                path = "/api/bootstrap/revoke",
                bodyJson = payload,
                deviceId = deviceId,
            )
        }
    }

    private fun postWithFallback(
        path: String,
        bodyJson: String,
        deviceId: String,
    ): Pair<Int, String> {
        val bases = candidateBootstrapApiBases(BuildConfig.YOKHAN_APP_API_BASE_URL)
        var lastError: Exception? = null
        var lastResult: Pair<Int, String>? = null
        val client = YokhanKeycloakAuth.sharedHttpClient
        for (base in bases) {
            val url = base.trimEnd('/') + path
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-Yokhan-Device", deviceId)
                .header("X-Yokhan-Client", "GATES-Android")
                .header("X-Yokhan-App-Version", BuildConfig.VERSION_NAME)
                .post(bodyJson.toRequestBody(jsonMediaType))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    lastResult = response.code to body
                    if (response.code < 500) {
                        return response.code to body
                    }
                }
            } catch (error: Exception) {
                lastError = error
            }
        }
        return lastResult ?: throw (lastError ?: IllegalStateException("No bootstrap endpoint configured"))
    }

    private fun fetchBootstrapSubscription(subscriptionUrl: String): String {
        val candidateUrls = candidateBootstrapSubscriptionUrls(subscriptionUrl)
        var lastError: Exception? = null
        var lastStatusCode = 0
        for (candidateUrl in candidateUrls) {
            val request = Request.Builder()
                .url(candidateUrl)
                .header("Accept", "application/json, text/plain")
                .header("X-Yokhan-Client", "GATES-Android")
                .header("X-Yokhan-App-Version", BuildConfig.VERSION_NAME)
                .get()
                .build()
            try {
                YokhanKeycloakAuth.sharedHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        return body
                    }
                    lastStatusCode = response.code
                    if (response.code < 500) {
                        throw IllegalStateException("HTTP ${response.code}")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                if (lastStatusCode in 400..499) {
                    throw error
                }
            }
        }
        if (lastStatusCode >= 500) {
            throw IllegalStateException("HTTP $lastStatusCode")
        }
        throw (lastError ?: IllegalStateException("Не удалось получить временный конфиг."))
    }

    private suspend fun importBootstrapProfiles(
        appContext: Context,
        payload: String,
        expiresAtMillis: Long,
    ): Int {
        val beans: List<AbstractBean> = if (payload.contains("\"xhttp\"")) {
            listOf(
                ConfigBean().apply {
                    name = "Edge-xhttp (Древо)"
                    type = 0
                    config = payload
                },
            )
        } else {
            RawUpdater.parseRaw(payload)
                ?: error("Сервер вернул пустой временный конфиг.")
        }
        require(beans.isNotEmpty()) { "Сервер вернул пустой временный конфиг." }
        beans.forEach { it.applyDefaultValues() }

        ProfileMutations.withLock {
            DataStore.flushConfiguration()
            DataStore.refreshConfiguration()
            val group = YokhanKeycloakAuth.ensureAppGroup(appContext)
            val importedIds = SagerDatabase.instance.withTransaction {
                val existing = SagerDatabase.proxyDao.getByGroup(group.id)
                val plan = planProfileRefresh(
                    existing.map { it.id to it.displayName() },
                    beans.map { it.displayName() },
                    setOf(DataStore.selectedProxy, DataStore.currentProfile),
                )
                val byId = existing.associateBy { it.id }
                val imported = beans.mapIndexed { index, bean ->
                    val entity = plan.replacementIds[index]?.let(byId::get)
                    if (entity == null) {
                        SagerDatabase.proxyDao.addProxy(
                            ProxyEntity(groupId = group.id, userOrder = index + 1L).apply { putBean(bean) },
                        )
                    } else {
                        val previous = entity.requireBean()
                        bean.customOutboundJson = previous.customOutboundJson
                        bean.customConfigJson = previous.customConfigJson
                        entity.putBean(bean)
                        entity.userOrder = index + 1L
                        SagerDatabase.proxyDao.updateProxy(entity)
                        entity.id
                    }
                }
                plan.removedIds.forEach { SagerDatabase.proxyDao.deleteById(it) }
                imported
            }
            importedIds.firstOrNull()?.let { first ->
                DataStore.selectedProxy = first
                DataStore.currentProfile = first
                DataStore.selectedGroup = group.id
            }
            appContext.getSharedPreferences(BOOTSTRAP_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_EXPIRES_AT_MS, expiresAtMillis)
                .apply()
            DataStore.flushConfiguration()
            GroupManager.postReload(group.id)
        }
        return beans.size
    }

    private fun existingBootstrapDeviceId(context: Context): String? {
        val file = File(context.filesDir, "bootstrap-device-id")
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8).trim() }
            .getOrNull()
            ?.takeIf { deviceIdRegex.matches(it) }
    }

    private fun bootstrapDeviceId(context: Context): String {
        existingBootstrapDeviceId(context)?.let { return it }
        val file = File(context.filesDir, "bootstrap-device-id")
        val id = "android-" + UUID.randomUUID().toString()
        runCatching { file.writeText(id, Charsets.UTF_8) }
        return id
    }
}
