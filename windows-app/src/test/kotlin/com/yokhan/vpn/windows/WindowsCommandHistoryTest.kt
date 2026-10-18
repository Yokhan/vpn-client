package com.yokhan.gates.windows

import com.google.gson.JsonParser
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WindowsCommandHistoryTest {
    @Test fun recordsFailureCodeWithoutSecretsAndKeepsBoundedHistory() {
        val root = Files.createTempDirectory("gates-command-history").toFile()
        repeat(135) {
            recordWindowsCommandResult(root, "prepare-runtime", RuntimeCommandResult(1223, "token=do-not-store password=private"))
        }
        recordWindowsCommandResult(root, "unknown-private-operation", RuntimeCommandResult(1, "secret"))
        val text = root.resolve("logs/operations.json").readText()
        val rows = JsonParser.parseString(text).asJsonArray
        assertEquals(128, rows.size())
        assertEquals("GATES_UAC_CANCELLED", rows.last().asJsonObject.get("failureCode").asString)
        assertFalse(text.contains("do-not-store"))
        assertFalse(text.contains("private"))
        assertFalse(text.contains("secret"))
    }
}
