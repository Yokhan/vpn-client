package com.yokhan.gates.windows.service.wfp

import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.Union
import java.lang.ref.Reference
import java.util.UUID

internal inline fun <T> withWfpNativeArena(arena: WfpNativeArena, nativeCall: () -> T): T = try {
    nativeCall()
} finally {
    Reference.reachabilityFence(arena)
}

internal open class WfpStructure : Structure {
    constructor() : super()
    constructor(pointer: Pointer) : super(pointer)

    fun offsetOf(fieldName: String): Int = fieldOffset(fieldName)
}

internal open class WfpGuid : WfpStructure {
    @JvmField var data1: Int = 0
    @JvmField var data2: Short = 0
    @JvmField var data3: Short = 0
    @JvmField var data4: ByteArray = ByteArray(8)

    constructor() : super()

    constructor(value: java.util.UUID) : this() {
        data1 = value.mostSignificantBits.ushr(32).toInt()
        data2 = (value.mostSignificantBits ushr 16).toShort()
        data3 = value.mostSignificantBits.toShort()
        val tail = value.leastSignificantBits
        data4 = ByteArray(8) { index -> (tail ushr (56 - index * 8)).toByte() }
        write()
    }

    constructor(pointer: Pointer) : this() {
        useMemory(pointer)
        read()
    }

    override fun getFieldOrder() = listOf("data1", "data2", "data3", "data4")

    fun toUuid(): java.util.UUID {
        read()
        val most = (data1.toLong() shl 32) or
            ((data2.toInt() and 0xffff).toLong() shl 16) or
            (data3.toInt() and 0xffff).toLong()
        var least = 0L
        data4.forEach { least = (least shl 8) or (it.toLong() and 0xff) }
        return java.util.UUID(most, least)
    }
}

internal class WfpDisplayData : WfpStructure {
    @JvmField var name: Pointer? = null
    @JvmField var description: Pointer? = null
    constructor() : super()
    override fun getFieldOrder() = listOf("name", "description")
}

internal class WfpByteBlob : WfpStructure {
    @JvmField var size: Int = 0
    @JvmField var data: Pointer? = null
    constructor() : super()
    constructor(pointer: Pointer) : super(pointer)
    override fun getFieldOrder() = listOf("size", "data")
}

internal class WfpValueUnion : Union() {
    @JvmField var uint8: Byte = 0
    @JvmField var uint16: Short = 0
    @JvmField var uint32: Int = 0
    @JvmField var uint64: Pointer? = null
    override fun getFieldOrder() = listOf("uint8", "uint16", "uint32", "uint64")
}

internal class WfpValue : WfpStructure {
    @JvmField var type: Int = 0
    @JvmField var value = WfpValueUnion()
    constructor() : super()
    override fun getFieldOrder() = listOf("type", "value")
}

internal class WfpConditionValueUnion : Union() {
    @JvmField var uint8: Byte = 0
    @JvmField var uint16: Short = 0
    @JvmField var uint32: Int = 0
    @JvmField var uint64: Pointer? = null
    override fun getFieldOrder() = listOf("uint8", "uint16", "uint32", "uint64")
}

internal class WfpConditionValue : WfpStructure {
    @JvmField var type: Int = 0
    @JvmField var value = WfpConditionValueUnion()
    constructor() : super()
    override fun getFieldOrder() = listOf("type", "value")
}

internal class WfpFilterCondition : WfpStructure {
    @JvmField var fieldKey = WfpGuid()
    @JvmField var matchType: Int = 0
    @JvmField var conditionValue = WfpConditionValue()
    constructor() : super()
    override fun getFieldOrder() = listOf("fieldKey", "matchType", "conditionValue")
}

internal class WfpActionUnion : Union() {
    @JvmField var filterType = WfpGuid()
    @JvmField var calloutKey = WfpGuid()
    override fun getFieldOrder() = listOf("filterType", "calloutKey")
}

internal class WfpNativeAction : WfpStructure {
    @JvmField var type: Int = 0
    @JvmField var value = WfpActionUnion()
    constructor() : super()
    override fun getFieldOrder() = listOf("type", "value")
}

internal class WfpFilterContextUnion : Union() {
    @JvmField var rawContext: Long = 0
    @JvmField var providerContextKey = WfpGuid()
    override fun getFieldOrder() = listOf("rawContext", "providerContextKey")
}

