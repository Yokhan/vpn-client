package io.nekohasekai.sagernet.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.listenForPackageChanges
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.plugin.Plugins
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

object PackageCache {

    @Volatile
    var installedPackages: Map<String, PackageInfo> = emptyMap()
        private set

    @Volatile
    var installedPluginPackages: Map<String, PackageInfo> = emptyMap()
        private set

    @Volatile
    var installedApps: Map<String, ApplicationInfo> = emptyMap()
        private set

    @Volatile
    var packageMap: Map<String, Int> = emptyMap()
        private set

    @Volatile
    var uidMap: Map<Int, Set<String>> = emptyMap()
        private set

    val loaded = Mutex(true)
    var registerd = AtomicBoolean(false)
    private val readyLatch = CountDownLatch(1)
    @Volatile
    private var initialized = false
    private val reloadLock = Any()
    private val labelMap = ConcurrentHashMap<String, String>()

    fun register() {
        if (registerd.getAndSet(true)) return
        reload()
        app.listenForPackageChanges(false) {
            reload()
            labelMap.clear()
        }
        if (loaded.isLocked) {
            runCatching { loaded.unlock() }
        }
    }

    @SuppressLint("InlinedApi")
    fun reload() {
        synchronized(reloadLock) {
            // Single Binder query instead of separate getInstalledPackages + getInstalledApplications calls.
            val rawPackageInfo = app.packageManager.getInstalledPackages(
                PackageManager.MATCH_UNINSTALLED_PACKAGES
                    or PackageManager.GET_PERMISSIONS
                    or PackageManager.GET_PROVIDERS
            )

            val nextInstalledPackages = LinkedHashMap<String, PackageInfo>(rawPackageInfo.size)
            val nextPluginPackages = LinkedHashMap<String, PackageInfo>()
            val nextInstalledApps = LinkedHashMap<String, ApplicationInfo>(rawPackageInfo.size)
            val nextPackageMap = LinkedHashMap<String, Int>(rawPackageInfo.size)
            val nextUidMap = HashMap<Int, HashSet<String>>(rawPackageInfo.size)

            for (info in rawPackageInfo) {
                val pkg = info.packageName
                if (pkg == "android" || info.requestedPermissions?.contains(Manifest.permission.INTERNET) == true) {
                    nextInstalledPackages[pkg] = info
                }
                if (Plugins.isExe(info)) {
                    nextPluginPackages[pkg] = info
                }
                val appInfo = info.applicationInfo
                if (appInfo != null) {
                    nextInstalledApps[pkg] = appInfo
                    nextPackageMap[pkg] = appInfo.uid
                    nextUidMap.getOrPut(appInfo.uid) { HashSet() }.add(pkg)
                }
            }

            installedPackages = nextInstalledPackages
            installedPluginPackages = nextPluginPackages
            installedApps = nextInstalledApps
            packageMap = nextPackageMap
            uidMap = nextUidMap
            initialized = true
            readyLatch.countDown()
        }
    }

    operator fun get(uid: Int): Set<String>? = uidMap[uid]
    operator fun get(packageName: String): Int? = packageMap[packageName]

    fun awaitLoadSync() {
        if (initialized) return
        if (!registerd.get()) {
            register()
            return
        }
        runBlocking {
            loaded.withLock {
                // Ensure register() finished initializing snapshots.
            }
        }
    }

    fun loadLabel(packageName: String): String {
        labelMap[packageName]?.let { return it }
        val info = installedApps[packageName] ?: return packageName
        val label = info.loadLabel(app.packageManager).toString()
        labelMap[packageName] = label
        return label
    }
}
