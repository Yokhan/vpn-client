package com.yokhan.gates.windows.service.wfp

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.util.UUID

internal class WfpNativeArena {
    val references = mutableListOf<Any>()
    fun <T : Any> keep(value: T): T = value.also { references.add(it) }
    fun memory(size: Long): Memory = keep(Memory(size))
    fun wide(value: String): Pointer = memory(value.length * 2L + 2).apply { setWideString(0, value) }
}

interface WfpGuardBackend : AutoCloseable {
    fun armBlocked(policy: WfpPolicy): WfpInspection
    fun permitTunnel(verifiedTunLuid: Long)
    fun block()
    fun disarm(): WfpInspection
    fun inspect(): WfpInspection
    fun closePermitsOnly()
}

/** Explicit service-entry factory. It performs no native loading or WFP operation until a method is called. */
object WindowsFilteringPlatformBackend {
    fun create(): WfpGuardBackend = NativeWfpGuardBackend()
}

internal interface WfpApi {
    fun replacePersistentRules(rules: List<WfpRulePlan>)
    fun replaceDynamicRules(rules: List<WfpRulePlan>)
    fun removeDynamicRules()
    fun removeOwnedPersistentRules()
    fun inspectOwnedRules(): WfpInspection
    fun close()
}

internal class NativeWfpGuardBackend(
    private val apiFactory: () -> WfpApi = { NativeWfpApi() },
) : WfpGuardBackend {
    private var api: WfpApi? = null
    private var policy: WfpPolicy? = null

    private fun api(): WfpApi = api ?: apiFactory().also { api = it }

    @Synchronized
    override fun armBlocked(policy: WfpPolicy): WfpInspection {
        val plan = WfpPolicyPlanner.plan(policy, includeTunnelPermit = false)
        val persistent = plan.filter(WfpRulePlan::persistent)
        val baselineDynamic = plan.filterNot(WfpRulePlan::persistent)
        val backend = api()
        backend.removeDynamicRules()
        this.policy = null
        backend.replacePersistentRules(persistent)
        this.policy = policy.copy(verifiedTunLuid = null)
        backend.replaceDynamicRules(baselineDynamic)
        return backend.inspectOwnedRules()
    }

    @Synchronized
    override fun permitTunnel(verifiedTunLuid: Long) {
        require(verifiedTunLuid > 0) { "TUN interface LUID must be positive" }
        val current = checkNotNull(policy) { "armBlocked must be called before permitting tunnel traffic" }
        val plan = WfpPolicyPlanner.plan(current.copy(verifiedTunLuid = verifiedTunLuid))
        policy = current.copy(verifiedTunLuid = null)
        api().replaceDynamicRules(plan.filterNot(WfpRulePlan::persistent))
        policy = current.copy(verifiedTunLuid = verifiedTunLuid)
    }

    @Synchronized
    override fun block() {
        val current = checkNotNull(policy) { "armBlocked must be called before blocking" }
        val plan = WfpPolicyPlanner.plan(current.copy(verifiedTunLuid = null), includeTunnelPermit = false)
        policy = current.copy(verifiedTunLuid = null)
        api().replaceDynamicRules(plan.filterNot(WfpRulePlan::persistent))
    }

    @Synchronized
    override fun disarm(): WfpInspection {
        val backend = api()
        backend.removeDynamicRules()
        backend.removeOwnedPersistentRules()
        val inspection = backend.inspectOwnedRules()
        check(inspection.rules.isEmpty()) { "Disarm verification found remaining provider-owned WFP rules" }
        policy = null
        return inspection
    }

    @Synchronized
    override fun inspect(): WfpInspection = api().inspectOwnedRules()

    @Synchronized
    override fun closePermitsOnly() {
        api?.removeDynamicRules()
        policy = null
    }

    @Synchronized
    override fun close() {
        closePermitsOnly()
        api?.close()
        api = null
    }
}

