package com.yokhan.gates.windows

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.roundToLong

private const val IF_TYPE_SOFTWARE_LOOPBACK = 24
private const val IF_OPER_STATUS_OPERATIONAL = 4
private const val MIB_IFROW_SIZE = 860

private interface IphlpapiIfTable : StdCallLibrary {
    fun GetIfTable(
        pIfTable: Pointer?,
        pdwSize: IntByReference,
        bOrder: Boolean,
    ): Int

    companion object {
        val INSTANCE: IphlpapiIfTable? by lazy {
            runCatching { Native.load("iphlpapi", IphlpapiIfTable::class.java) }.getOrNull()
        }
    }
}

internal data class WindowsLiveTelemetrySnapshot(
    val uploadBytesPerSec: Long = 0L,
    val downloadBytesPerSec: Long = 0L,
    val uploadLabel: String = "0 B/s",
    val downloadLabel: String = "0 B/s",
    val timerLabel: String = "00:00:00",
)

internal class WindowsLiveTelemetryTracker(
    private val octetsSampler: () -> Pair<Long, Long>? = ::sampleActiveWindowsInterfaceOctets,
    private val nanoClock: () -> Long = System::nanoTime,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private var lastSampleNanos: Long = 0L
    private var lastInBytes: Long = -1L
    private var lastOutBytes: Long = -1L

    fun reset() {
        lastSampleNanos = 0L
        lastInBytes = -1L
        lastOutBytes = -1L
    }

    fun sample(
        connected: Boolean,
        startedAtEpochMillis: Long?,
    ): WindowsLiveTelemetrySnapshot {
        if (!connected) {
            reset()
            return WindowsLiveTelemetrySnapshot()
        }
        val timer = formatWindowsElapsedTimer(startedAtEpochMillis, wallClock())
        val nowNanos = nanoClock()
        val counters = runCatching { octetsSampler() }.getOrNull()
        if (counters == null) {
            return WindowsLiveTelemetrySnapshot(
                uploadLabel = "--",
                downloadLabel = "--",
                timerLabel = timer,
            )
        }
        val (inOctets, outOctets) = counters
        val prevNanos = lastSampleNanos
        val prevIn = lastInBytes
        val prevOut = lastOutBytes
        lastSampleNanos = nowNanos
        lastInBytes = inOctets
        lastOutBytes = outOctets

        if (prevNanos == 0L || prevIn < 0L || prevOut < 0L || nowNanos <= prevNanos) {
            return WindowsLiveTelemetrySnapshot(
                uploadBytesPerSec = 0L,
                downloadBytesPerSec = 0L,
                uploadLabel = "0 B/s",
                downloadLabel = "0 B/s",
                timerLabel = timer,
            )
        }
        val elapsedSec = (nowNanos - prevNanos).toDouble() / 1_000_000_000.0
        val deltaIn = ((inOctets - prevIn) and 0xFFFFFFFFL).coerceAtLeast(0L)
        val deltaOut = ((outOctets - prevOut) and 0xFFFFFFFFL).coerceAtLeast(0L)
        val downBps = if (elapsedSec > 0.05) (deltaIn.toDouble() / elapsedSec).roundToLong() else 0L
        val upBps = if (elapsedSec > 0.05) (deltaOut.toDouble() / elapsedSec).roundToLong() else 0L
        return WindowsLiveTelemetrySnapshot(
            uploadBytesPerSec = upBps,
            downloadBytesPerSec = downBps,
            uploadLabel = formatWindowsTransferRate(upBps),
            downloadLabel = formatWindowsTransferRate(downBps),
            timerLabel = timer,
        )
    }
}

internal fun sampleActiveWindowsInterfaceOctets(): Pair<Long, Long>? {
    val api = IphlpapiIfTable.INSTANCE ?: return null
    val sizeRef = IntByReference(0)
    api.GetIfTable(null, sizeRef, false)
    var bufferSize = sizeRef.value.coerceAtLeast(1024)
    var mem = Memory(bufferSize.toLong())
    var rc = api.GetIfTable(mem, sizeRef, false)
    if (rc == 122) {
        bufferSize = sizeRef.value.coerceAtLeast(bufferSize * 2)
        mem = Memory(bufferSize.toLong())
        rc = api.GetIfTable(mem, sizeRef, false)
    }
    if (rc != 0) return null

    val count = mem.getInt(0).coerceIn(0, 256)
    var tunIn = 0L
    var tunOut = 0L
    var foundTun = false
    var physIn = 0L
    var physOut = 0L
    var foundPhys = false

    for (i in 0 until count) {
        val base = 4L + i.toLong() * MIB_IFROW_SIZE
        if (base + MIB_IFROW_SIZE > mem.size()) break
        val dwType = mem.getInt(base + 516)
        if (dwType == IF_TYPE_SOFTWARE_LOOPBACK) continue
        val dwOperStatus = mem.getInt(base + 544)
        if (dwOperStatus < IF_OPER_STATUS_OPERATIONAL) continue

        val inOctets = mem.getInt(base + 552).toLong() and 0xFFFFFFFFL
        val outOctets = mem.getInt(base + 576).toLong() and 0xFFFFFFFFL
        val descrLen = mem.getInt(base + 600).coerceIn(0, 255)
        val descrBytes = ByteArray(descrLen)
        if (descrLen > 0) {
            mem.read(base + 604, descrBytes, 0, descrLen)
        }
        val descr = String(descrBytes, StandardCharsets.US_ASCII).lowercase(Locale.ROOT)
        if ("wintun" in descr || "gates" in descr || "sing-tun" in descr || "tun" in descr) {
            foundTun = true
            tunIn += inOctets
            tunOut += outOctets
        } else {
            foundPhys = true
            physIn += inOctets
            physOut += outOctets
        }
    }

    return when {
        foundTun -> tunIn to tunOut
        foundPhys -> physIn to physOut
        else -> null
    }
}

internal fun formatWindowsTransferRate(bytesPerSec: Long): String {
    val safe = bytesPerSec.coerceAtLeast(0L)
    return when {
        safe < 1024L -> "$safe B/s"
        safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB/s", safe / 1024.0)
        safe < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB/s", safe / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB/s", safe / (1024.0 * 1024.0 * 1024.0))
    }
}

internal fun formatWindowsElapsedTimer(startedAtEpochMillis: Long?, nowMillis: Long = System.currentTimeMillis()): String {
    if (startedAtEpochMillis == null || startedAtEpochMillis <= 0L || nowMillis < startedAtEpochMillis) {
        return "--:--:--"
    }
    val totalSeconds = ((nowMillis - startedAtEpochMillis) / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}
