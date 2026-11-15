@file:Suppress("UnstableApiUsage")

import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
    id("org.jetbrains.kotlin.plugin.compose")
}

setupApp()

val composeDashboardEnabled = true

fun projectPropertyOrEnv(name: String): String {
    return providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .orElse("")
        .get()
        .trim()
}

fun booleanProjectPropertyOrEnv(name: String, defaultValue: Boolean = false): Boolean {
    val raw = projectPropertyOrEnv(name).lowercase()
    if (raw.isEmpty()) return defaultValue
    return raw in setOf("1", "true", "yes", "on")
}

fun intProjectPropertyOrEnv(name: String, defaultValue: Int): Int {
    return projectPropertyOrEnv(name).toIntOrNull() ?: defaultValue
}

fun quotedBuildConfigString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$escaped\""
}

val yokhanRouteManifestEnabled = booleanProjectPropertyOrEnv("YOKHAN_ROUTE_MANIFEST_ENABLED")
val yokhanRouteManifestPublicKeys = projectPropertyOrEnv("YOKHAN_ROUTE_MANIFEST_PUBLIC_KEYS")
val yokhanRouteManifestVersion = intProjectPropertyOrEnv("YOKHAN_ROUTE_MANIFEST_VERSION", 1)
val yokhanKeycloakIssuer = projectPropertyOrEnv("YOKHAN_KEYCLOAK_ISSUER")
    .ifBlank { "https://auth.yokhan.xyz/realms/yokhan" }
val yokhanKeycloakClientId = projectPropertyOrEnv("YOKHAN_KEYCLOAK_CLIENT_ID")
    .ifBlank { "gates-android" }
val yokhanKeycloakRedirectScheme =
    projectPropertyOrEnv("YOKHAN_KEYCLOAK_REDIRECT_SCHEME").ifBlank { "gates" }
val yokhanKeycloakRedirectUri = projectPropertyOrEnv("YOKHAN_KEYCLOAK_REDIRECT_URI")
    .ifBlank { "$yokhanKeycloakRedirectScheme://auth/callback" }
val yokhanAppApiBaseUrl = projectPropertyOrEnv("YOKHAN_APP_API_BASE_URL")
    .ifBlank { "https://app.yokhan.xyz/gates" }
val yokhanUpdateSignerSha256 = projectPropertyOrEnv("YOKHAN_UPDATE_SIGNER_SHA256")
    .ifBlank { "b174e202d24f73eb703265f3270c2784eba0ae80d6a1ad0979ad9b87f7d4a504" }
val gatesAndroidKeystoreFile = projectPropertyOrEnv("GATES_ANDROID_KEYSTORE_FILE")
val gatesAndroidKeystorePassword = projectPropertyOrEnv("GATES_ANDROID_KEYSTORE_PASSWORD")
val gatesAndroidKeyAlias = projectPropertyOrEnv("GATES_ANDROID_KEY_ALIAS")
val gatesAndroidKeyPassword = projectPropertyOrEnv("GATES_ANDROID_KEY_PASSWORD")
val gatesAndroidSigningEnabled = gatesAndroidKeystoreFile.isNotBlank() &&
    gatesAndroidKeystorePassword.isNotBlank() &&
    gatesAndroidKeyAlias.isNotBlank() &&
    gatesAndroidKeyPassword.isNotBlank()
val pinnedAndroidXraySha256 = mapOf(
    "arm64-v8a" to "ce8f18c44eb40cbd4563433109e0ede36c38b89c2fe71d1e583b4332ebefb622",
    "x86_64" to "894651c1de4e2b9cb51775c13ed168e8c8148be7220d2b5db15a42362c5b3263",
)
// XHTTP sidecar capability. Enabled by default when the shipped libxray.so binaries match their
// pinned SHA-256 digests; can still be disabled explicitly with YOKHAN_XHTTP_SIDECAR_ENABLED=false.
val yokhanXhttpSidecarEnabled = booleanProjectPropertyOrEnv("YOKHAN_XHTTP_SIDECAR_ENABLED", defaultValue = true)
val yokhanXrayVersion = projectPropertyOrEnv("YOKHAN_XRAY_VERSION").ifBlank { "26.10.31" }

val verifyPinnedAndroidXray = tasks.register("verifyPinnedAndroidXray") {
    doLast {
        pinnedAndroidXraySha256.forEach { (abi, expected) ->
            val binary = file("src/main/jniLibs/$abi/libxray.so")
            check(binary.isFile) { "Pinned Android Xray binary is missing: ${binary.path}" }
            val digest = MessageDigest.getInstance("SHA-256")
            binary.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
            check(actual == expected) {
                "libxray.so SHA-256 mismatch for $abi: expected $expected, got $actual"
            }
        }
    }
}

