package com.yokhan.gates.app.auth

import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.AtomicFile
import android.util.Log
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.yokhan.gates.app.backup.ProfileMutations
import com.yokhan.gates.app.backup.ProfileMutationGate
import com.yokhan.gates.app.manifest.YokhanRouteManifestManager
import com.yokhan.gates.app.manifest.YokhanRouteManifestStore
import com.yokhan.gates.app.manifest.YokhanRouteManifestVerifier
import com.yokhan.gates.app.ui.MainActivity
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.app
import moe.matsuri.nb4a.proxy.config.ConfigBean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.connectivity.ConnectionBuilder
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object YokhanKeycloakAuth {
    private val sessionGate by lazy { ProfileMutationGate(File(app.filesDir, "auth-session.lock")) }
    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var deliveredResult: Intent? = null
    private val mutableSessionAvailable = MutableStateFlow(false)
    val sessionAvailable = mutableSessionAvailable.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private const val LOG_TAG = "GatesAuth"
    private const val ACTION_AUTH_COMPLETE = "com.yokhan.gates.app.auth.COMPLETE"
    private const val ACTION_AUTH_CANCELLED = "com.yokhan.gates.app.auth.CANCELLED"
    private const val ACTION_AUTH_RETURN = "com.yokhan.gates.app.auth.RETURN"
    private const val AUTH_COMPLETE_REQUEST_CODE = 24061
    private const val AUTH_CANCEL_REQUEST_CODE = 24062
    private const val AUTH_HTTP_TIMEOUT_MS = 5_000

    private val authConnectionBuilder = ConnectionBuilder { uri ->
        require(uri.scheme == "https") { "Only HTTPS auth endpoints are supported" }
        (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = AUTH_HTTP_TIMEOUT_MS
            readTimeout = AUTH_HTTP_TIMEOUT_MS
            instanceFollowRedirects = false
        }
    }

    internal fun createAuthorizationService(context: Context): AuthorizationService {
        val config = AppAuthConfiguration.Builder()
            .setConnectionBuilder(authConnectionBuilder)
            .build()
        return AuthorizationService(context, config)
    }

    internal val sharedHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(6, 2, TimeUnit.MINUTES))
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun authHttpClient(): OkHttpClient = sharedHttpClient

    private data class XhttpProfileRef(
        val name: String,
        val label: String,
    )

    suspend fun restoreSession(context: Context) = withContext(Dispatchers.IO) {
        sessionGate.withLock {
            mutableSessionAvailable.value = YokhanAuthStore.load(context)?.isAuthorized == true
        }
    }

    fun signOut(activity: MainActivity) {
        if (mutableBusy.value) return
        val appContext = activity.applicationContext
        mutableSessionAvailable.value = false
        authScope.launch {
            sessionGate.withLock { YokhanAuthStore.clear(appContext) }
            withContext(Dispatchers.Main) {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.showDashboardMessage(R.string.yokhan_auth_signed_out)
                }
            }
        }
    }

    fun syncOrLogin(activity: MainActivity, authService: AuthorizationService) {
        if (mutableBusy.value) return
        if (!mutableSessionAvailable.value) {
            startLogin(activity, authService)
            return
        }
        mutableBusy.value = true
        activity.lifecycleScope.launch {
            try {
                val count = importAuthenticatedConfig(activity, authService)
                activity.onYokhanAuthImportComplete(count)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(LOG_TAG, "config_sync_failed type=${error.javaClass.simpleName}")
                if (error is GatesAccountAccessException && !error.message.isNullOrBlank()) {
                    activity.showDashboardMessage(error.message!!)
                } else {
                    activity.showDashboardMessage(R.string.yokhan_auth_sync_failed)
                }
                if (AuthSyncRecoveryPolicy.shouldOpenLogin(mutableSessionAvailable.value, error)) {
                    mutableSessionAvailable.value = false
                }
            } finally {
                mutableBusy.value = false
            }
        }
    }

    fun isConfigured(): Boolean {
        return BuildConfig.YOKHAN_KEYCLOAK_ISSUER.isNotBlank() &&
            BuildConfig.YOKHAN_KEYCLOAK_CLIENT_ID.isNotBlank() &&
            BuildConfig.YOKHAN_KEYCLOAK_REDIRECT_URI.isNotBlank() &&
            BuildConfig.YOKHAN_APP_API_BASE_URL.isNotBlank()
    }

    suspend fun freshAccessToken(context: Context): String? {
        if (!isConfigured()) return null
        val appContext = context.applicationContext
        val authService = createAuthorizationService(appContext)
        return try {
            storedAccessToken(appContext, authService)
        } finally {
            authService.dispose()
        }
    }

    private suspend fun storedAccessToken(context: Context, service: AuthorizationService): String? =
        withStoredAuthSession(sessionGate, { YokhanAuthStore.load(context) },
            { state -> state?.let { YokhanAuthStore.save(context, it) } }) { state ->
            state?.let { freshAccessToken(it, service) }
        }

    // PKCE state must be durable before the browser can return to a recreated process.
    @SuppressLint("ApplySharedPref")
    fun startLogin(
        activity: MainActivity,
        authService: AuthorizationService,
    ) {
        if (mutableBusy.value) {
            debugLog { "login_ignored_busy" }
            return
        }
        if (!isConfigured()) {
            activity.showDashboardMessage(R.string.yokhan_auth_not_configured)
            return
        }

        mutableBusy.value = true
        Log.i(LOG_TAG, "login_discovery_started")

        AuthorizationServiceConfiguration.fetchFromIssuer(
            Uri.parse(BuildConfig.YOKHAN_KEYCLOAK_ISSUER),
            { serviceConfiguration, exception ->
                if (exception != null || serviceConfiguration == null) {
                    mutableBusy.value = false
                    Log.w(LOG_TAG, "discovery_failed type=${exception?.javaClass?.simpleName} msg=${exception?.message}")
                    Logs.w(exception ?: IllegalStateException("Missing auth service config"))
                    val messageRes = if (isAuthNetworkTimeoutOrUnreachable(exception)) {
                        R.string.yokhan_auth_network_unreachable
                    } else {
                        R.string.yokhan_auth_open_failed
                    }
                    activity.runOnUiThread {
                        activity.showDashboardMessage(messageRes)
                    }
                    return@fetchFromIssuer
                }

                val request = AuthorizationRequest.Builder(
                    serviceConfiguration,
                    BuildConfig.YOKHAN_KEYCLOAK_CLIENT_ID,
                    ResponseTypeValues.CODE,
                    Uri.parse(BuildConfig.YOKHAN_KEYCLOAK_REDIRECT_URI),
                )
                    .setScopes("openid", "profile", "email")
                    .build()

                activity.getSharedPreferences("gates_auth_request", Context.MODE_PRIVATE).edit()
                    .putString("request", request.jsonSerializeString()).commit()

                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        mutableBusy.value = false
                        return@runOnUiThread
                    }
                    runCatching {
                        authService.performAuthorizationRequest(
                            request,
                            createAuthPendingIntent(activity, ACTION_AUTH_COMPLETE, AUTH_COMPLETE_REQUEST_CODE),
                            createAuthPendingIntent(activity, ACTION_AUTH_CANCELLED, AUTH_CANCEL_REQUEST_CODE),
                        )
                    }.onFailure { error ->
                        mutableBusy.value = false
                        Log.w(LOG_TAG, "browser_open_failed type=${error.javaClass.simpleName}")
                        activity.showDashboardMessage(R.string.yokhan_auth_open_failed)
                    }
                }
            },
            authConnectionBuilder,
        )
    }

    fun handleAuthorizationIntent(
        activity: MainActivity,
        authService: AuthorizationService,
        intent: Intent?,
    ): Boolean {
        if (intent?.action != ACTION_AUTH_RETURN) return false
        val result = deliveredResult ?: return true
        deliveredResult = null
        return when (result.action) {
            ACTION_AUTH_COMPLETE -> {
                debugLog { "auth_complete_intent_received" }
                handleAuthorizationResponse(activity, authService, result)
                true
            }
            ACTION_AUTH_CANCELLED -> {
                mutableBusy.value = false
                activity.getSharedPreferences("gates_auth_request", Context.MODE_PRIVATE).edit().clear().apply()
                debugLog { "auth_cancel_intent_received" }
                activity.showDashboardMessage(R.string.yokhan_auth_cancelled)
                true
            }
            else -> false
        }
    }

    internal fun deliverAuthorizationResult(context: Context, result: Intent) {
        deliveredResult = Intent(result)
        context.startActivity(Intent(context, MainActivity::class.java)
            .setAction(ACTION_AUTH_RETURN)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    // Consume the persisted one-time request before exchanging its authorization code.
    @SuppressLint("ApplySharedPref")
    private fun handleAuthorizationResponse(
        activity: MainActivity,
        authService: AuthorizationService,
        data: Intent?,
    ) {
        if (data == null) {
            mutableBusy.value = false
            Log.w(LOG_TAG, "auth_response_missing_intent")
            activity.showDashboardMessage(R.string.yokhan_auth_cancelled)
            return
        }

        val pending = activity.getSharedPreferences("gates_auth_request", Context.MODE_PRIVATE)
        val expected = pending.getString("request", null)
        pending.edit().clear().commit()
        val response = runCatching { AuthorizationResponse.fromIntent(data) }.getOrNull()
        val exception = runCatching { AuthorizationException.fromIntent(data) }.getOrNull()
        val expectedRequest = expected?.let { runCatching { AuthorizationRequest.jsonDeserialize(it) }.getOrNull() }
        if (response == null || expectedRequest?.state.isNullOrBlank() ||
            expectedRequest?.codeVerifier.isNullOrBlank() || response.authorizationCode.isNullOrBlank() ||
            response.request.state != expectedRequest.state || response.state != expectedRequest.state ||
            response.request.clientId != BuildConfig.YOKHAN_KEYCLOAK_CLIENT_ID ||
            response.request.redirectUri.toString() != BuildConfig.YOKHAN_KEYCLOAK_REDIRECT_URI ||
            response.request.codeVerifier != expectedRequest.codeVerifier ||
            response.request.configuration.tokenEndpoint != expectedRequest.configuration.tokenEndpoint ||
            response.request.configuration.authorizationEndpoint != expectedRequest.configuration.authorizationEndpoint
        ) {
            mutableBusy.value = false
            Log.w(LOG_TAG, "auth_response_missing")
            Logs.w(exception ?: IllegalStateException("Missing auth response"))
            activity.showDashboardMessage(R.string.yokhan_auth_failed)
            return
        }
        debugLog { "auth_response_received" }

        val authState = AuthState(response, exception)
        authService.performTokenRequest(response.createTokenExchangeRequest()) { token, tokenError ->
            if (tokenError != null || token == null) {
                mutableBusy.value = false
                Log.w(LOG_TAG, "token_exchange_failed code=${tokenError?.code}")
                Logs.w(tokenError ?: IllegalStateException("Missing token response"))
                activity.runOnUiThread {
                    activity.showDashboardMessage(R.string.yokhan_auth_failed)
                }
                return@performTokenRequest
            }

            debugLog { "token_exchange_ok" }
            authState.update(token, tokenError)
            val appContext = activity.applicationContext
            authScope.launch {
                val syncService = createAuthorizationService(appContext)
                try {
                    sessionGate.withLock { YokhanAuthStore.save(appContext, authState) }
                    mutableSessionAvailable.value = true
                    try {
                        debugLog { "config_import_started" }
                        val count = importAuthenticatedConfig(appContext, syncService)
                        debugLog { "config_import_ok count=$count" }
                        withContext(Dispatchers.Main) {
                            if (!activity.isFinishing && !activity.isDestroyed) activity.onYokhanAuthImportComplete(count)
                        }
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "config_import_failed type=${e.javaClass.simpleName}")
                        withContext(Dispatchers.Main) {
                            if (!activity.isFinishing && !activity.isDestroyed) activity.showDashboardMessage(
                                activity.getString(R.string.yokhan_auth_import_failed, e.message ?: ""),
                            )
                        }
                    }
                } finally {
                    syncService.dispose()
                    mutableBusy.value = false
                }
            }
        }
    }

    private fun createAuthPendingIntent(
        activity: MainActivity,
        action: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(activity, GatesAuthCallbackActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(activity, requestCode, intent, pendingIntentFlags())
    }

    private fun pendingIntentFlags(): Int {
        val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag
    }

    suspend fun importAuthenticatedConfig(
        context: Context,
        authService: AuthorizationService,
    ): Int = withContext(Dispatchers.IO) {
        val accessToken = storedAccessToken(context, authService)
            ?: throw MissingStoredAuthSessionException()
        debugLog { "fresh_token_ok" }

        val xhttpProfiles = if (BuildConfig.YOKHAN_XHTTP_SIDECAR_ENABLED) {
            fetchAppProfiles(accessToken).mapNotNull { profile ->
                fetchXhttpProfile(accessToken, profile.name)
            }
        } else {
            emptyList()
        }
        val proxies = if (xhttpProfiles.isNotEmpty()) {
            xhttpProfiles.toMutableList()
        } else if (BuildConfig.YOKHAN_XHTTP_SIDECAR_ENABLED) {
            // XHTTP-capable builds fail closed: never fall back to a legacy
            // VLESS/Reality or sing-box manifest route.
            val single = fetchXhttpProfile(accessToken)
                ?: error(context.getString(R.string.no_proxies_found))
            mutableListOf(single)
        } else {
            val payload = runCatching { fetchAuthenticatedManifestPayload(context, accessToken) }
                .onFailure { error -> Log.w(LOG_TAG, "manifest_fallback_to_links", error) }
                .getOrNull()
                ?: fetchAppLinks(accessToken)
            debugLog { "config_payload_loaded length=${payload.length}" }
            (RawUpdater.parseRaw(payload)
                ?: error(context.getString(R.string.no_proxies_found))).toMutableList()
        }
        debugLog { "config_payload_parsed count=${proxies.size}" }

        require(proxies.isNotEmpty()) { context.getString(R.string.no_proxies_found) }
        proxies.forEach { it.applyDefaultValues() }
        ProfileMutations.withLock {
            DataStore.flushConfiguration()
            DataStore.refreshConfiguration()
            val group = ensureAppGroup(context)
            val importedIds = SagerDatabase.instance.withTransaction {
                val existing = SagerDatabase.proxyDao.getByGroup(group.id)
                val plan = planProfileRefresh(
                    existing.map { it.id to it.displayName() },
                    proxies.map { it.displayName() },
                    setOf(DataStore.selectedProxy, DataStore.currentProfile),
                )
                val byId = existing.associateBy { it.id }
                val imported = proxies.mapIndexed { index, bean ->
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
            val xhttpId = if (BuildConfig.YOKHAN_XHTTP_SIDECAR_ENABLED) {
                importedIds.firstOrNull()
            } else {
                null
            }
            if (xhttpId != null) {
                // XHTTP is the only transport that works from the blocked entry, so a fresh
                // login selects the primary XHTTP device instead of a dead VLESS route.
                DataStore.selectedProxy = xhttpId
                DataStore.currentProfile = xhttpId
                DataStore.selectedGroup = group.id
            } else {
                val currentExists = SagerDatabase.proxyDao.getById(DataStore.currentProfile) != null
                initialImportedProfile(DataStore.selectedProxy, if (currentExists) DataStore.currentProfile else 0L,
                    DataStore.serviceState.canStop, importedIds)?.let { first ->
                    DataStore.selectedProxy = first
                    DataStore.currentProfile = first
                    DataStore.selectedGroup = group.id
                }
            }
            DataStore.flushConfiguration()
            GroupManager.postReload(group.id)
        }
        finalizeAuthenticatedProfileImport(
            isTunnelActive = { DataStore.serviceState.canStop },
            reloadTunnel = {
                withContext(Dispatchers.Main) {
                    if (DataStore.serviceState.canStop) {
                        SagerNet.reloadServiceAsync()
                    }
                }
            },
            waitForTunnelReconnect = { awaitTunnelTransitionAfterReload() },
            revokeBootstrap = { YokhanBootstrapAccess.revokeBestEffort(context.applicationContext) },
        )
        proxies.size
    }

    internal suspend fun finalizeAuthenticatedProfileImport(
        isTunnelActive: () -> Boolean,
        reloadTunnel: suspend () -> Unit,
        waitForTunnelReconnect: suspend () -> Unit,
        revokeBootstrap: suspend () -> Unit,
    ) {
        if (isTunnelActive()) {
            reloadTunnel()
            waitForTunnelReconnect()
        }
        runCatching { revokeBootstrap() }
    }

    private suspend fun awaitTunnelTransitionAfterReload() {
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + 8_000L
        var sawTransition = false
        var stoppedSince = 0L
        while (System.currentTimeMillis() < deadline) {
            val state = DataStore.serviceState
            when {
                state == BaseService.State.Stopping || state == BaseService.State.Connecting -> {
                    sawTransition = true
                    stoppedSince = 0L
                }
                state == BaseService.State.Stopped -> {
                    sawTransition = true
                    if (stoppedSince == 0L) stoppedSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - stoppedSince >= 2_500L) return
                }
                state == BaseService.State.Connected -> {
                    if (sawTransition) {
                        delay(300L)
                        return
                    }
                    if (System.currentTimeMillis() - startedAt >= 1_500L) {
                        return
                    }
                }
                else -> if (!state.canStop) return
            }
            delay(120L)
        }
    }

    private suspend fun freshAccessToken(
        authState: AuthState,
        authService: AuthorizationService,
    ): String = suspendCancellableCoroutine { continuation ->
        authState.performActionWithFreshTokens(authService) { accessToken, _, exception ->
            when {
                exception != null -> continuation.resumeWithException(exception)
                accessToken.isNullOrBlank() -> continuation.resumeWithException(
                    IllegalStateException("Missing access token"),
                )
                else -> continuation.resume(accessToken)
            }
        }
    }

    private fun fetchAppLinks(accessToken: String): String {
        val url = BuildConfig.YOKHAN_APP_API_BASE_URL.trimEnd('/') +
            "/api/app/links?profile=nekobox"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "text/plain")
            .build()

        authHttpClient().newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(LOG_TAG, "links_fetch_failed http=${response.code}")
                throw IllegalStateException("HTTP ${response.code}")
            }
            return try {
                String(Base64.decode(body, Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                body
            }
        }
    }

    /**
     * XHTTP cannot be represented as a VLESS link bean. When this build ships the Xray
     * sidecar, the server's raw profile becomes a normal "sing-box config" profile in the
     * GATES group; the runtime bridge starts Xray for it.
     */
    private fun fetchAppProfiles(accessToken: String): List<XhttpProfileRef> {
        val url = BuildConfig.YOKHAN_APP_API_BASE_URL.trimEnd('/') + "/api/app/profiles"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()
        return authHttpClient().newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throwIfUnlinkedAccountError(response.code, body)
                Log.w(LOG_TAG, "profiles_fetch_failed http=${response.code}")
                return@use emptyList()
            }
            val devices = JSONObject(body).optJSONArray("devices") ?: return@use emptyList()
            buildList {
                for (index in 0 until devices.length()) {
                    val device = devices.optJSONObject(index) ?: continue
                    val name = device.optString("name").trim()
                    if (name.isBlank()) continue
                    val label = device.optString("label").trim().ifBlank { name }
                    add(XhttpProfileRef(name, label))
                }
            }.sortedBy { if (it.name == "main") 0 else 1 }
        }
    }

    private fun fetchXhttpProfile(accessToken: String, deviceName: String? = null): ConfigBean? {
        if (!BuildConfig.YOKHAN_XHTTP_SIDECAR_ENABLED) {
            if (BuildConfig.DEBUG) Log.w(LOG_TAG, "xhttp_skip disabled")
            return null
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        if (abi !in setOf("arm64-v8a", "x86_64")) {
            if (BuildConfig.DEBUG) Log.w(LOG_TAG, "xhttp_skip abi=$abi")
            return null
        }
        val url = (BuildConfig.YOKHAN_APP_API_BASE_URL.trimEnd('/') + "/api/app/xhttp")
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("platform", "android")
            .apply {
                if (!deviceName.isNullOrBlank()) addQueryParameter("device", deviceName)
            }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()
        return authHttpClient().newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throwIfUnlinkedAccountError(response.code, body)
                if (response.code != 404) {
                    Log.w(LOG_TAG, "xhttp_fetch_failed http=${response.code}")
                }
                return@use null
            }
            if (!body.contains("\"xhttp\"")) {
                Log.w(LOG_TAG, "xhttp_body_rejected length=${body.length}")
                return@use null
            }
            if (BuildConfig.DEBUG) Log.w(LOG_TAG, "xhttp_profile_ready device=${deviceName ?: "main"}")
            ConfigBean().apply {
                name = if (deviceName.isNullOrBlank() || deviceName == "main") {
                    "Edge-xhttp"
                } else {
                    "Edge-xhttp-$deviceName"
                }
                type = 0
                config = body
            }
        }
    }

    internal class GatesAccountAccessException(message: String) : IllegalStateException(message)

    internal fun throwIfUnlinkedAccountError(statusCode: Int, body: String) {
        val code = runCatching {
            com.google.gson.JsonParser.parseString(body)
                .takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("code")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.trim()
                .orEmpty()
        }.getOrDefault("")
        when {
            code in setOf("GATES_USER_NOT_FOUND", "GATES_DEVICE_NOT_FOUND", "GATES_ACCESS_NOT_LINKED") ||
                statusCode == 403 -> throw GatesAccountAccessException(
                "Аккаунт не привязан к доступу GATES (нужна привязка Telegram в боте или выдача доступа). Пока можно войти по коду Древа.",
            )
            code == "GATES_ROOM_FORBIDDEN" -> throw GatesAccountAccessException(
                "Устройство не привязано к выбранной комнате GATES.",
            )
        }
    }

    private fun fetchAuthenticatedManifestPayload(context: Context, accessToken: String): String? {
        if (!YokhanRouteManifestManager.isEnabled()) return null

        val url = BuildConfig.YOKHAN_APP_API_BASE_URL.trimEnd('/') +
            "/api/app/manifest?v=${BuildConfig.YOKHAN_ROUTE_MANIFEST_VERSION}"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()

        authHttpClient().newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(LOG_TAG, "manifest_fetch_failed http=${response.code}")
                throw IllegalStateException("Manifest HTTP ${response.code}")
            }
            val verifier = YokhanRouteManifestVerifier()
            val manifest = verifier.verify(body)
            debugLog { "manifest_verified routes=${manifest.routes.size}" }
            YokhanRouteManifestStore(context.applicationContext).save(url, body, manifest)
            return YokhanRouteManifestManager.payloadForManifest(context, manifest)
                ?: error(context.getString(R.string.no_proxies_found))
        }
    }

    private inline fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG) Log.i(LOG_TAG, message())
    }

    internal suspend fun ensureAppGroup(context: Context): ProxyGroup {
        val name = context.getString(R.string.yokhan_auth_group_name)
        DataStore.ensureGroupsLoaded()
        return SagerDatabase.groupDao.allGroups().firstOrNull {
            it.type == GroupType.BASIC && it.name == name
        } ?: GroupManager.createGroup(
            ProxyGroup(
                type = GroupType.BASIC,
                name = name,
            ),
        )
    }
}