private class NativeWfpApi : WfpApi {
    private val providerKey = UUID.fromString("3b15f201-f8e2-4ea1-90d1-43ef03910721")
    private val sublayerKey = UUID.fromString("3b15f202-f8e2-4ea1-90d1-43ef03910721")
    private val wfp by lazy { Native.load("Fwpuclnt", WfpFunctions::class.java) }
    private val advapi by lazy { Native.load("Advapi32", SecurityFunctions::class.java) }
    private val kernel32 by lazy { Native.load("Kernel32", KernelFunctions::class.java) }
    private var dynamicEngine: Pointer? = null

    override fun replacePersistentRules(rules: List<WfpRulePlan>) {
        withEngine(dynamic = false) { engine ->
            ensurePersistentObjects(engine)
            transaction(engine) {
                val installed = enumerateOwnedKeys(engine)
                val desired = rules.map(WfpRulePlan::key).toSet()
                (installed - desired).forEach { key -> checkStatus(wfp.FwpmFilterDeleteByKey0(engine, WfpGuid(key))) }
                val missing = desired - installed
                val byKey = rules.associateBy(WfpRulePlan::key)
                missing.map(byKey::getValue).forEach { addFilter(engine, it, persistent = true) }
            }
        }
    }

    override fun replaceDynamicRules(rules: List<WfpRulePlan>) {
        removeDynamicRules()
        if (rules.isEmpty()) return
        val session = openEngine(dynamic = true)
        dynamicEngine = session
        try {
            transaction(session) {
                rules.forEach { addFilter(session, it, persistent = false) }
            }
        } catch (failure: Throwable) {
            closeEngine(session)
            dynamicEngine = null
            throw failure
        }
    }

    override fun removeDynamicRules() {
        dynamicEngine?.let(::closeEngine)
        dynamicEngine = null
    }

    override fun removeOwnedPersistentRules() {
        withEngine(dynamic = false) { engine ->
            transaction(engine) { enumerateOwnedKeys(engine).forEach { key ->
                checkStatus(wfp.FwpmFilterDeleteByKey0(engine, WfpGuid(key)))
            } }
        }
    }

    override fun inspectOwnedRules(): WfpInspection = withEngineResult(dynamic = false) { engine ->
        enumerateOwnedFilters(engine)
    }.let(::WfpInspection)

    override fun close() = removeDynamicRules()

    private fun ensurePersistentObjects(engine: Pointer) {
        val arena = WfpNativeArena()
        val providerGuid = arena.keep(WfpGuid(providerKey))
        transaction(engine) {
            val provider = WfpProvider().apply {
                providerKey = WfpGuid(this@NativeWfpApi.providerKey)
                displayData = display("GATES WFP guard", "GATES-owned Windows Filtering Platform objects", arena)
                flags = PROVIDER_FLAG_PERSISTENT
                serviceName = arena.wide("GatesVpn")
                write()
            }
            val providerResult = withPersistentSecurityDescriptor { sd -> wfp.FwpmProviderAdd0(engine, provider, sd) }
            if (providerResult == FWP_E_ALREADY_EXISTS) verifyProvider(engine) else checkStatus(providerResult)

            val sublayer = WfpSublayer().apply {
                subLayerKey = WfpGuid(this@NativeWfpApi.sublayerKey)
                displayData = display("GATES WFP guard", "GATES-owned policy sublayer", arena)
                flags = SUBLAYER_FLAG_PERSISTENT
                providerKey = providerGuid.pointer
                weight = SUBLAYER_WEIGHT.toShort()
                write()
            }
            val sublayerResult = withPersistentSecurityDescriptor { sd -> wfp.FwpmSubLayerAdd0(engine, sublayer, sd) }
            if (sublayerResult == FWP_E_ALREADY_EXISTS) verifySublayer(engine) else checkStatus(sublayerResult)
        }
        withPersistentDacl { dacl ->
            checkStatus(wfp.FwpmProviderSetSecurityInfoByKey0(engine, WfpGuid(providerKey), PROTECTED_DACL_SECURITY_INFORMATION, null, null, dacl, null))
        }
        withPersistentDacl { dacl ->
            checkStatus(wfp.FwpmSubLayerSetSecurityInfoByKey0(engine, WfpGuid(sublayerKey), PROTECTED_DACL_SECURITY_INFORMATION, null, null, dacl, null))
        }
    }

