package com.yokhan.gates.app.manifest

import android.content.Context
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher

object YokhanRouteManifestManager {

    fun refreshOnLaunch(context: Context) {
        if (!isEnabled()) return
        val appContext = context.applicationContext
        runOnIoDispatcher {
            refresh(appContext)
        }
    }

    suspend fun refresh(context: Context): YokhanRouteManifestResult {
        val verifier = YokhanRouteManifestVerifier()
        val store = YokhanRouteManifestStore(context)
        val manifestUrl = findManifestUrl()
            ?: return YokhanRouteManifestResult.Unavailable("No subscription manifest URL", null)
        return refresh(context, manifestUrl, verifier, store)
    }

    suspend fun rawSubscriptionPayloadFor(context: Context, subscriptionLink: String?): String? {
        if (!isEnabled()) return null
        val manifestUrl = YokhanRouteManifestUrls.fromSubscriptionLink(
            subscriptionLink,
            BuildConfig.YOKHAN_ROUTE_MANIFEST_VERSION,
        ) ?: return null
        val result = refresh(context.applicationContext, manifestUrl)
        val manifest = when (result) {
            is YokhanRouteManifestResult.Updated -> result.manifest
            is YokhanRouteManifestResult.Cached -> result.manifest
            is YokhanRouteManifestResult.Unavailable -> result.cached
        } ?: return null
        return manifest.toRawSubscriptionPayload().takeIf { it.isNotBlank() }
    }

    private fun refresh(
        context: Context,
        manifestUrl: String,
        verifier: YokhanRouteManifestVerifier = YokhanRouteManifestVerifier(),
        store: YokhanRouteManifestStore = YokhanRouteManifestStore(context),
    ): YokhanRouteManifestResult {
        val result = YokhanRouteManifestClient(verifier, store).fetchAndCache(manifestUrl)
        if (BuildConfig.DEBUG) {
            Logs.d("Yokhan manifest refresh: $result")
        }
        return result
    }

    private fun isEnabled(): Boolean {
        return BuildConfig.YOKHAN_ROUTE_MANIFEST_ENABLED &&
            BuildConfig.YOKHAN_ROUTE_MANIFEST_PUBLIC_KEYS.isNotBlank()
    }

    private suspend fun findManifestUrl(): String? {
        val subscriptions = SagerDatabase.groupDao.subscriptions()
        for (group in subscriptions) {
            if (group.type != GroupType.SUBSCRIPTION) continue
            val url = YokhanRouteManifestUrls.fromSubscriptionLink(
                group.subscription?.link,
                BuildConfig.YOKHAN_ROUTE_MANIFEST_VERSION,
            )
            if (url != null) return url
        }
        return null
    }
}
