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

    @Test fun recordsOnlyBoundedAccessMetadata() {
        val root = Files.createTempDirectory("gates-command-access").toFile()
        recordWindowsCommandResult(
            root,
            "login",
            RuntimeCommandResult(
                403,
                "SECRET_DETAIL",
                accessFailure = WindowsAccessFailure(
                    WindowsAccessStage.PROFILE,
                    WindowsAccessCode.ACCESS_NOT_LINKED,
                    403,
                ),
            ),
        )
        val text = root.resolve("logs/operations.json").readText()
        val row = JsonParser.parseString(text).asJsonArray.last().asJsonObject
        assertEquals("profile", row.get("accessStage").asString)
        assertEquals("ACCESS_NOT_LINKED", row.get("accessCode").asString)
        assertEquals(403, row.get("accessHttpStatus").asInt)
        assertFalse(text.contains("SECRET_DETAIL"))
        assertFalse(text.contains("SEEDED"))
    }
}
