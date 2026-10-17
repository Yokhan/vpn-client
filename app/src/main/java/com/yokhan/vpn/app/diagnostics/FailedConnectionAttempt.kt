package com.yokhan.gates.app.diagnostics

import com.yokhan.gates.shared.domain.diagnostics.DiagnosticEvent
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticEventKind
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticPath
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticTransport
import com.yokhan.gates.shared.domain.diagnostics.IncidentRecorder

internal fun recordFailedConnectionAttempt(recorder: IncidentRecorder, sessionId: String, at: Long, elapsed: Long,
    transport: DiagnosticTransport = DiagnosticTransport.UNKNOWN) {
    val failure = DiagnosticEvent(at, elapsed, sessionId, DiagnosticEventKind.RUNTIME_FAILURE,
        path = DiagnosticPath.CORE, transport = transport, code = 1)
    recorder.record(failure)
    recorder.endSession(failure.copy(kind = DiagnosticEventKind.SESSION_ENDED))
}
