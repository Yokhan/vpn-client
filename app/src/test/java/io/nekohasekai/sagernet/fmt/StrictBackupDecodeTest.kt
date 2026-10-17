package io.nekohasekai.sagernet.fmt

import com.esotericsoftware.kryo.KryoException
import io.nekohasekai.sagernet.database.ProxyEntity
import moe.matsuri.nb4a.proxy.config.ConfigBean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StrictBackupDecodeTest {
    private fun largeConfig() = ConfigBean().apply {
        initializeDefaultValues()
        name = "Large local fixture"
        config = "x".repeat(16 * 1024)
    }

    @Test
    fun readsLargeConfigAndNestedProfile() {
        val source = largeConfig()
        val restored = KryoConverters.deserializeStrict(ConfigBean(), KryoConverters.serialize(source))
        assertEquals(source.config, restored.config)
        val profile = ProxyEntity(id = 9, groupId = 7).apply { putBean(source) }
        val restoredProfile = KryoConverters.deserializeStrict(ProxyEntity(), KryoConverters.serialize(profile))
        assertEquals(source.config, restoredProfile.configBean!!.config)
    }

    @Test
    fun rejectsTruncatedAndTrailingBytesIncludingNestedBeans() {
        val encoded = KryoConverters.serialize(largeConfig())
        assertFailsWith<KryoException> {
            KryoConverters.deserializeStrict(ConfigBean(), encoded.copyOf(encoded.size - 1))
        }
        assertFailsWith<KryoException> {
            KryoConverters.deserializeStrict(ConfigBean(), encoded + byteArrayOf(1))
        }
    }

    @Test
    fun strictModeDoesNotLeakIntoNormalDatabaseReads() {
        val encoded = KryoConverters.serialize(largeConfig()) + byteArrayOf(1)
        assertFailsWith<KryoException> { KryoConverters.deserializeStrict(ConfigBean(), encoded) }
        assertEquals(16 * 1024, KryoConverters.deserialize(ConfigBean(), encoded).config.length)
    }
}
