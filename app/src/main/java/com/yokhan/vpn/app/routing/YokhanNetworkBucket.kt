package com.yokhan.gates.app.routing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

fun currentYokhanNetworkBucket(context: Context): String {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return "other"
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return legacyNetworkBucket(manager)
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "other"
    return when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }
}

@Suppress("DEPRECATION")
private fun legacyNetworkBucket(manager: ConnectivityManager): String =
    when (manager.activeNetworkInfo?.type) {
        ConnectivityManager.TYPE_WIFI -> "wifi"
        ConnectivityManager.TYPE_MOBILE -> "cellular"
        ConnectivityManager.TYPE_ETHERNET -> "ethernet"
        else -> "other"
    }
