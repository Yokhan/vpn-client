package com.yokhan.gates.windows

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WindowsIncidentHistoryUiTest {
    @Test
    fun rejectsUnsafeAndBrokenRowsWithoutBreakingHistory() {
        val summary = JsonParser.parseString("""{"archives":[
          {"id":"../../secret"},{"id":"${"a".repeat(32)}","triggered_at_millis":0,
          "status":"interrupted","event_count":3,"truncated":false,"recovered":false}
        ]}""").asJsonObject
        val rows = windowsIncidentHistoryItems(summary)
        assertEquals(1, rows.size)
        assertEquals("Запись прервана", rows.single().outcome)
        assertEquals(true, rows.single().incomplete)
        assertFalse(rows.single().capturedAt.isBlank())
    }
}
