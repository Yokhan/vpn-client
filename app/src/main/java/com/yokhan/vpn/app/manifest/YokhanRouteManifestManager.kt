package com.yokhan.gates.app.manifest

import android.content.Context
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import com.yokhan.gates.app.routing.YokhanRouteLocalObservationStore
import com.yokhan.gates.app.routing.YokhanRouteObservationScope
import com.yokhan.gates.app.routing.currentYokhanNetworkBucket

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
        return payloadForManifest(context, manifest)
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

    fun isEnabled(): Boolean {
        return BuildConfig.YOKHAN_ROUTE_MANIFEST_ENABLED &&
            BuildConfig.YOKHAN_ROUTE_MANIFEST_PUBLIC_KEYS.isNotBlank()
    }

    fun recordTunnelConnected(context: Context) {
        if (!isEnabled() || !BuildConfig.DEBUG) return
        Logs.d("GATES tunnel connected; route remains unverified until quality evidence exists")
    }

    fun recordConnectionFailure(context: Context) {
        if (!isEnabled()) return
        val appContext = context.applicationContext
        runOnIoDispatcher {
            val manifest = YokhanRouteManifestStore(appContext)
                .loadLastVerified(YokhanRouteManifestVerifier()) ?: return@runOnIoDispatcher
            val profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy) ?: return@runOnIoDispatcher
            val routeId = profile.displayName().orEmpty().routeIdFromProfileName() ?: return@runOnIoDispatcher
            val nowSeconds = System.currentTimeMillis() / 1000L
            YokhanRouteLocalObservationStore(appContext).recordQuarantine(
                scope = observationScope(appContext, manifest),
                routeId = routeId,
                quarantineUntilEpochSeconds = nowSeconds + INITIAL_FAILURE_QUARANTINE_SECONDS,
                nowEpochSeconds = nowSeconds,
                localScore = 0,
            )
        }
    }

    fun payloadForManifest(context: Context, manifest: YokhanRouteManifest): String? {
        val nowSeconds = System.currentTimeMillis() / 1000L
        val state = YokhanRouteLocalObservationStore(context)
            .load(observationScope(context, manifest), nowSeconds)
        return manifest.activeRouteLinks(state, nowSeconds).joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun observationScope(
        context: Context,
        manifest: YokhanRouteManifest,
    ) = YokhanRouteObservationScope(
        coarseNetworkBucket = currentYokhanNetworkBucket(context),
        manifestFingerprint = manifest.routingFingerprint(),
    )

    private fun String.routeIdFromProfileName(): String? {
        val normalized = lowercase()
        return listOf("bridge", "relay", "naive", "hy2")
            .firstOrNull { routeId -> routeId in normalized }
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

private const val INITIAL_FAILURE_QUARANTINE_SECONDS = 15L * 60L
