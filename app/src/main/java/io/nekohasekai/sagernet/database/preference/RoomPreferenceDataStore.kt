package io.nekohasekai.sagernet.database.preference

import androidx.preference.PreferenceDataStore
import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Suppress("MemberVisibilityCanBePrivate", "unused")
open class RoomPreferenceDataStore(
    private val kvPairDao: KeyValuePair.Dao,
    database: RoomDatabase? = null,
    private val preload: Boolean = true,
    vararg observedTables: String,
) :
    PreferenceDataStore() {

    private val workerThreadName = "RoomPreferenceDataStore-${System.identityHashCode(this)}"
    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, workerThreadName).apply {
                isDaemon = true
            }
        }
    @Volatile
    private var cache: Map<String, KeyValuePair> = emptyMap()
    @Volatile
    private var loaded = false
    private val invalidationObserver =
        if (database != null && observedTables.isNotEmpty()) object : InvalidationTracker.Observer(
            observedTables.first(),
            *observedTables.drop(1).toTypedArray()
        ) {
            override fun onInvalidated(tables: Set<String>) {
                writeExecutor.execute(::refreshLocked)
            }
        } else null

    init {
        if (preload) {
            executeWrite(::refreshLocked)
        }
        invalidationObserver?.let { observer ->
            database!!.invalidationTracker.addObserver(observer)
        }
    }

    fun all(): List<KeyValuePair> {
        ensureLoaded()
        return cache.values.toList()
    }
    fun getBoolean(key: String): Boolean? {
        ensureLoaded()
        return cache[key]?.boolean
    }
    fun getFloat(key: String): Float? {
        ensureLoaded()
        return cache[key]?.float
    }
    fun getInt(key: String): Int? {
        ensureLoaded()
        return cache[key]?.long?.toInt()
    }
    fun getLong(key: String): Long? {
        ensureLoaded()
        return cache[key]?.long
    }
    fun getString(key: String): String? {
        ensureLoaded()
        return cache[key]?.string
    }
    fun getStringSet(key: String): Set<String>? {
        ensureLoaded()
        return cache[key]?.stringSet
    }
    fun reset() {
        cache = emptyMap()
        loaded = true
        scheduleWrite {
            kvPairDao.reset()
        }
    }

    fun refresh() {
        executeWrite(::refreshLocked)
    }

    fun flush() {
        executeWrite { }
    }

    override fun getBoolean(key: String, defValue: Boolean) = getBoolean(key) ?: defValue
    override fun getFloat(key: String, defValue: Float) = getFloat(key) ?: defValue
    override fun getInt(key: String, defValue: Int) = getInt(key) ?: defValue
    override fun getLong(key: String, defValue: Long) = getLong(key) ?: defValue
    override fun getString(key: String, defValue: String?) = getString(key) ?: defValue
    override fun getStringSet(key: String, defValue: MutableSet<String>?) =
        getStringSet(key) ?: defValue

    fun putBoolean(key: String, value: Boolean?) =
        if (value == null) remove(key) else putBoolean(key, value)

    fun putFloat(key: String, value: Float?) =
        if (value == null) remove(key) else putFloat(key, value)

    fun putInt(key: String, value: Int?) =
        if (value == null) remove(key) else putLong(key, value.toLong())

    fun putLong(key: String, value: Long?) = if (value == null) remove(key) else putLong(key, value)
    override fun putBoolean(key: String, value: Boolean) {
        ensureLoaded()
        val pair = KeyValuePair(key).put(value)
        cache = cache + (key to pair)
        scheduleWrite {
            kvPairDao.put(pair)
        }
        fireChangeListener(key)
    }

    override fun putFloat(key: String, value: Float) {
        ensureLoaded()
        val pair = KeyValuePair(key).put(value)
        cache = cache + (key to pair)
        scheduleWrite {
            kvPairDao.put(pair)
        }
        fireChangeListener(key)
    }

    override fun putInt(key: String, value: Int) {
        ensureLoaded()
        val pair = KeyValuePair(key).put(value.toLong())
        cache = cache + (key to pair)
        scheduleWrite {
            kvPairDao.put(pair)
        }
        fireChangeListener(key)
    }

    override fun putLong(key: String, value: Long) {
        ensureLoaded()
        val pair = KeyValuePair(key).put(value)
        cache = cache + (key to pair)
        scheduleWrite {
            kvPairDao.put(pair)
        }
        fireChangeListener(key)
    }

    override fun putString(key: String, value: String?) = if (value == null) remove(key) else {
        ensureLoaded()
        val pair = KeyValuePair(key).put(value)
        cache = cache + (key to pair)
        scheduleWrite {
            kvPairDao.put(pair)
        }
        fireChangeListener(key)
    }

    override fun putStringSet(key: String, values: MutableSet<String>?) =
        if (values == null) remove(key) else {
            ensureLoaded()
            val pair = KeyValuePair(key).put(values)
            cache = cache + (key to pair)
            scheduleWrite {
                kvPairDao.put(pair)
            }
            fireChangeListener(key)
        }

    fun remove(key: String) {
        ensureLoaded()
        cache = cache - key
        scheduleWrite {
            kvPairDao.delete(key)
        }
        fireChangeListener(key)
    }

    private fun refreshLocked() {
        cache = kvPairDao.all().associateBy { it.key }
        loaded = true
    }

    private fun ensureLoaded() {
        if (loaded) return
        executeWrite {
            if (!loaded) {
                refreshLocked()
            }
        }
    }

    private fun executeWrite(block: () -> Unit) {
        if (Thread.currentThread().name == workerThreadName) {
            block()
            return
        }
        writeExecutor.submit(block).get(30, TimeUnit.SECONDS)
    }

    private fun scheduleWrite(block: () -> Unit) {
        writeExecutor.execute(block)
    }

    private val listeners = HashSet<OnPreferenceDataStoreChangeListener>()
    private fun fireChangeListener(key: String) {
        val listeners = synchronized(listeners) {
            listeners.toList()
        }
        listeners.forEach { it.onPreferenceDataStoreChanged(this, key) }
    }

    fun registerChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun unregisterChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }
}
