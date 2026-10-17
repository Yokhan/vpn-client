package com.yokhan.gates.windows

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WindowsAtomicStoreTest {
    @Test
    fun replacesOldBytesWithTheCompleteNewPayload() {
        val root = Files.createTempDirectory("gates-atomic-store").toFile()
        val target = File(root, "nested/config.json")
        assertTrue(target.parentFile.mkdirs())
        val unrelatedStage = File(target.parentFile, ".gates-atomic-preserve.tmp")
        unrelatedStage.writeText("keep")
        val oldBytes = "old-config".toByteArray()
        val newBytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        val store = WindowsAtomicStore(root)

        store.writeAtomic(target, oldBytes)
        assertContentEquals(oldBytes, store.readBytes(target))
        assertEquals("old-config", store.readText(target))

        store.writeAtomic(target, newBytes)

        assertContentEquals(newBytes, store.readBytes(target))
        assertEquals(setOf("config.json", unrelatedStage.name), target.parentFile.list()?.toSet())
        assertEquals("keep", unrelatedStage.readText())
    }

    @Test
    fun concurrentWritersNeverExposePartialPayloads() {
        val root = Files.createTempDirectory("gates-atomic-contention").toFile()
        val target = File(root, "shared.bin")
        val first = ByteArray(512 * 1024) { 0x35 }
        val second = ByteArray(512 * 1024) { 0x6A }
        val store = WindowsAtomicStore(root)
        store.writeAtomic(target, first)

        val start = CountDownLatch(1)
        val writersDone = CountDownLatch(2)
        val observations = AtomicInteger()
        val executor = Executors.newFixedThreadPool(3)
        try {
            val reader = executor.submit(Callable {
                start.await()
                do {
                    val observed = store.readBytes(target)
                    check(observed.contentEquals(first) || observed.contentEquals(second)) {
                        "Reader observed a partial or unexpected file payload."
                    }
                    observations.incrementAndGet()
                } while (writersDone.count > 0)
            })
            val writers = listOf(first, second).map { payload ->
                executor.submit(Callable {
                    start.await()
                    try {
                        repeat(40) { store.writeAtomic(target, payload) }
                    } finally {
                        writersDone.countDown()
                    }
                })
            }

            start.countDown()
            writers.forEach { it.get(30, TimeUnit.SECONDS) }
            reader.get(30, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        assertTrue(observations.get() > 0)
        val finalBytes = store.readBytes(target)
        assertTrue(finalBytes.contentEquals(first) || finalBytes.contentEquals(second))
    }

    @Test
    fun nestedTransactionsAcrossStoreInstancesReuseTheRootLock() {
        val root = Files.createTempDirectory("gates-atomic-nested").toFile()
        val outer = WindowsAtomicStore(root)
        val inner = WindowsAtomicStore(File(root, "."))
        val target = File(root, "import/config.json")
        val payload = "imported-config".toByteArray()

        val result = outer.transaction {
            inner.writeAtomic(target, payload)
            "import-complete"
        }

        assertEquals("import-complete", result)
        assertContentEquals(payload, outer.readBytes(target))
    }

    @Test
    fun rejectsTargetsOutsideTheRoot() {
        val root = Files.createTempDirectory("gates-atomic-boundary").toFile()
        val outside = File(root.parentFile, "${root.name}-outside.json")
        val store = WindowsAtomicStore(root)

        assertFailsWith<IllegalArgumentException> {
            store.writeAtomic(outside, "must-not-write".toByteArray())
        }
        assertTrue(!outside.exists())
    }

}
