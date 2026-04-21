package io.nekohasekai.sagernet.database.preference

import androidx.preference.PreferenceDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

@Suppress("MemberVisibilityCanBePrivate", "unused")
open class RoomPreferenceDataStore(private val kvPairDao: KeyValuePair.Dao) :
    PreferenceDataStore() {

    private val writeScope = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "RoomPreferenceDataStore").apply {
                isDaemon = true
            }
        }.asCoroutineDispatcher()
    )
    private val cache = ConcurrentHashMap<String, KeyValuePair>().apply {
        runBlocking(Dispatchers.IO) {
            kvPairDao.all().forEach { put(it.key, it) }
        }
    }

    fun all() = cache.values.toList()
    fun getBoolean(key: String) = cache[key]?.boolean
    fun getFloat(key: String) = cache[key]?.float
    fun getInt(key: String) = cache[key]?.long?.toInt()
    fun getLong(key: String) = cache[key]?.long
    fun getString(key: String) = cache[key]?.string
    fun getStringSet(key: String) = cache[key]?.stringSet
    fun reset() {
        cache.clear()
        writeScope.launch {
            kvPairDao.reset()
        }
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
        cache[key] = KeyValuePair(key).put(value)
        writeScope.launch {
            kvPairDao.put(KeyValuePair(key).put(value))
        }
        fireChangeListener(key)
    }

    override fun putFloat(key: String, value: Float) {
        cache[key] = KeyValuePair(key).put(value)
        writeScope.launch {
            kvPairDao.put(KeyValuePair(key).put(value))
        }
        fireChangeListener(key)
    }

    override fun putInt(key: String, value: Int) {
        cache[key] = KeyValuePair(key).put(value.toLong())
        writeScope.launch {
            kvPairDao.put(KeyValuePair(key).put(value.toLong()))
        }
        fireChangeListener(key)
    }

    override fun putLong(key: String, value: Long) {
        cache[key] = KeyValuePair(key).put(value)
        writeScope.launch {
            kvPairDao.put(KeyValuePair(key).put(value))
        }
        fireChangeListener(key)
    }

    override fun putString(key: String, value: String?) = if (value == null) remove(key) else {
        cache[key] = KeyValuePair(key).put(value)
        writeScope.launch {
            kvPairDao.put(KeyValuePair(key).put(value))
        }
        fireChangeListener(key)
    }

    override fun putStringSet(key: String, values: MutableSet<String>?) =
        if (values == null) remove(key) else {
            cache[key] = KeyValuePair(key).put(values)
            writeScope.launch {
                kvPairDao.put(KeyValuePair(key).put(values))
            }
            fireChangeListener(key)
        }

    fun remove(key: String) {
        cache.remove(key)
        writeScope.launch {
            kvPairDao.delete(key)
        }
        fireChangeListener(key)
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
