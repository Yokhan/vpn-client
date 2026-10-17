package com.yokhan.gates.app.manifest

import android.content.Context
import java.security.MessageDigest

class YokhanRouteManifestStore(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(cacheKey: String, rawJson: String, manifest: YokhanRouteManifest) {
        preferences.edit()
            .putString(key(cacheKey, KEY_RAW_JSON), rawJson)
            .putString(key(cacheKey, KEY_MANIFEST_ID), manifest.manifestId)
            .putLong(key(cacheKey, KEY_EXPIRES_AT), manifest.expiresAt)
            .putLong(key(cacheKey, KEY_UPDATED_AT), System.currentTimeMillis())
            .putString(KEY_LAST_CACHE_KEY, cacheKey)
            .apply()
    }

    fun loadRaw(cacheKey: String): String? = preferences.getString(key(cacheKey, KEY_RAW_JSON), null)

    fun loadVerified(verifier: YokhanRouteManifestVerifier, cacheKey: String): YokhanRouteManifest? {
        val raw = loadRaw(cacheKey) ?: return null
        return runCatching { verifier.verify(raw) }.getOrNull()
    }

    fun loadLastVerified(verifier: YokhanRouteManifestVerifier): YokhanRouteManifest? {
        val cacheKey = preferences.getString(KEY_LAST_CACHE_KEY, null) ?: return null
        return loadVerified(verifier, cacheKey)
    }

    fun clear(cacheKey: String) {
        preferences.edit()
            .remove(key(cacheKey, KEY_RAW_JSON))
            .remove(key(cacheKey, KEY_MANIFEST_ID))
            .remove(key(cacheKey, KEY_EXPIRES_AT))
            .remove(key(cacheKey, KEY_UPDATED_AT))
            .apply()
    }

    private fun key(cacheKey: String, suffix: String): String = "${sha256(cacheKey)}_$suffix"

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val PREFS_NAME = "yokhan_route_manifest"
        private const val KEY_RAW_JSON = "raw_json"
        private const val KEY_MANIFEST_ID = "manifest_id"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val KEY_LAST_CACHE_KEY = "last_cache_key"
    }
}
