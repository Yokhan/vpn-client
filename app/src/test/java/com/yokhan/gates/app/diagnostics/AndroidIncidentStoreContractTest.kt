package com.yokhan.gates.app.diagnostics

import com.yokhan.gates.shared.domain.diagnostics.DiagnosticEvent
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticEventKind
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticNetwork
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticPath
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticTarget
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticTransport
import com.yokhan.gates.shared.domain.diagnostics.IncidentArchive
import com.yokhan.gates.shared.domain.diagnostics.IncidentStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidIncidentStoreContractTest {
    @Test
    fun readOnlyLookupsDoNotCreateStorage() {
        val parent = createTempDir(prefix = "incident-read-only-")
        val root = File(parent, "not-created")
        try {
            val store = AndroidIncidentStore(root)

            assertNull(store.readOnlySnapshot())
            assertNull(store.readOnlyArchive(ID))
            assertFalse(root.exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun confirmationEvidenceSurvivesRecoveredStatus() {
        val archive = archive(
            status = IncidentStatus.RECOVERED,
            events = listOf(
                event(1, DiagnosticEventKind.PROBE_FAILURE, DiagnosticTarget.GSTATIC),
                event(2, DiagnosticEventKind.PROBE_FAILURE, DiagnosticTarget.YOUTUBE),
                event(3, DiagnosticEventKind.PROBE_SUCCESS, DiagnosticTarget.GSTATIC),
            ),
        )

        assertTrue(isConfirmedIncident(archive))
    }

    @Test
    fun distinctTargetEvidenceMustShareAKnownPath() {
        val unknownPath = archive(
            status = IncidentStatus.CONFIRMED,
            events = listOf(
                event(1, DiagnosticEventKind.PROBE_FAILURE, DiagnosticTarget.GSTATIC, DiagnosticPath.UNKNOWN),
                event(2, DiagnosticEventKind.PROBE_FAILURE, DiagnosticTarget.YOUTUBE, DiagnosticPath.UNKNOWN),
            ),
        )
        val sameTarget = archive(
            status = IncidentStatus.SUSPECTED,
            events = listOf(
                event(1, DiagnosticEventKind.PROBE_FAILURE, DiagnosticTarget.GSTATIC),
                event(2, DiagnosticEventKind.PROBE_FAILURE, DiagnosticTarget.GSTATIC),
            ),
        )

        assertFalse(isConfirmedIncident(unknownPath))
        assertFalse(isConfirmedIncident(sameTarget))
    }

    private fun archive(status: IncidentStatus, events: List<DiagnosticEvent>): IncidentArchive {
        val trigger = events.first()
        return IncidentArchive(ID, SESSION, trigger, status, recovered = status == IncidentStatus.RECOVERED,
            truncated = false, events = events)
    }

    private fun event(
        time: Long,
        kind: DiagnosticEventKind,
        target: DiagnosticTarget,
        path: DiagnosticPath = DiagnosticPath.CORE,
    ) = DiagnosticEvent(
        timestampMillis = time,
        monotonicMillis = time,
        sessionId = SESSION,
        kind = kind,
        target = target,
        transport = DiagnosticTransport.HYSTERIA2,
        network = DiagnosticNetwork.WIFI,
        path = path,
    )

    private companion object {
        const val SESSION = "11111111111111111111111111111111"
        const val ID = "22222222222222222222222222222222"
    }
}
