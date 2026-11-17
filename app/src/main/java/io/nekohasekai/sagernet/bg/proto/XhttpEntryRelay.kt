package io.nekohasekai.sagernet.bg.proto

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local TCP loopback relay in front of the Xray sidecar outbound.
 *
 * Some mobile networks silently drop the trailing segment of a split TLS
 * ClientHello while leaving TCP ESTABLISHED, stalling Xray's dial for 60s+.
 * This relay captures the initial ClientHello, serializes upstream dials
 * (avoiding SYN/ClientHello bursts), waits briefly for the first ServerHello
 * byte, and retries on a fresh local port if the handshake stalls.
 */
internal class XhttpEntryRelay(
    private val targetHost: String,
    private val targetPort: Int,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val serverHelloTimeoutMs: Int = DEFAULT_SERVER_HELLO_TIMEOUT_MS,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    private val clientHelloReadTimeoutMs: Int = DEFAULT_CLIENT_HELLO_READ_TIMEOUT_MS,
    private val addressResolver: (String) -> InetAddress = { InetAddress.getByName(it) },
    private val socketBinder: (Socket) -> Unit = {},
    private val socketProtector: (Socket) -> Unit = {},
    private val logger: (String) -> Unit = {},
) : Closeable {

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 8
        const val DEFAULT_CONNECT_TIMEOUT_MS = 1800
        const val DEFAULT_SERVER_HELLO_TIMEOUT_MS = 1500
        const val DEFAULT_RETRY_DELAY_MS = 200L
        const val DEFAULT_CLIENT_HELLO_READ_TIMEOUT_MS = 2000
        private const val MAX_INITIAL_BYTES = 16 * 1024 + 5
        private const val PUMP_BUFFER_BYTES = 32 * 1024
    }

    internal class InitialPayload(
        val bytes: ByteArray,
        val length: Int,
    )

    private class EstablishedUpstream(
        val socket: Socket,
        val initialResponse: ByteArray,
        val initialResponseLen: Int,
    )

    private val closed = AtomicBoolean(false)
    private val resetGeneration = AtomicInteger(0)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val dialLock = Any()
    private val threadSeq = AtomicInteger(0)
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "xray-entry-relay-${threadSeq.incrementAndGet()}").apply {
            isDaemon = true
        }
    }

    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0))
    }

    val localPort: Int
        get() = serverSocket.localPort

    init {
        require(targetHost.isNotBlank()) { "targetHost must not be blank" }
        require(targetPort in 1..65535) { "targetPort must be in 1..65535" }
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        executor.execute { acceptLoop() }
    }

    fun resetConnections() {
        resetGeneration.incrementAndGet()
        val snapshot = activeSockets.toList()
        for (socket in snapshot) {
            closeQuietly(socket)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket.close() }
        resetConnections()
        executor.shutdownNow()
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val local = try {
                serverSocket.accept()
            } catch (_: IOException) {
                break
            }
            if (closed.get()) {
                closeQuietly(local)
                break
            }
            registerSocket(local)
            runCatching {
                executor.execute { handleClient(local) }
            }.onFailure {
                closeQuietly(local)
            }
        }
    }

    private fun handleClient(local: Socket) {
        var upstream: Socket? = null
        val genAtAccept = resetGeneration.get()
        try {
            local.tcpNoDelay = true
            local.soTimeout = clientHelloReadTimeoutMs
            val initial = readInitialPayload(local.getInputStream()) ?: return
            local.soTimeout = 0

            val established = dialWithServerHelloRetry(local, initial, genAtAccept) ?: return
            upstream = established.socket

            val localOut = local.getOutputStream()
            localOut.write(established.initialResponse, 0, established.initialResponseLen)
            localOut.flush()

            val upToLocal = executor.submit {
                copyStream(upstream.getInputStream(), localOut, local)
            }
            copyStream(local.getInputStream(), upstream.getOutputStream(), upstream)
            runCatching { upToLocal.get() }
        } catch (e: IOException) {
            if (!closed.get()) {
                logger("entry relay session ended: ${e.message ?: e.javaClass.simpleName}")
            }
        } finally {
            closeQuietly(local)
            upstream?.let { closeQuietly(it) }
        }
    }

    private fun readInitialPayload(input: InputStream): InitialPayload? {
        val buf = ByteArray(MAX_INITIAL_BYTES)
        val firstRead = input.read(buf, 0, buf.size)
        if (firstRead <= 0) return null
        var filled = firstRead

        // Buffer a complete TLS Handshake record so retries replay the full ClientHello.
        while (filled >= 5 && isTlsHandshakeHeader(buf)) {
            val bodyLen = ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
            val totalTlsLen = 5 + bodyLen
            if (totalTlsLen !in 6..MAX_INITIAL_BYTES || filled >= totalTlsLen) {
                break
            }
            val n = input.read(buf, filled, buf.size - filled)
            if (n <= 0) break
            filled += n
        }
        return InitialPayload(buf, filled)
    }

    private fun dialWithServerHelloRetry(
        local: Socket,
        initial: InitialPayload,
        genAtAccept: Int,
    ): EstablishedUpstream? {
        synchronized(dialLock) {
            if (closed.get() || local.isClosed || resetGeneration.get() != genAtAccept) {
                return null
            }
            val targetAddr = try {
                addressResolver(targetHost)
            } catch (e: Exception) {
                logger("entry relay DNS resolution failed for $targetHost: ${e.message ?: e.javaClass.simpleName}")
                return null
            }
            val firstResp = ByteArray(16 * 1024)
            for (attempt in 1..maxAttempts) {
                if (closed.get() || local.isClosed || resetGeneration.get() != genAtAccept) {
                    return null
                }
                val upstream = Socket()
                registerSocket(upstream)
                try {
                    upstream.tcpNoDelay = true
                    runCatching { socketBinder(upstream) }
                    socketProtector(upstream)
                    upstream.connect(InetSocketAddress(targetAddr, targetPort), connectTimeoutMs)
                    val out = upstream.getOutputStream()
                    out.write(initial.bytes, 0, initial.length)
                    out.flush()
                    upstream.soTimeout = serverHelloTimeoutMs
                    val n = upstream.getInputStream().read(firstResp)
                    if (n > 0) {
                        upstream.soTimeout = 0
                        if (attempt > 1) {
                            logger("entry relay recovered on attempt $attempt/$maxAttempts")
                        }
                        return EstablishedUpstream(upstream, firstResp, n)
                    }
                    logger("entry relay attempt $attempt/$maxAttempts got EOF before ServerHello")
                } catch (_: SocketTimeoutException) {
                    logger("entry relay attempt $attempt/$maxAttempts timed out waiting ServerHello")
                } catch (e: IOException) {
                    if (!closed.get() && resetGeneration.get() == genAtAccept) {
                        logger("entry relay attempt $attempt/$maxAttempts failed: ${e.message ?: e.javaClass.simpleName}")
                    }
                } catch (t: Throwable) {
                    closeQuietly(upstream)
                    throw t
                }
                closeQuietly(upstream)
                if (
                    !closed.get() &&
                    !local.isClosed &&
                    resetGeneration.get() == genAtAccept &&
                    attempt < maxAttempts &&
                    retryDelayMs > 0L
                ) {
                    try {
                        Thread.sleep(retryDelayMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
            }
            return null
        }
    }

    private fun copyStream(
        input: InputStream,
        output: OutputStream,
        destinationSocket: Socket,
    ) {
        val buffer = ByteArray(PUMP_BUFFER_BYTES)
        try {
            while (!closed.get()) {
                val n = input.read(buffer)
                if (n < 0) {
                    runCatching { destinationSocket.shutdownOutput() }
                    break
                }
                if (n > 0) {
                    output.write(buffer, 0, n)
                    output.flush()
                }
            }
        } catch (_: IOException) {
            // Normal teardown on close or resetConnections().
        }
    }

    private fun registerSocket(socket: Socket) {
        activeSockets.add(socket)
        if (closed.get()) {
            closeQuietly(socket)
        }
    }

    private fun closeQuietly(socket: Socket) {
        activeSockets.remove(socket)
        runCatching { socket.close() }
    }

    private fun isTlsHandshakeHeader(buf: ByteArray): Boolean =
        buf[0] == 0x16.toByte() && buf[1] == 0x03.toByte()
}
