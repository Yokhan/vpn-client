package com.yokhan.gates.windows

import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsEncryptedJsonStoreTest {
    @Test
    fun storesJsonWithoutLeavingPlaintextTokensOnDisk() {
        val file = Files.createTempDirectory("gates-auth").resolve("auth-state.json").toFile()
        val protector = ReversibleProtector()
        val store = WindowsEncryptedJsonStore(file, protector, protector)
        val json = """{"refresh_token":"secret-value"}"""

        store.write(json)

        assertFalse(file.readText().contains("secret-value"))
        assertEquals(StoredJson(json, needsMigration = false), store.read())
    }

    @Test
    fun recognizesLegacyPlaintextForControlledMigration() {
        val file = Files.createTempDirectory("gates-auth").resolve("auth-state.json").toFile()
        file.writeText("""{"access_token":"legacy"}""")
        val protector = ReversibleProtector()
        val store = WindowsEncryptedJsonStore(file, protector, protector)

        val stored = store.read()

        assertEquals("""{"access_token":"legacy"}""", stored?.json)
        assertTrue(stored?.needsMigration == true)
    }

    @Test
    fun twoInstancesSerializeWholeTokenRefreshTransactions() {
        val file = Files.createTempDirectory("gates-auth-transaction").resolve("auth-state.json").toFile()
        val protector = ReversibleProtector()
        val stores = List(2) { WindowsEncryptedJsonStore(file, protector, protector) }
        stores.first().write("0")
        val executor = Executors.newFixedThreadPool(2)
        try {
            executor.invokeAll(stores.map { store -> Callable {
                store.transaction {
                    val generation = store.read()!!.json.toInt()
                    Thread.sleep(25)
                    store.write((generation + 1).toString())
                }
            } }).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
        assertEquals("2", stores.first().read()?.json)
        assertFalse(file.resolveSibling("auth-state.json.tmp").exists())
    }

    private class ReversibleProtector : WindowsSecretProtector, WindowsSecretUnprotector {
        override fun protect(value: ByteArray): ByteArray = value.reversedArray()

        override fun unprotect(value: ByteArray): ByteArray = value.reversedArray()
    }
}