if (yokhanXhttpSidecarEnabled) {
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(verifyPinnedAndroidXray)
    }
}

android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    ksp {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    defaultConfig {
        // Production ships arm64-v8a only via the ABI split in buildSrc/Helpers.kt.
        manifestPlaceholders["appAuthRedirectScheme"] = yokhanKeycloakRedirectScheme
        buildConfigField("boolean", "ENABLE_COMPOSE_DASHBOARD", composeDashboardEnabled.toString())
        buildConfigField("String", "YOKHAN_PROVIDER_PACKAGE", "\"com.yokhan.gates.provider\"")
        buildConfigField("String", "YOKHAN_PROVIDER_CERT_SHA256", "\"\"")
        buildConfigField("boolean", "YOKHAN_ROUTE_MANIFEST_ENABLED", yokhanRouteManifestEnabled.toString())
        buildConfigField("String", "YOKHAN_ROUTE_MANIFEST_PUBLIC_KEYS", quotedBuildConfigString(yokhanRouteManifestPublicKeys))
        buildConfigField("int", "YOKHAN_ROUTE_MANIFEST_VERSION", yokhanRouteManifestVersion.toString())
        buildConfigField("String", "YOKHAN_KEYCLOAK_ISSUER", quotedBuildConfigString(yokhanKeycloakIssuer))
        buildConfigField("String", "YOKHAN_KEYCLOAK_CLIENT_ID", quotedBuildConfigString(yokhanKeycloakClientId))
        buildConfigField("String", "YOKHAN_KEYCLOAK_REDIRECT_URI", quotedBuildConfigString(yokhanKeycloakRedirectUri))
        buildConfigField("String", "YOKHAN_APP_API_BASE_URL", quotedBuildConfigString(yokhanAppApiBaseUrl))
        buildConfigField("String", "YOKHAN_UPDATE_SIGNER_SHA256", quotedBuildConfigString(yokhanUpdateSignerSha256))
        buildConfigField("boolean", "YOKHAN_XHTTP_SIDECAR_ENABLED", yokhanXhttpSidecarEnabled.toString())
        buildConfigField("String", "YOKHAN_XRAY_VERSION", quotedBuildConfigString(yokhanXrayVersion))
    }
    bundle {
        language {
            enableSplit = false
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
        compose = true
    }
    signingConfigs {
        if (gatesAndroidSigningEnabled) {
            create("gatesRelease") {
                storeFile = file(gatesAndroidKeystoreFile)
                storePassword = gatesAndroidKeystorePassword
                keyAlias = gatesAndroidKeyAlias
                keyPassword = gatesAndroidKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            if (gatesAndroidSigningEnabled) {
                signingConfig = signingConfigs.getByName("gatesRelease")
            }
        }
    }
    namespace = "io.nekohasekai.sagernet"
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
}

dependencies {

    implementation(project(":shared-domain"))
    implementation(project(":shared-ui"))

    testImplementation(kotlin("test"))

    implementation(fileTree("libs"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.5.6")
    implementation("androidx.browser:browser:1.5.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.5.3")
    implementation("androidx.navigation:navigation-ui-ktx:2.5.3")
    implementation("androidx.preference:preference-ktx:1.2.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.work:work-runtime-ktx:2.8.1")
    implementation("androidx.work:work-multiprocess:2.8.1")

    implementation("com.google.android.material:material:1.8.0")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.google.code.gson:gson:2.9.0")
    implementation("net.i2p.crypto:eddsa:0.3.0")
    implementation("net.openid:appauth:0.11.1")

    implementation("com.github.jenly1314:zxing-lite:2.1.1")
    implementation("com.blacksquircle.ui:editorkit:2.6.0")
    implementation("com.blacksquircle.ui:language-base:2.6.0")
    implementation("com.blacksquircle.ui:language-json:2.6.0")

    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.3")
    implementation("org.yaml:snakeyaml:1.30")
    implementation("com.github.daniel-stoneuk:material-about-library:3.2.0-rc01")
    implementation("com.jakewharton:process-phoenix:2.1.2")
    implementation("com.esotericsoftware:kryo:5.2.1")
    implementation("com.google.guava:guava:31.0.1-android")
    implementation("org.ini4j:ini4j:0.5.4")

    implementation("com.simplecityapps:recyclerview-fastscroll:2.0.1") {
        exclude(group = "androidx.recyclerview")
        exclude(group = "androidx.appcompat")
    }

    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("com.github.MatrixDev.Roomigrant:RoomigrantLib:0.3.4")
    ksp("com.github.MatrixDev.Roomigrant:RoomigrantCompiler:0.3.4")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
}
