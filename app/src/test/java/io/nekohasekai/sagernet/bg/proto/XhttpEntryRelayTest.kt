package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class XhttpEntryRelayTest {

    private fun buildTlsClientHello(payloadSize: Int = 1800): ByteArray {
        val headerLen = 5
        val packet = ByteArray(headerLen + payloadSize)
        packet[0] = 0x16
        packet[1] = 0x03
        packet[2] = 0x01
        packet[3] = ((payloadSize ushr 8) and 0xFF).toByte()
        packet[4] = (payloadSize and 0xFF).toByte()
        for (i in headerLen until packet.size) {
            packet[i] = ((i * 31) and 0xFF).toByte()
        }
        return packet
    }

    @Test
    fun relaysTlsSessionOnFirstAttemptAndInvokesSocketHooks() {
        val clientHello = buildTlsClientHello(1722)
        val serverHello = byteArrayOf(0x16, 0x03, 0x03, 0x00, 0x04, 1, 2, 3, 4)
        val upstreamAttempts = AtomicInteger(0)
        val receivedHello = CopyOnWriteArrayList<ByteArray>()
        val protectedCount = AtomicInteger(0)
        val boundCount = AtomicInteger(0)

        val server = ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val serverThread = Thread {
            val s = server.accept()
            upstreamAttempts.incrementAndGet()
            val inp = s.getInputStream()
            val buf = ByteArray(clientHello.size)
            var read = 0
            while (read < buf.size) {
                val n = inp.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            receivedHello.add(buf.copyOf(read))
            s.getOutputStream().apply {
                write(serverHello)
                flush()
            }
            val echo = ByteArray(16)
            val en = inp.read(echo)
            if (en > 0) {
                s.getOutputStream().apply {
                    write(echo, 0, en)
                    flush()
                }
            }
            s.close()
        }.apply { isDaemon = true; start() }

        XhttpEntryRelay(
            targetHost = "127.0.0.1",
            targetPort = server.localPort,
            serverHelloTimeoutMs = 500,
            socketBinder = { boundCount.incrementAndGet() },
            socketProtector = { protectedCount.incrementAndGet() },
        ).use { relay ->
            Socket("127.0.0.1", relay.localPort).use { client ->
                client.soTimeout = 3000
                client.getOutputStream().apply {
                    write(clientHello)
                    flush()
                }
                val resp = ByteArray(serverHello.size)
                var got = 0
                while (got < resp.size) {
                    val n = client.getInputStream().read(resp, got, resp.size - got)
                    if (n < 0) break
                    got += n
                }
                assertEquals(serverHello.size, got)
                assertArrayEquals(serverHello, resp)

                val ping = "PING".toByteArray()
                client.getOutputStream().apply {
                    write(ping)
                    flush()
                }
                val pong = ByteArray(ping.size)
                val pn = client.getInputStream().read(pong)
                assertEquals(ping.size, pn)
                assertArrayEquals(ping, pong)
            }
        }
        serverThread.join(2000)
        server.close()

        assertEquals(1, upstreamAttempts.get())
        assertEquals(1, boundCount.get())
        assertEquals(1, protectedCount.get())
        assertEquals(1, receivedHello.size)
        assertArrayEquals(clientHello, receivedHello[0])
    }

    @Test
    fun retriesWhenFirstAttemptStallsBeforeServerHelloAndReplaysClientHello() {
        val clientHello = buildTlsClientHello(1722)
        val serverHello = byteArrayOf(0x16, 0x03, 0x03, 0x00, 0x02, 0x55, 0x66)
        val attemptCounter = AtomicInteger(0)
        val stalledClosed = CountDownLatch(1)
        val replayedHellos = CopyOnWriteArrayList<ByteArray>()

        val server = ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val serverThread = Thread {
            // Attempt 1: accept, read ClientHello, stay silent so relay ServerHello timeout fires.
            val stalled = server.accept()
            attemptCounter.incrementAndGet()
            val buf1 = ByteArray(clientHello.size)
            var r1 = 0
            while (r1 < buf1.size) {
                val n = stalled.getInputStream().read(buf1, r1, buf1.size - r1)
                if (n < 0) break
                r1 += n
            }
            replayedHellos.add(buf1.copyOf(r1))
            Thread {
                // Wait until relay closes the stalled socket.
                runCatching {
                    while (stalled.getInputStream().read() >= 0) Unit
                }
                stalledClosed.countDown()
                runCatching { stalled.close() }
            }.apply { isDaemon = true; start() }

            // Attempt 2: accept and reply cleanly with ServerHello.
            val healthy = server.accept()
            attemptCounter.incrementAndGet()
            val buf2 = ByteArray(clientHello.size)
            var r2 = 0
            while (r2 < buf2.size) {
                val n = healthy.getInputStream().read(buf2, r2, buf2.size - r2)
                if (n < 0) break
                r2 += n
            }
            replayedHellos.add(buf2.copyOf(r2))
            healthy.getOutputStream().apply {
                write(serverHello)
                flush()
            }
            healthy.close()
        }.apply { isDaemon = true; start() }

        XhttpEntryRelay(
            targetHost = "127.0.0.1",
            targetPort = server.localPort,
            maxAttempts = 3,
            serverHelloTimeoutMs = 150,
            retryDelayMs = 20,
        ).use { relay ->
            Socket("127.0.0.1", relay.localPort).use { client ->
                client.soTimeout = 3000
                // Split ClientHello across two writes to verify buffering of the full TLS record.
                client.getOutputStream().apply {
                    write(clientHello, 0, 1308)
                    flush()
                    Thread.sleep(15)
                    write(clientHello, 1308, clientHello.size - 1308)
                    flush()
                }
                val resp = ByteArray(serverHello.size)
                var got = 0
                while (got < resp.size) {
                    val n = client.getInputStream().read(resp, got, resp.size - got)
                    if (n < 0) break
                    got += n
                }
                assertEquals(serverHello.size, got)
                assertArrayEquals(serverHello, resp)
            }
        }
        serverThread.join(3000)
        server.close()

        assertTrue("Stalled attempt socket was not closed promptly", stalledClosed.await(2, TimeUnit.SECONDS))
        assertEquals(2, attemptCounter.get())
        assertEquals(2, replayedHellos.size)
        assertArrayEquals(clientHello, replayedHellos[0])
        assertArrayEquals(clientHello, replayedHellos[1])
    }

    @Test
    fun resetConnectionsClosesActiveSocketsWithoutStoppingRelay() {
        val server = ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val sessionCount = AtomicInteger(0)
        val firstConnected = CountDownLatch(1)
        val serverThread = Thread {
            repeat(2) {
                val s = runCatching { server.accept() }.getOrNull() ?: return@Thread
                val idx = sessionCount.incrementAndGet()
                Thread {
                    runCatching {
                        val b = ByteArray(64)
                        val n = s.getInputStream().read(b)
                        if (n > 0) {
                            s.getOutputStream().apply {
                                write(b, 0, n)
                                flush()
                            }
                        }
                        if (idx == 1) {
                            firstConnected.countDown()
                            while (s.getInputStream().read() >= 0) Unit
                        }
                    }
                    runCatching { s.close() }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }

        XhttpEntryRelay(
            targetHost = "127.0.0.1",
            targetPort = server.localPort,
            serverHelloTimeoutMs = 1000,
        ).use { relay ->
            val firstClient = Socket("127.0.0.1", relay.localPort)
            firstClient.soTimeout = 3000
            firstClient.getOutputStream().apply {
                write("ONE".toByteArray())
                flush()
            }
            val ack = ByteArray(3)
            assertEquals(3, firstClient.getInputStream().read(ack))
            assertTrue(firstConnected.await(2, TimeUnit.SECONDS))

            relay.resetConnections()

            // First connection should immediately hit EOF / IOException after reset.
            val eofOrError = try {
                firstClient.getInputStream().read() < 0
            } catch (_: IOException) {
                true
            }
            assertTrue("Expected active connection to terminate on resetConnections", eofOrError)
            runCatching { firstClient.close() }

            // Relay listener must still accept new connections on the same localPort.
            Socket("127.0.0.1", relay.localPort).use { secondClient ->
                secondClient.soTimeout = 3000
                secondClient.getOutputStream().apply {
                    write("TWO".toByteArray())
                    flush()
                }
                val ack2 = ByteArray(3)
                assertEquals(3, secondClient.getInputStream().read(ack2))
                assertArrayEquals("TWO".toByteArray(), ack2)
            }
        }
        serverThread.join(2000)
        server.close()
        assertEquals(2, sessionCount.get())
    }
}