private object YokhanAuthStore {
    private const val PREFS = "yokhan_keycloak_auth"
    private const val KEY_AUTH_STATE = "auth_state"

    private fun file(context: Context) = AtomicFile(File(context.filesDir, "gates-auth-state.json"))

    fun load(context: Context): AuthState? {
        val store = file(context)
        val raw = if (store.baseFile.exists() || File(store.baseFile.path + ".bak").exists()) {
            runCatching { store.readFully().toString(Charsets.UTF_8) }.getOrNull() ?: return null
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_AUTH_STATE, null) ?: return null
        }
        val state = runCatching { AuthState.jsonDeserialize(raw) }.getOrNull() ?: return null
        return state.takeIf {
            authIssuerMatches(
                it.authorizationServiceConfiguration?.discoveryDoc?.issuer,
                BuildConfig.YOKHAN_KEYCLOAK_ISSUER,
            )
        }
    }

    fun save(context: Context, authState: AuthState) {
        val store = file(context)
        val output = store.startWrite()
        try {
            output.write(authState.jsonSerializeString().toByteArray(Charsets.UTF_8))
            store.finishWrite(output)
        } catch (error: Exception) {
            store.failWrite(output)
            throw error
        }
    }

    fun clear(context: Context) {
        val store = file(context)
        store.delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_AUTH_STATE).apply()
        context.getSharedPreferences("gates_auth_request", Context.MODE_PRIVATE).edit().clear().apply()
    }
}
