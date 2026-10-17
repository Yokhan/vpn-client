package com.yokhan.gates.app.manifest

import android.util.Base64
import android.util.Log
import io.nekohasekai.sagernet.BuildConfig
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest

class YokhanRouteManifestVerifier(
    private val publicKeys: Map<String, String> = parsePublicKeys(BuildConfig.YOKHAN_ROUTE_MANIFEST_PUBLIC_KEYS),
) {

    fun verify(rawJson: String, nowSeconds: Long = System.currentTimeMillis() / 1000L): YokhanRouteManifest {
        val manifest = YokhanRouteManifestParser.parse(rawJson)
        val signature = manifest.signature ?: error("Manifest is unsigned")
        require(signature.alg == "Ed25519") { "Unsupported manifest signature: ${signature.alg}" }
        require(manifest.version in SUPPORTED_MANIFEST_VERSIONS) {
            "Unsupported manifest version: ${manifest.version}"
        }
        require(manifest.routes.isNotEmpty()) { "Manifest has no routes" }
        require(manifest.minAppVersion <= BuildConfig.VERSION_CODE) {
            "App version is too old for this manifest"
        }
        require(!manifest.isExpired(nowSeconds)) { "Manifest is expired" }

        val publicKey = publicKeys[signature.keyId]
            ?: error("Unknown manifest key id: ${signature.keyId}")
        val payload = YokhanRouteManifestParser.canonicalUnsignedPayload(rawJson)
        val signatureBytes = decodeBase64Url(signature.value)
        val publicKeyBytes = decodeBase64Url(publicKey)
        if (BuildConfig.DEBUG) {
            Log.i(
                LOG_TAG,
                "manifest_verify key_id=${signature.keyId} payload_len=${payload.size} " +
                    "payload_sha256=${sha256Hex(payload)} public_key_len=${publicKeyBytes.size} " +
                    "public_key_sha256=${sha256Hex(publicKeyBytes)} signature_len=${signatureBytes.size}",
            )
        }
        require(verifyEd25519(payload, signatureBytes, publicKeyBytes)) {
            "Manifest signature is invalid"
        }
        return manifest
    }

    private fun verifyEd25519(payload: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        require(publicKey.size == ED25519_PUBLIC_KEY_BYTES) { "Ed25519 public key must be 32 bytes" }
        val curve = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val verifier = EdDSAEngine(MessageDigest.getInstance(curve.hashAlgorithm))
        verifier.initVerify(EdDSAPublicKey(EdDSAPublicKeySpec(publicKey, curve)))
        verifier.update(payload)
        return verifier.verify(signature)
    }

    companion object {
        fun parsePublicKeys(value: String): Map<String, String> {
            val raw = value.trim()
            if (raw.isBlank()) return emptyMap()
            return raw.split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .associate { entry ->
                    val keyId = entry.substringBefore(':', DEFAULT_KEY_ID).trim()
                    val key = entry.substringAfter(':', entry).trim()
                    keyId to key
                }
        }

        fun decodeBase64Url(value: String): ByteArray {
            val padded = value.trim() + "=".repeat((4 - value.trim().length % 4) % 4)
            return Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
        }

        private const val DEFAULT_KEY_ID = "manifest-v1"
        private const val ED25519_PUBLIC_KEY_BYTES = 32
        private const val LOG_TAG = "GatesManifest"
        private val SUPPORTED_MANIFEST_VERSIONS = setOf(1, 2)

        private fun sha256Hex(value: ByteArray): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(value)
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
