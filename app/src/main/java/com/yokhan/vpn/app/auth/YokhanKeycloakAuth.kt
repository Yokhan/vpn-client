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
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.File

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

    suspend fun restoreSession(context: Context) = withContext(Dispatchers.IO) {
        sessionGate.withLock {
            mutableSessionAvailable.value = YokhanAuthStore.load(context)?.isAuthorized == true
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
                activity.showDashboardMessage(R.string.yokhan_auth_sync_failed)
                // The next explicit tap opens the browser; an outage does not discard saved keys.
                mutableSessionAvailable.value = false
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
        val authService = AuthorizationService(appContext)
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
        if (mutableBusy.value) return
        if (!isConfigured()) {
            activity.showDashboardMessage(R.string.yokhan_auth_not_configured)
            return
        }

        mutableBusy.value = true

        AuthorizationServiceConfiguration.fetchFromIssuer(
            Uri.parse(BuildConfig.YOKHAN_KEYCLOAK_ISSUER),
        ) { serviceConfiguration, exception ->
            if (exception != null || serviceConfiguration == null) {
                mutableBusy.value = false
                Logs.w(exception ?: IllegalStateException("Missing auth service config"))
                activity.runOnUiThread {
                    activity.showDashboardMessage(R.string.yokhan_auth_open_failed)
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
        }
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
                val syncService = AuthorizationService(appContext)
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
                        Log.w(LOG_TAG, "config_import_failed ${e.javaClass.simpleName}: ${e.message}")
                        Logs.w(e)
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
            ?: error(context.getString(R.string.yokhan_auth_missing_session))
        debugLog { "fresh_token_ok" }

        val payload = runCatching { fetchAuthenticatedManifestPayload(context, accessToken) }
            .onFailure { error -> Log.w(LOG_TAG, "manifest_fallback_to_links", error) }
            .getOrNull()
            ?: fetchAppLinks(accessToken)
        debugLog { "config_payload_loaded length=${payload.length}" }
        val proxies = RawUpdater.parseRaw(payload)
            ?: error(context.getString(R.string.no_proxies_found))
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
            val currentExists = SagerDatabase.proxyDao.getById(DataStore.currentProfile) != null
            initialImportedProfile(DataStore.selectedProxy, if (currentExists) DataStore.currentProfile else 0L,
                DataStore.serviceState.canStop, importedIds)?.let { first ->
                DataStore.selectedProxy = first
                DataStore.currentProfile = first
                DataStore.selectedGroup = group.id
            }
            GroupManager.postReload(group.id)
        }
        proxies.size
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

        OkHttpClient().newCall(request).execute().use { response ->
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

    private fun fetchAuthenticatedManifestPayload(context: Context, accessToken: String): String? {
        if (!YokhanRouteManifestManager.isEnabled()) return null

        val url = BuildConfig.YOKHAN_APP_API_BASE_URL.trimEnd('/') +
            "/api/app/manifest?v=${BuildConfig.YOKHAN_ROUTE_MANIFEST_VERSION}"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()

        OkHttpClient().newCall(request).execute().use { response ->
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

    private suspend fun ensureAppGroup(context: Context): ProxyGroup {
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
}
