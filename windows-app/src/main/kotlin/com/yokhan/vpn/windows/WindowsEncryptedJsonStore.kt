package com.yokhan.gates.windows

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal fun interface WindowsSecretProtector {
    fun protect(value: ByteArray): ByteArray
}

internal interface WindowsSecretUnprotector {
    fun unprotect(value: ByteArray): ByteArray
}

internal object WindowsDpapiSecretProtector : WindowsSecretProtector, WindowsSecretUnprotector {
    override fun protect(value: ByteArray): ByteArray = Crypt32Util.cryptProtectData(value)

    override fun unprotect(value: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(value)
}

internal data class StoredJson(
    val json: String,
    val needsMigration: Boolean,
)

internal class WindowsEncryptedJsonStore(
    private val file: File,
    private val protector: WindowsSecretProtector = WindowsDpapiSecretProtector,
    private val unprotector: WindowsSecretUnprotector = WindowsDpapiSecretProtector,
) {
    private val lockFile = File(file.parentFile, "${file.name}.lock")
    private val localLock = authStoreLocks.computeIfAbsent(lockFile.canonicalPath.lowercase(Locale.ROOT)) { ReentrantLock() }

    fun <T> transaction(block: () -> T): T = localLock.withLock {
        if (localLock.holdCount > 1) return@withLock block()
        file.parentFile?.mkdirs()
        RandomAccessFile(lockFile, "rw").use { handle ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            var acquired: java.nio.channels.FileLock? = null
            while (acquired == null) {
                acquired = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (acquired == null) {
                    check(System.nanoTime() < deadline) { "Timed out waiting for GATES auth state" }
                    Thread.sleep(10)
                }
            }
            acquired.use { block() }
        }
    }

    fun write(json: String) = transaction {
        val protected = protector.protect(json.toByteArray(StandardCharsets.UTF_8))
        val payload = Base64.getEncoder().encodeToString(protected)
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(FILE_HEADER + payload, StandardCharsets.US_ASCII)
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
        Unit
    }

    fun read(): StoredJson? = transaction {
        if (!file.isFile) return@transaction null
        val bytes = file.readBytes()
        val headerBytes = FILE_HEADER.toByteArray(StandardCharsets.US_ASCII)
        if (!bytes.startsWith(headerBytes)) {
            return@transaction StoredJson(bytes.toString(StandardCharsets.UTF_8), needsMigration = true)
        }
        val encoded = bytes.copyOfRange(headerBytes.size, bytes.size).toString(StandardCharsets.US_ASCII)
        val protected = Base64.getDecoder().decode(encoded)
        val plain = unprotector.unprotect(protected)
        StoredJson(plain.toString(StandardCharsets.UTF_8), needsMigration = false)
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    return prefix.indices.all { index -> this[index] == prefix[index] }
}

private const val FILE_HEADER = "GATES-DPAPI-1\n"
private val authStoreLocks = ConcurrentHashMap<String, ReentrantLock>()
