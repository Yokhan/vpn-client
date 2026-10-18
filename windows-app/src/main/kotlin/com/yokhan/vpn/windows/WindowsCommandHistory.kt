package com.yokhan.gates.windows

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant

/** Store only allowlisted operation metadata, never arguments or raw core/auth output. */
internal fun recordWindowsCommandResult(root: File, command: String, result: RuntimeCommandResult) {
    if (command !in WINDOWS_RECORDED_COMMANDS) return
    runCatching {
        val file = File(root, "logs/operations.json")
        file.parentFile.mkdirs()
        val store = WindowsAtomicStore(root)
        store.transaction {
            val previous = if (file.isFile && file.length() < 256 * 1024) {
                runCatching { JsonParser.parseString(store.readText(file, Charsets.UTF_8)).asJsonArray }
                    .getOrDefault(JsonArray())
            } else JsonArray()
            val entries = JsonArray()
            previous.toList().takeLast(127).forEach(entries::add)
            entries.add(JsonObject().apply {
                addProperty("at", Instant.now().toString())
                addProperty("operation", command)
                addProperty("exitCode", result.exitCode)
                windowsUiFailureCode(command, result)?.let { addProperty("failureCode", it) }
                windowsRuntimeSetupStage(result)?.let { addProperty("setupStage", it) }
            })
            store.writeAtomic(file, entries.toString().toByteArray(Charsets.UTF_8))
        }
    }
}

private val WINDOWS_RECORDED_COMMANDS = setOf(
    "connect", "stop", "prepare-runtime", "install-service", "set-route", "set-runtime",
    "add-app-proxy", "add-app-direct", "remove-app-proxy", "remove-app-direct", "set-tun-routing",
    "login", "import-app", "import-sub",
)
