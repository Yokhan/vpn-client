package com.yokhan.gates.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.lifecycleScope
import com.yokhan.gates.app.ui.MainActivity
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
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

object YokhanKeycloakAuth {

    fun isConfigured(): Boolean {
        return BuildConfig.YOKHAN_KEYCLOAK_ISSUER.isNotBlank() &&
            BuildConfig.YOKHAN_KEYCLOAK_CLIENT_ID.isNotBlank() &&
            BuildConfig.YOKHAN_KEYCLOAK_REDIRECT_URI.isNotBlank() &&
            BuildConfig.YOKHAN_APP_API_BASE_URL.isNotBlank()
    }

    fun startLogin(
        activity: MainActivity,
        authService: AuthorizationService,
        launcher: ActivityResultLauncher<Intent>,
    ) {
        if (!isConfigured()) {
            activity.showDashboardMessage(R.string.yokhan_auth_not_configured)
            return
        }

        AuthorizationServiceConfiguration.fetchFromIssuer(
            Uri.parse(BuildConfig.YOKHAN_KEYCLOAK_ISSUER),
        ) { serviceConfiguration, exception ->
            if (exception != null || serviceConfiguration == null) {
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

            activity.runOnUiThread {
                launcher.launch(authService.getAuthorizationRequestIntent(request))
            }
        }
    }

    fun handleAuthorizationResponse(
        activity: MainActivity,
        authService: AuthorizationService,
        data: Intent?,
    ) {
        if (data == null) {
            activity.showDashboardMessage(R.string.yokhan_auth_cancelled)
            return
        }

        val response = AuthorizationResponse.fromIntent(data)
        val exception = AuthorizationException.fromIntent(data)
        if (response == null) {
            Logs.w(exception ?: IllegalStateException("Missing auth response"))
            activity.showDashboardMessage(R.string.yokhan_auth_failed)
            return
        }

        val authState = AuthState(response, exception)
        authService.performTokenRequest(response.createTokenExchangeRequest()) { token, tokenError ->
            if (tokenError != null || token == null) {
                Logs.w(tokenError ?: IllegalStateException("Missing token response"))
                activity.runOnUiThread {
                    activity.showDashboardMessage(R.string.yokhan_auth_failed)
                }
                return@performTokenRequest
            }

            authState.update(token, tokenError)
            YokhanAuthStore.save(activity, authState)
            activity.runOnUiThread {
                activity.lifecycleScope.launch {
                    try {
                        val count = importAuthenticatedConfig(activity, authService)
                        activity.onYokhanAuthImportComplete(count)
                    } catch (e: Exception) {
                        Logs.w(e)
                        activity.showDashboardMessage(
                            activity.getString(
                                R.string.yokhan_auth_import_failed,
                                e.message ?: "",
                            ),
                        )
                    }
                }
            }
        }
    }

    suspend fun importAuthenticatedConfig(
        context: Context,
        authService: AuthorizationService,
    ): Int = withContext(Dispatchers.IO) {
        val authState = YokhanAuthStore.load(context)
            ?: error(context.getString(R.string.yokhan_auth_missing_session))
        val accessToken = freshAccessToken(authState, authService)
        YokhanAuthStore.save(context, authState)

        val payload = fetchAppLinks(accessToken)
        val proxies = RawUpdater.parseRaw(payload)
            ?: error(context.getString(R.string.no_proxies_found))

        val group = ensureAppGroup(context)
        GroupManager.clearGroup(group.id)
        val created = proxies.map { ProfileManager.createProfile(group.id, it) }
        DataStore.selectedGroup = group.id
        created.firstOrNull()?.let { first ->
            DataStore.selectedProxy = first.id
            DataStore.currentProfile = first.id
        }
        GroupManager.postReload(group.id)
        if (DataStore.serviceState.canStop) {
            SagerNet.reloadService()
        }
        created.size
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
                throw IllegalStateException("HTTP ${response.code}")
            }
            return try {
                String(Base64.decode(body, Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                body
            }
        }
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

    fun load(context: Context): AuthState? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_AUTH_STATE, null)
            ?: return null
        return runCatching { AuthState.jsonDeserialize(raw) }.getOrNull()
    }

    fun save(context: Context, authState: AuthState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AUTH_STATE, authState.jsonSerializeString())
            .apply()
    }
}
