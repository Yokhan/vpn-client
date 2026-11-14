package com.yokhan.gates.windows

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.File

private const val AF_INET = 2
private const val TCP_TABLE_OWNER_PID_CONNECTIONS = 4
private const val UDP_TABLE_OWNER_PID = 1
private const val ERROR_INSUFFICIENT_BUFFER = 122
private const val TCP_MIB_LISTEN_STATE = 2
private const val TCP_ROW_BYTES = 24
private const val UDP_ROW_BYTES = 12

private interface IphlpapiSocketTables : StdCallLibrary {
    fun GetExtendedTcpTable(
        pTcpTable: Pointer?,
        pdwSize: IntByReference,
        bOrder: Boolean,
        ulAf: Int,
        tableClass: Int,
        reserved: Int,
    ): Int

    fun GetExtendedUdpTable(
        pUdpTable: Pointer?,
        pdwSize: IntByReference,
        bOrder: Boolean,
        ulAf: Int,
        tableClass: Int,
        reserved: Int,
    ): Int

    companion object {
        val INSTANCE: IphlpapiSocketTables? by lazy {
            runCatching { Native.load("iphlpapi", IphlpapiSocketTables::class.java) }.getOrNull()
        }
    }
}

internal data class WindowsActiveNetworkApp(
    val name: String,
    val path: String,
    val pid: Long,
    val tcpCount: Int,
    val udpCount: Int,
    val status: WindowsAppRouteStatus,
    val isRuCandidate: Boolean,
)

internal object WindowsActiveConnections {
    fun query(appRoutingState: AppRoutingState, limit: Int = 24): List<WindowsActiveNetworkApp> {
        val api = IphlpapiSocketTables.INSTANCE ?: return emptyList()
        val tcpPids = readTcpPids(api)
        val udpPids = readUdpPids(api)

        val tcpCounts = HashMap<Long, Int>()
        tcpPids.forEach { pid -> tcpCounts[pid] = (tcpCounts[pid] ?: 0) + 1 }
        val udpCounts = HashMap<Long, Int>()
        udpPids.forEach { pid -> udpCounts[pid] = (udpCounts[pid] ?: 0) + 1 }

        val allPids = (tcpCounts.keys + udpCounts.keys).filter { it > 4L }
        if (allPids.isEmpty()) return emptyList()

        data class Accumulator(
            val name: String,
            val path: String,
            val pid: Long,
            var tcp: Int = 0,
            var udp: Int = 0,
        )

        val grouped = linkedMapOf<String, Accumulator>()
        for (pid in allPids) {
            val handle = ProcessHandle.of(pid).orElse(null) ?: continue
            val cmdPath = handle.info().command().orElse("").trim()
            if (cmdPath.isBlank() || !cmdPath.endsWith(".exe", ignoreCase = true)) continue
            val exeName = File(cmdPath).name
            if (isWindowsUninstallerOrHelperExe(exeName)) continue
            val lowerName = exeName.lowercase()
            if (lowerName in setOf("svchost.exe", "system", "lsass.exe", "services.exe", "wininit.exe")) continue

            val acc = grouped.getOrPut(lowerName) {
                Accumulator(name = exeName, path = cmdPath, pid = pid)
            }
            acc.tcp += tcpCounts[pid] ?: 0
            acc.udp += udpCounts[pid] ?: 0
        }

        return grouped.values
            .map { acc ->
                WindowsActiveNetworkApp(
                    name = acc.name,
                    path = acc.path,
                    pid = acc.pid,
                    tcpCount = acc.tcp,
                    udpCount = acc.udp,
                    status = windowsAppRouteStatus(acc.name, acc.path, appRoutingState),
                    isRuCandidate = isRuCompatibilityCandidate(acc.name, acc.path),
                )
            }
            .sortedWith(
                compareByDescending<WindowsActiveNetworkApp> { it.tcpCount + it.udpCount }
                    .thenBy { it.name.lowercase() },
            )
            .take(limit)
    }

    private fun readTcpPids(api: IphlpapiSocketTables): List<Long> {
        val mem = fetchTableWithRetry { ptr, sizeRef ->
            api.GetExtendedTcpTable(ptr, sizeRef, false, AF_INET, TCP_TABLE_OWNER_PID_CONNECTIONS, 0)
        } ?: return emptyList()
        if (mem.size() < 4L) return emptyList()
        val numEntries = mem.getInt(0).coerceIn(0, 8192)
        val pids = ArrayList<Long>(numEntries)
        for (i in 0 until numEntries) {
            val offset = 4L + i.toLong() * TCP_ROW_BYTES
            if (offset + TCP_ROW_BYTES > mem.size()) break
            val state = mem.getInt(offset)
            if (state == TCP_MIB_LISTEN_STATE) continue
            val pid = mem.getInt(offset + 20).toLong() and 0xFFFFFFFFL
            if (pid > 4L) pids.add(pid)
        }
        return pids
    }

    private fun readUdpPids(api: IphlpapiSocketTables): List<Long> {
        val mem = fetchTableWithRetry { ptr, sizeRef ->
            api.GetExtendedUdpTable(ptr, sizeRef, false, AF_INET, UDP_TABLE_OWNER_PID, 0)
        } ?: return emptyList()
        if (mem.size() < 4L) return emptyList()
        val numEntries = mem.getInt(0).coerceIn(0, 8192)
        val pids = ArrayList<Long>(numEntries)
        for (i in 0 until numEntries) {
            val offset = 4L + i.toLong() * UDP_ROW_BYTES
            if (offset + UDP_ROW_BYTES > mem.size()) break
            val pid = mem.getInt(offset + 8).toLong() and 0xFFFFFFFFL
            if (pid > 4L) pids.add(pid)
        }
        return pids
    }

    private fun fetchTableWithRetry(call: (Pointer?, IntByReference) -> Int): Memory? {
        val sizeRef = IntByReference(0)
        call(null, sizeRef)
        var cap = sizeRef.value.coerceAtLeast(4096)
        repeat(4) {
            val mem = Memory(cap.toLong())
            sizeRef.value = cap
            val rc = call(mem, sizeRef)
            if (rc == 0) return mem
            if (rc != ERROR_INSUFFICIENT_BUFFER) return null
            cap = sizeRef.value.coerceAtLeast(cap * 2)
        }
        return null
    }
}
