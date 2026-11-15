package io.nekohasekai.sagernet.utils

import android.annotation.TargetApi
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.UnknownHostException

object DefaultNetworkListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private val listeners = linkedMapOf<Any, (Network?) -> Unit>()
    private val pendingRequests = arrayListOf<CompletableDeferred<Network>>()
    @Volatile
    private var currentNetwork: Network? = null

    suspend fun start(key: Any, listener: (Network?) -> Unit) {
        val initialNetwork: Network? = stateMutex.withLock {
            val wasEmpty = listeners.isEmpty()
            listeners[key] = listener
            if (wasEmpty) {
                register()
                if (currentNetwork == null) {
                    currentNetwork = seedActiveNonVpnNetwork()
                }
            }
            currentNetwork
        }
        initialNetwork?.let(listener)
    }

    suspend fun get(): Network {
        if (fallback) @TargetApi(23) {
            return SagerNet.connectivity.activeNetwork ?: throw UnknownHostException()
        }
        val deferred = stateMutex.withLock {
            check(listeners.isNotEmpty()) { "Getting network without any listeners is not supported" }
            currentNetwork?.let { return it }
            CompletableDeferred<Network>().also { pendingRequests += it }
        }
        return deferred.await()
    }

    suspend fun stop(key: Any) {
        stateMutex.withLock {
            if (listeners.remove(key) != null && listeners.isEmpty()) {
                currentNetwork = null
                pendingRequests.clear()
                unregister()
            }
        }
    }

    private fun seedActiveNonVpnNetwork(): Network? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return runCatching {
            val active = SagerNet.connectivity.activeNetwork ?: return null
            val caps = SagerNet.connectivity.getNetworkCapabilities(active) ?: return null
            active.takeUnless { caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        }.getOrNull()
    }

    private fun dispatchPut(network: Network) {
        scope.launch {
            val (snapshotListeners, waiters) = stateMutex.withLock {
                currentNetwork = network
                val waiting = ArrayList(pendingRequests)
                pendingRequests.clear()
                listeners.values.toList() to waiting
            }
            waiters.forEach { it.complete(network) }
            snapshotListeners.forEach { it(network) }
        }
    }

    private fun dispatchUpdate(network: Network) {
        scope.launch {
            val snapshotListeners = stateMutex.withLock {
                if (currentNetwork == network) listeners.values.toList() else emptyList()
            }
            snapshotListeners.forEach { it(network) }
        }
    }

    private fun dispatchLost(network: Network) {
        scope.launch {
            val snapshotListeners = stateMutex.withLock {
                if (currentNetwork == network) {
                    currentNetwork = null
                    listeners.values.toList()
                } else {
                    emptyList()
                }
            }
            snapshotListeners.forEach { it(null) }
        }
    }

    private object Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = dispatchPut(network)

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = dispatchUpdate(network)

        override fun onLost(network: Network) = dispatchLost(network)
    }

    private var fallback = false
    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        if (Build.VERSION.SDK_INT == 23) {
            removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        }
    }.build()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun register() {
        try {
            fallback = false
            when (Build.VERSION.SDK_INT) {
                in 31..Int.MAX_VALUE -> @TargetApi(31) {
                    SagerNet.connectivity.registerBestMatchingNetworkCallback(
                        request, Callback, mainHandler
                    )
                }
                in 28 until 31 -> @TargetApi(28) {
                    SagerNet.connectivity.requestNetwork(request, Callback, mainHandler)
                }
                in 26 until 28 -> @TargetApi(26) {
                    SagerNet.connectivity.registerDefaultNetworkCallback(Callback, mainHandler)
                }
                in 24 until 26 -> @TargetApi(24) {
                    SagerNet.connectivity.registerDefaultNetworkCallback(Callback)
                }
                else -> {
                    SagerNet.connectivity.requestNetwork(request, Callback)
                }
            }
        } catch (e: Exception) {
            Logs.w(e)
            fallback = true
        }
    }

    private fun unregister() {
        runCatching { SagerNet.connectivity.unregisterNetworkCallback(Callback) }
    }
}