    private fun verifyProvider(engine: Pointer) {
        val output = PointerByReference()
        checkStatus(wfp.FwpmProviderGetByKey0(engine, WfpGuid(providerKey), output))
        val pointer = requireNotNull(output.value)
        try {
            val found = WfpProvider(pointer).apply { read() }
            check(found.providerKey.toUuid() == providerKey && found.flags and PROVIDER_FLAG_PERSISTENT != 0 &&
                found.serviceName?.getWideString(0) == "GatesVpn") {
                "Existing WFP provider key is not the expected GATES service provider"
            }
        } finally {
            wfp.FwpmFreeMemory0(PointerByReference(pointer))
        }
    }

    private fun verifySublayer(engine: Pointer) {
        val output = PointerByReference()
        checkStatus(wfp.FwpmSubLayerGetByKey0(engine, WfpGuid(sublayerKey), output))
        val pointer = requireNotNull(output.value)
        try {
            val found = WfpSublayer(pointer).apply { read() }
            val foundProvider = found.providerKey?.let(::WfpGuid)?.toUuid()
            check(found.subLayerKey.toUuid() == sublayerKey && foundProvider == providerKey &&
                found.flags and SUBLAYER_FLAG_PERSISTENT != 0 && (found.weight.toInt() and 0xffff) == SUBLAYER_WEIGHT) {
                "Existing WFP sublayer key is not the expected GATES-owned sublayer"
            }
        } finally {
            wfp.FwpmFreeMemory0(PointerByReference(pointer))
        }
    }

    private fun addFilter(engine: Pointer, plan: WfpRulePlan, persistent: Boolean) {
        val arena = WfpNativeArena()
        val conditions = plan.conditions.map { nativeCondition(it, arena) }
        val conditionMemory = if (conditions.isEmpty()) null else Memory((conditions.size.toLong() * conditions.first().size().toLong())).also { memory ->
            conditions.forEachIndexed { index, condition ->
                condition.write()
                memory.write(index * condition.size().toLong(), condition.pointer.getByteArray(0, condition.size()), 0, condition.size())
            }
        }
        conditionMemory?.let(arena::keep)
        val providerGuid = arena.keep(WfpGuid(providerKey))
        val filter = WfpFilter().apply {
            filterKey = WfpGuid(plan.key)
            displayData = display("GATES ${plan.purpose}", "GATES WFP policy rule", arena)
            flags = if (plan.persistent) FILTER_FLAG_PERSISTENT else 0
            providerKey = providerGuid.pointer
            layerKey = WfpGuid(UUID.fromString(plan.layer.guid))
            subLayerKey = WfpGuid(sublayerKey)
            weight = uint64Value(plan.weight, arena)
            numFilterConditions = conditions.size
            filterCondition = conditionMemory
            action = WfpNativeAction().apply { type = if (plan.action == WfpAction.PERMIT) ACTION_PERMIT else ACTION_BLOCK }
            write()
        }
        withWfpNativeArena(arena) {
            val status = if (!persistent) {
                wfp.FwpmFilterAdd0(engine, filter, null, LongByReference())
            } else {
                withPersistentSecurityDescriptor { sd -> wfp.FwpmFilterAdd0(engine, filter, sd, LongByReference()) }
            }
            checkStatus(status)
        }
    }

    private fun nativeCondition(condition: WfpCondition, arena: WfpNativeArena): WfpFilterCondition {
        val result = arena.keep(WfpFilterCondition())
        when (condition) {
            is WfpCondition.ApplicationId -> {
                result.fieldKey = WfpGuid(FIELD_ALE_APP_ID)
                result.conditionValue = byteBlobValue(appId(condition.absoluteExePath), arena)
            }
            is WfpCondition.LocalInterfaceLuid -> {
                result.fieldKey = WfpGuid(FIELD_IP_LOCAL_INTERFACE)
                val valueMemory = arena.memory(8).apply { setLong(0, condition.luid) }
                result.conditionValue = pointerValue(FWP_UINT64, valueMemory)
            }
            WfpCondition.Loopback -> {
                result.fieldKey = WfpGuid(FIELD_FLAGS)
                result.conditionValue = uint32Condition(FWP_UINT32, LOOPBACK_FLAG)
            }
            is WfpCondition.LocalPort -> {
                result.fieldKey = WfpGuid(FIELD_IP_LOCAL_PORT)
                result.conditionValue = uint32Condition(FWP_UINT16, condition.port)
            }
            is WfpCondition.IpProtocol -> {
                result.fieldKey = WfpGuid(FIELD_IP_PROTOCOL)
                result.conditionValue = uint32Condition(FWP_UINT8, condition.protocol)
            }
        }
        result.matchType = if (condition == WfpCondition.Loopback) MATCH_FLAGS_ANY_SET else MATCH_EQUAL
        result.write()
        return result
    }

