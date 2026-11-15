package com.yokhan.gates.app.auth

import android.content.Context
import androidx.room.withTransaction
import com.yokhan.gates.app.backup.ProfileMutations
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

internal fun candidateBootstrapApiBases(configuredBase: String): List<String> =
    listOf(
        configuredBase.trim().trimEnd('/'),
        GATES_BOOTSTRAP_CLOAK_BASE,
        GATES_BOOTSTRAP_PRIMARY_BASE,
    )
        .filter { it.startsWith("https://", ignoreCase = true) }
        .distinct()

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
    ): Result<BootstrapClaimResult> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val normalizedCode = code.trim()
        if (normalizedCode.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Введите пароль Древа."))
        }
        val deviceId = bootstrapDeviceId(appContext)
        val requestJson = JsonObject().apply {
            addProperty("code", normalizedCode)
            addProperty("device_id", deviceId)
            addProperty("platform", "android")
        }.toString()

        val responsePair = try {
            postWithFallback(
                path = "/api/bootstrap/claim",
                bodyJson = requestJson,
                deviceId = deviceId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return@withContext Result.failure(
                IllegalStateException("Не удалось связаться с сервером разового доступа.", error),
            )
        }

        val (statusCode, responseBody) = responsePair
        if (statusCode !in 200..299) {
            return@withContext Result.failure(IllegalStateException(bootstrapClaimErrorMessage(statusCode)))
        }

        val parsed = runCatching { parseBootstrapClaimPayload(responseBody) }
            .getOrElse { return@withContext Result.failure(it) }

        val profileBody = try {
            fetchBootstrapSubscription(parsed.subscriptionUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return@withContext Result.failure(
                IllegalStateException("Не удалось получить временный профиль: ${error.message ?: error.javaClass.simpleName}", error),
            )
        }

        val importedCount = importBootstrapProfiles(appContext, profileBody, parsed.expiresAtMillis)
        Result.success(
            BootstrapClaimResult(
                profileCount = importedCount,
                expiresAtMillis = parsed.expiresAtMillis,
            ),
        )
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
        val request = Request.Builder()
            .url(subscriptionUrl)
            .header("Accept", "application/json, text/plain")
            .header("X-Yokhan-Client", "GATES-Android")
            .header("X-Yokhan-App-Version", BuildConfig.VERSION_NAME)
            .get()
            .build()
        YokhanKeycloakAuth.sharedHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            return body
        }
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
