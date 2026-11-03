package com.yokhan.gates.windows.service.wfp

import com.sun.jna.Native
import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WfpNativeStructuresAbiTest {
    @Test
    fun x64SdkStructureSizesAndUnionOffsetsMatchWindowsSdk() {
        assertEquals(8, Native.POINTER_SIZE, "These ABI assertions target the Windows x64 service runtime")

        val value = WfpValue()
        assertEquals(16, value.size())
        assertEquals(0, value.offsetOf("type"))
        assertEquals(8, value.offsetOf("value"))

        val conditionValue = WfpConditionValue()
        assertEquals(16, conditionValue.size())
        assertEquals(0, conditionValue.offsetOf("type"))
        assertEquals(8, conditionValue.offsetOf("value"))

        val blob = WfpByteBlob()
        assertEquals(16, blob.size())
        assertEquals(0, blob.offsetOf("size"))
        assertEquals(8, blob.offsetOf("data"))

        val action = WfpNativeAction()
        assertEquals(20, action.size())
        assertEquals(0, action.offsetOf("type"))
        assertEquals(4, action.offsetOf("value"))

        val condition = WfpFilterCondition()
        assertEquals(40, condition.size())
        assertEquals(0, condition.offsetOf("fieldKey"))
        assertEquals(16, condition.offsetOf("matchType"))
        assertEquals(24, condition.offsetOf("conditionValue"))

        val filter = WfpFilter()
        assertEquals(200, filter.size())
        assertEquals(0, filter.offsetOf("filterKey"))
        assertEquals(16, filter.offsetOf("displayData"))
        assertEquals(32, filter.offsetOf("flags"))
        assertEquals(40, filter.offsetOf("providerKey"))
        assertEquals(48, filter.offsetOf("providerData"))
        assertEquals(64, filter.offsetOf("layerKey"))
        assertEquals(80, filter.offsetOf("subLayerKey"))
        assertEquals(96, filter.offsetOf("weight"))
        assertEquals(112, filter.offsetOf("numFilterConditions"))
        assertEquals(120, filter.offsetOf("filterCondition"))
        assertEquals(128, filter.offsetOf("action"))
        assertEquals(152, filter.offsetOf("context"))
        assertEquals(168, filter.offsetOf("reserved"))
        assertEquals(176, filter.offsetOf("filterId"))
        assertEquals(184, filter.offsetOf("effectiveWeight"))
    }

    @Test
    fun guidUsesWindowsInMemoryByteOrderAndPointerFieldsAreDirectGuidPointers() {
        val guid = WfpGuid(UUID.fromString("3b15f201-f8e2-4ea1-90d1-43ef03910721"))
        assertEquals(
            listOf(0x01, 0xf2, 0x15, 0x3b, 0xe2, 0xf8, 0xa1, 0x4e, 0x90, 0xd1, 0x43, 0xef, 0x03, 0x91, 0x07, 0x21),
            guid.pointer.getByteArray(0, 16).map { it.toInt() and 0xff },
        )
        assertEquals("3b15f201-f8e2-4ea1-90d1-43ef03910721", guid.toUuid().toString())

        val providerGuid = WfpGuid(UUID.fromString("3b15f201-f8e2-4ea1-90d1-43ef03910721"))
        val sublayer = WfpSublayer().apply { providerKey = providerGuid.pointer; write() }
        assertEquals(Pointer.nativeValue(providerGuid.pointer), Pointer.nativeValue(sublayer.providerKey))
    }

    @Test
    fun nativeArenaRetainsNestedPayloadAndUtf16BuffersForTheNativeCallLifetime() {
        val arena = WfpNativeArena()
        val payload = arena.memory(4).apply { write(0, byteArrayOf(1, 2, 3, 4), 0, 4) }
        val blob = arena.keep(WfpByteBlob().apply { size = 4; data = payload; write() })
        val displayName = arena.wide("GATES rule")
        System.gc()

        assertTrue(arena.references.size >= 3)
        assertSame(payload, arena.references.first())
        assertEquals(4, blob.size)
        assertEquals(4, blob.data?.getByteArray(0, 4)?.size)
        assertEquals("GATES rule", displayName.getWideString(0))
    }

    @Test
    fun nativeArenaKeepsMarshallingObjectsAliveAcrossGcDuringNativeCall() {
        val arena = WfpNativeArena()
        var payload: Memory? = arena.memory(8)
        var blob: WfpByteBlob? = arena.keep(WfpByteBlob().apply { size = 8; data = payload; write() })
        val payloadReference = WeakReference(requireNotNull(payload))
        val blobReference = WeakReference(requireNotNull(blob))
        payload = null
        blob = null

        withWfpNativeArena(arena) {
            repeat(8) {
                System.gc()
                assertNotNull(payloadReference.get())
                assertNotNull(blobReference.get())
            }
        }
    }

    @Test
    fun enumTemplatesSerializeAllActionsIncludeDisabledAndEachAleLayer() {
        val providerKey = UUID.fromString("3b15f201-f8e2-4ea1-90d1-43ef03910721")
        val fakeNativeRequests = mutableListOf<ByteArray>()
        val providerAddresses = mutableListOf<Long>()
        val providerBytes = mutableListOf<List<Byte>>()
        val enumeratedLayers = WfpFilterEnumTemplates.enumerateByLayer(providerKey) { template ->
            System.gc()
            fakeNativeRequests += template.pointer.getByteArray(0, template.size())
            val providerPointer = requireNotNull(template.providerKey)
            providerAddresses += Pointer.nativeValue(providerPointer)
            providerBytes += providerPointer.getByteArray(0, 16).toList()
            listOf(template.layerKey.toUuid())
        }

        assertEquals(4, fakeNativeRequests.size)
        assertEquals(WfpAleLayer.entries.map { UUID.fromString(it.guid) }, enumeratedLayers)
        WfpAleLayer.entries.forEachIndexed { index, layer ->
            val template = WfpFilterEnumTemplate()
            val bytes = fakeNativeRequests[index]
            val serialized = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(providerAddresses[index], serialized.getLong(template.offsetOf("providerKey")))
            assertEquals(WfpGuid(providerKey).pointer.getByteArray(0, 16).toList(), providerBytes[index])
            assertEquals(
                WfpGuid(UUID.fromString(layer.guid)).pointer.getByteArray(0, 16).toList(),
                bytes.sliceArray(template.offsetOf("layerKey") until template.offsetOf("layerKey") + 16).toList(),
            )
            assertEquals(WfpFilterEnumTemplates.INCLUDE_DISABLED, serialized.getInt(template.offsetOf("flags")))
            assertEquals(WfpFilterEnumTemplates.ALL_ACTIONS, serialized.getInt(template.offsetOf("actionMask")))
        }
    }
}