    private fun appId(path: String): ByteArray {
        val output = PointerByReference()
        checkStatus(wfp.FwpmGetAppIdFromFileName0(Memory((path.length * 2L) + 2).apply { setWideString(0, path) }, output))
        val blobPointer = output.value ?: error("FwpmGetAppIdFromFileName0 returned a null blob")
        try {
            val blob = WfpByteBlob(blobPointer).apply { read() }
            return requireNotNull(blob.data).getByteArray(0, blob.size)
        } finally {
            wfp.FwpmFreeMemory0(PointerByReference(blobPointer))
        }
    }

    private fun byteBlobValue(bytes: ByteArray, arena: WfpNativeArena): WfpConditionValue {
        val blobData = arena.memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
        val blob = arena.keep(WfpByteBlob().apply { size = bytes.size; data = blobData; write() })
        return pointerValue(FWP_BYTE_BLOB_TYPE, blob.pointer)
    }

    private fun uint32Condition(type: Int, value: Int) = WfpConditionValue().apply {
        this.type = type
        when (type) {
            FWP_UINT8 -> { this.value.setType(Byte::class.java); this.value.uint8 = value.toByte() }
            FWP_UINT16 -> { this.value.setType(Short::class.java); this.value.uint16 = value.toShort() }
            FWP_UINT32 -> { this.value.setType(Int::class.java); this.value.uint32 = value }
            else -> error("Unsupported scalar WFP condition type $type")
        }
        write()
    }

    private fun pointerValue(type: Int, pointer: Pointer) = WfpConditionValue().apply {
        this.type = type
        value.setType(Pointer::class.java)
        value.uint64 = pointer
        write()
    }

    private fun uint64Value(value: Long, arena: WfpNativeArena) = WfpValue().apply {
        type = FWP_UINT64
        val memory = arena.memory(8).apply { setLong(0, value) }
        this.value.setType(Pointer::class.java)
        this.value.uint64 = memory
        write()
    }

    private fun enumerateOwnedKeys(engine: Pointer): Set<UUID> = enumerateOwnedFilters(engine).mapTo(mutableSetOf(), InstalledWfpRule::key)

    private fun enumerateOwnedFilters(engine: Pointer): List<InstalledWfpRule> =
        WfpFilterEnumTemplates.enumerateByLayer(providerKey) { template ->
            enumerateOwnedFiltersForTemplate(engine, template)
        }

    private fun enumerateOwnedFiltersForTemplate(
        engine: Pointer,
        template: WfpFilterEnumTemplate,
    ): List<InstalledWfpRule> {
        val enumHandle = PointerByReference()
        checkStatus(wfp.FwpmFilterCreateEnumHandle0(engine, template, enumHandle))
        val result = mutableListOf<InstalledWfpRule>()
        try {
            while (true) {
                val entries = PointerByReference()
                val count = IntByReference()
                checkStatus(wfp.FwpmFilterEnum0(engine, enumHandle.value, 128, entries, count))
                val array = entries.value
                try {
                    repeat(count.value) { index ->
                        val filterPointer = array.getPointer(index.toLong() * Native.POINTER_SIZE)
                        val filter = WfpFilter(filterPointer).apply { read() }
                        val owner = filter.providerKey?.let(::WfpGuid)?.apply { read() }?.toUuid()
                        if (owner == providerKey) {
                            val key = filter.filterKey.toUuid()
                            val layer = WfpAleLayer.entries.firstOrNull { UUID.fromString(it.guid) == filter.layerKey.toUuid() }
                                ?: return@repeat
                            result += InstalledWfpRule(
                                key, if (filter.action.type == ACTION_PERMIT) WfpAction.PERMIT else WfpAction.BLOCK,
                                filter.flags and FILTER_FLAG_PERSISTENT != 0, layer,
                                filter.displayData.name?.getWideString(0)?.removePrefix("GATES ") ?: "unknown",
                            )
                        }
                    }
                } finally {
                    if (array != null) wfp.FwpmFreeMemory0(PointerByReference(array))
                }
                if (count.value < 128) break
            }
        } finally {
            checkStatus(wfp.FwpmFilterDestroyEnumHandle0(engine, enumHandle.value))
        }
        return result
    }

