package com.yokhan.gates.app.manifest

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util

class YokhanRouteManifestClient(
    private val verifier: YokhanRouteManifestVerifier,
    private val store: YokhanRouteManifestStore,
) {

    fun fetchAndCache(url: String): YokhanRouteManifestResult {
        val cached = store.loadVerified(verifier, url)
        return try {
            val rawJson = fetchRawManifest(url, useProxy = false)
            val manifest = verifier.verify(rawJson)
            store.save(url, rawJson, manifest)
            YokhanRouteManifestResult.Updated(manifest)
        } catch (directError: Exception) {
            Logs.w(directError)
            try {
                val rawJson = fetchRawManifest(url, useProxy = true)
                val manifest = verifier.verify(rawJson)
                store.save(url, rawJson, manifest)
                YokhanRouteManifestResult.Updated(manifest)
            } catch (proxyError: Exception) {
                Logs.w(proxyError)
                if (cached != null) {
                    YokhanRouteManifestResult.Cached(cached)
                } else {
                    YokhanRouteManifestResult.Unavailable(
                        proxyError.message ?: proxyError.javaClass.simpleName,
                        null,
                    )
                }
            }
        }
    }

    private fun fetchRawManifest(url: String, useProxy: Boolean): String {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryH3Direct()
            if (useProxy) {
                trySocks5(DataStore.mixedPort)
            }
        }
        return try {
            val response = client.newRequest().apply {
                setURL(url)
                setUserAgent(USER_AGENT)
            }.execute()
            Util.getStringBox(response.contentString)
        } catch (e: Exception) {
            throw e
        } finally {
            client.close()
        }
    }

    private companion object {
        const val USER_AGENT = "GATES-Android/manifest-v1"
    }
}
