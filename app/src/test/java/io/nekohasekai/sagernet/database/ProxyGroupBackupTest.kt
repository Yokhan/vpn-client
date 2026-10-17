package io.nekohasekai.sagernet.database

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProxyGroupBackupTest {
    @Test
    fun preservesSelectorAndBothHops() {
        val source = ProxyGroup(id = 7, name = "Local", isSelector = true, frontProxy = 11, landingProxy = 12)
        val output = ByteBufferOutput(512)
        source.serializeToBuffer(output)
        val restored = ProxyGroup()
        restored.deserializeFromBuffer(ByteBufferInput(output.toBytes()))
        assertEquals(source, restored)
    }

    @Test
    fun readsLegacyGroupsWithExplicitDefaults() {
        val output = ByteBufferOutput(512)
        output.writeInt(0)
        output.writeLong(7)
        output.writeLong(2)
        output.writeBoolean(false)
        output.writeString("Local")
        output.writeInt(0)
        output.writeInt(0)
        val restored = ProxyGroup(isSelector = true, frontProxy = 11, landingProxy = 12)
        restored.deserializeFromBuffer(ByteBufferInput(output.toBytes()))
        assertEquals(7L, restored.id)
        assertEquals(false, restored.isSelector)
        assertEquals(-1L, restored.frontProxy)
        assertEquals(-1L, restored.landingProxy)
    }

    @Test
    fun rejectsUnknownGroupVersions() {
        val output = ByteBufferOutput(32)
        output.writeInt(2)
        assertFailsWith<IllegalArgumentException> {
            ProxyGroup().deserializeFromBuffer(ByteBufferInput(output.toBytes()))
        }
    }
}