    private fun transaction(engine: Pointer, operation: () -> Unit) {
        checkStatus(wfp.FwpmTransactionBegin0(engine, 0))
        try {
            operation()
            checkStatus(wfp.FwpmTransactionCommit0(engine))
        } catch (failure: Throwable) {
            wfp.FwpmTransactionAbort0(engine)
            throw failure
        }
    }

    private fun withEngine(dynamic: Boolean, operation: (Pointer) -> Unit) {
        val engine = openEngine(dynamic)
        try { operation(engine) } finally { closeEngine(engine) }
    }

    private fun <T> withEngineResult(dynamic: Boolean, operation: (Pointer) -> T): T {
        val engine = openEngine(dynamic)
        try { return operation(engine) } finally { closeEngine(engine) }
    }

    private fun openEngine(dynamic: Boolean): Pointer {
        val arena = WfpNativeArena()
        val session = WfpSession().apply {
            sessionKey = WfpGuid(UUID.randomUUID())
            displayData = display("GATES WFP ${if (dynamic) "runtime" else "persistent"}", "GATES service-owned session", arena)
            flags = if (dynamic) SESSION_FLAG_DYNAMIC else 0
            write()
        }
        val handle = PointerByReference()
        checkStatus(wfp.FwpmEngineOpen0(null, RPC_C_AUTHN_WINNT, null, session, handle))
        return requireNotNull(handle.value)
    }

    private fun closeEngine(engine: Pointer) = checkStatus(wfp.FwpmEngineClose0(engine))

    private fun persistentSecurityDescriptor(): Pointer {
        val output = PointerByReference()
        val converted = advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW(
            WString("D:P(A;;GA;;;SY)(A;;GA;;;BA)"), 1, output, IntByReference(),
        )
        check(converted != 0) { "Could not build restrictive WFP security descriptor" }
        return requireNotNull(output.value)
    }

    private fun <T> withPersistentSecurityDescriptor(operation: (Pointer) -> T): T {
        val descriptor = persistentSecurityDescriptor()
        return try { operation(descriptor) } finally { kernel32.LocalFree(descriptor) }
    }

    private fun <T> withPersistentDacl(operation: (Pointer) -> T): T = withPersistentSecurityDescriptor { descriptor ->
        val present = IntByReference()
        val dacl = PointerByReference()
        val defaulted = IntByReference()
        check(advapi.GetSecurityDescriptorDacl(descriptor, present, dacl, defaulted) != 0 && present.value != 0) {
            "Could not extract the restrictive WFP DACL"
        }
        operation(requireNotNull(dacl.value))
    }

    private fun display(name: String, description: String, arena: WfpNativeArena): WfpDisplayData {
        return WfpDisplayData().apply { this.name = arena.wide(name); this.description = arena.wide(description); write() }
    }

    private fun checkStatus(status: Int) {
        check(status == 0) { "WFP API failed with HRESULT 0x${status.toUInt().toString(16)}" }
    }

