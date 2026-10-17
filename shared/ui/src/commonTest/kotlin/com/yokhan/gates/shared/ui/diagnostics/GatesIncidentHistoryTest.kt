package com.yokhan.gates.shared.ui.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals

class GatesIncidentHistoryTest {
    @Test
    fun labelsDoNotTurnStoppedOrUnknownIntoRecovery() {
        assertEquals("Подключение завершено", gatesIncidentOutcome("ended", false, false))
        assertEquals("Запись прервана", gatesIncidentOutcome("INTERRUPTED", false, false))
        assertEquals("Возможный сбой", gatesIncidentOutcome("unrecognized", false, false))
        assertEquals("Связь восстановилась", gatesIncidentOutcome("RECOVERED", true, false))
        assertEquals("Сбой записывается", gatesIncidentOutcome("RECOVERED", true, true))
    }
}