internal class WfpFilter : WfpStructure {
    @JvmField var filterKey = WfpGuid()
    @JvmField var displayData = WfpDisplayData()
    @JvmField var flags: Int = 0
    @JvmField var providerKey: Pointer? = null
    @JvmField var providerData = WfpByteBlob()
    @JvmField var layerKey = WfpGuid()
    @JvmField var subLayerKey = WfpGuid()
    @JvmField var weight = WfpValue()
    @JvmField var numFilterConditions: Int = 0
    @JvmField var filterCondition: Pointer? = null
    @JvmField var action = WfpNativeAction()
    @JvmField var context = WfpFilterContextUnion()
    @JvmField var reserved: Pointer? = null
    @JvmField var filterId: Long = 0
    @JvmField var effectiveWeight = WfpValue()

    constructor() : super()
    constructor(pointer: Pointer) : super(pointer)
    override fun getFieldOrder() = listOf(
        "filterKey", "displayData", "flags", "providerKey", "providerData", "layerKey", "subLayerKey",
        "weight", "numFilterConditions", "filterCondition", "action", "context", "reserved", "filterId",
        "effectiveWeight",
    )
}

internal class WfpSession : WfpStructure {
    @JvmField var sessionKey = WfpGuid()
    @JvmField var displayData = WfpDisplayData()
    @JvmField var flags: Int = 0
    @JvmField var txnWaitTimeoutInMSec: Int = 0
    @JvmField var processId: Int = 0
    @JvmField var sid: Pointer? = null
    @JvmField var username: Pointer? = null
    @JvmField var kernelMode: Int = 0
    constructor() : super()
    override fun getFieldOrder() = listOf(
        "sessionKey", "displayData", "flags", "txnWaitTimeoutInMSec", "processId", "sid", "username", "kernelMode",
    )
}

internal class WfpProvider : WfpStructure {
    @JvmField var providerKey = WfpGuid()
    @JvmField var displayData = WfpDisplayData()
    @JvmField var flags: Int = 0
    @JvmField var providerData = WfpByteBlob()
    @JvmField var serviceName: Pointer? = null
    constructor() : super()
    constructor(pointer: Pointer) : super(pointer)
    override fun getFieldOrder() = listOf("providerKey", "displayData", "flags", "providerData", "serviceName")
}

internal class WfpSublayer : WfpStructure {
    @JvmField var subLayerKey = WfpGuid()
    @JvmField var displayData = WfpDisplayData()
    @JvmField var flags: Int = 0
    @JvmField var providerKey: Pointer? = null
    @JvmField var providerData = WfpByteBlob()
    @JvmField var weight: Short = 0
    constructor() : super()
    constructor(pointer: Pointer) : super(pointer)
    override fun getFieldOrder() = listOf("subLayerKey", "displayData", "flags", "providerKey", "providerData", "weight")
}

internal class WfpFilterEnumTemplate : WfpStructure {
    @JvmField var providerKey: Pointer? = null
    @JvmField var layerKey = WfpGuid()
    @JvmField var enumType: Int = 0
    @JvmField var flags: Int = 0
    @JvmField var providerContextTemplate: Pointer? = null
    @JvmField var numFilterConditions: Int = 0
    @JvmField var filterCondition: Pointer? = null
    @JvmField var actionMask: Int = 0
    @JvmField var calloutKey: Pointer? = null
    constructor() : super()
    override fun getFieldOrder() = listOf(
        "providerKey", "layerKey", "enumType", "flags", "providerContextTemplate", "numFilterConditions",
        "filterCondition", "actionMask", "calloutKey",
    )
}

internal object WfpFilterEnumTemplates {
    const val INCLUDE_DISABLED = 0x10
    const val ALL_ACTIONS = -1

    fun create(providerGuid: WfpGuid, layer: WfpAleLayer): WfpFilterEnumTemplate = WfpFilterEnumTemplate().apply {
        providerKey = providerGuid.pointer
        layerKey = WfpGuid(UUID.fromString(layer.guid))
        flags = INCLUDE_DISABLED
        actionMask = ALL_ACTIONS
        write()
    }

    fun <T> enumerateByLayer(providerKey: UUID, enumerate: (WfpFilterEnumTemplate) -> List<T>): List<T> {
        val arena = WfpNativeArena()
        val providerGuid = arena.keep(WfpGuid(providerKey))
        return withWfpNativeArena(arena) {
            WfpAleLayer.entries.flatMap { layer -> enumerate(create(providerGuid, layer)) }
        }
    }
}