    private interface WfpFunctions : StdCallLibrary {
        fun FwpmEngineOpen0(serverName: Pointer?, authnService: Int, authIdentity: Pointer?, session: WfpSession?, engine: PointerByReference): Int
        fun FwpmEngineClose0(engine: Pointer): Int
        fun FwpmTransactionBegin0(engine: Pointer, flags: Int): Int
        fun FwpmTransactionCommit0(engine: Pointer): Int
        fun FwpmTransactionAbort0(engine: Pointer): Int
        fun FwpmProviderAdd0(engine: Pointer, provider: WfpProvider, securityDescriptor: Pointer?): Int
        fun FwpmProviderGetByKey0(engine: Pointer, key: WfpGuid, provider: PointerByReference): Int
        fun FwpmProviderSetSecurityInfoByKey0(engine: Pointer, key: WfpGuid, securityInfo: Int, owner: Pointer?, group: Pointer?, dacl: Pointer?, sacl: Pointer?): Int
        fun FwpmSubLayerAdd0(engine: Pointer, sublayer: WfpSublayer, securityDescriptor: Pointer?): Int
        fun FwpmSubLayerGetByKey0(engine: Pointer, key: WfpGuid, sublayer: PointerByReference): Int
        fun FwpmSubLayerSetSecurityInfoByKey0(engine: Pointer, key: WfpGuid, securityInfo: Int, owner: Pointer?, group: Pointer?, dacl: Pointer?, sacl: Pointer?): Int
        fun FwpmFilterAdd0(engine: Pointer, filter: WfpFilter, securityDescriptor: Pointer?, id: LongByReference): Int
        fun FwpmFilterDeleteByKey0(engine: Pointer, key: WfpGuid): Int
        fun FwpmFilterCreateEnumHandle0(engine: Pointer, template: WfpFilterEnumTemplate, enumHandle: PointerByReference): Int
        fun FwpmFilterEnum0(engine: Pointer, enumHandle: Pointer?, count: Int, entries: PointerByReference, returned: IntByReference): Int
        fun FwpmFilterDestroyEnumHandle0(engine: Pointer, enumHandle: Pointer?): Int
        fun FwpmGetAppIdFromFileName0(fileName: Pointer, appId: PointerByReference): Int
        fun FwpmFreeMemory0(pointer: PointerByReference)
    }

    private interface SecurityFunctions : StdCallLibrary {
        fun ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl: WString, revision: Int, descriptor: PointerByReference, size: IntByReference): Int
        fun GetSecurityDescriptorDacl(descriptor: Pointer, daclPresent: IntByReference, dacl: PointerByReference, daclDefaulted: IntByReference): Int
    }

    private interface KernelFunctions : StdCallLibrary {
        fun LocalFree(memory: Pointer?): Pointer?
    }

    private companion object {
        const val RPC_C_AUTHN_WINNT = 10
        const val SESSION_FLAG_DYNAMIC = 1
        const val PROVIDER_FLAG_PERSISTENT = 1
        const val SUBLAYER_FLAG_PERSISTENT = 1
        const val FILTER_FLAG_PERSISTENT = 1
        const val FWP_UINT8 = 1
        const val FWP_UINT16 = 2
        const val FWP_UINT32 = 3
        const val FWP_UINT64 = 4
        const val FWP_BYTE_BLOB_TYPE = 12
        const val ACTION_BLOCK = 0x1001
        const val ACTION_PERMIT = 0x1002
        const val MATCH_EQUAL = 0
        const val MATCH_FLAGS_ANY_SET = 7
        const val LOOPBACK_FLAG = 1
        const val PROTECTED_DACL_SECURITY_INFORMATION = 0x80000004.toInt()
        const val SUBLAYER_WEIGHT = 0x7fff
        const val FWP_E_ALREADY_EXISTS = 0x80320009.toInt()
        val FIELD_ALE_APP_ID = UUID.fromString("d78e1e87-8644-4ea5-9437-d809ecefc971")
        val FIELD_IP_LOCAL_INTERFACE = UUID.fromString("4cd62a49-59c3-4969-b7f3-bda5d32890a4")
        val FIELD_FLAGS = UUID.fromString("632ce23b-5167-435c-86d7-e903684aa80c")
        val FIELD_IP_LOCAL_PORT = UUID.fromString("0c1ba1af-5765-453f-af22-a8f791ac775b")
        val FIELD_IP_PROTOCOL = UUID.fromString("3971ef2b-623e-4f9a-8cb1-6e79b806b9a7")
    }
}
