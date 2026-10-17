package com.yokhan.gates.app.diagnostics

import com.yokhan.gates.shared.domain.diagnostics.DiagnosticEvent
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticEventKind
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticNetwork
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticPath
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticTarget
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticTransport
import com.yokhan.gates.shared.domain.diagnostics.IncidentStatus
import com.yokhan.gates.shared.domain.diagnostics.IncidentArchive
import com.yokhan.gates.shared.domain.diagnostics.IncidentCheckpoint
import com.yokhan.gates.shared.domain.diagnostics.IncidentPolicy
import com.yokhan.gates.shared.domain.diagnostics.IncidentRecorder
import com.yokhan.gates.shared.domain.diagnostics.IncidentStore
import com.yokhan.gates.shared.domain.diagnostics.RecoveryDeliveryQueue
import com.yokhan.gates.shared.domain.diagnostics.RecoveryDeliveryState
import com.yokhan.gates.shared.domain.diagnostics.RecoveryDeliveryStore
import com.yokhan.gates.shared.domain.diagnostics.RecoveryReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AndroidRecoveryDeliveryTest {
    @Test
    fun workManagerComponentsShareTheVpnBackgroundProcess() {
        val source = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .first { it.isFile }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val services = factory.newDocumentBuilder().parse(source).getElementsByTagName("service")
        val android = "http://schemas.android.com/apk/res/android"
        val owners = (0 until services.length).map { services.item(it) as org.w3c.dom.Element }
            .associate { it.getAttributeNS(android, "name") to it.getAttributeNS(android, "process") }
        listOf("androidx.work.multiprocess.RemoteWorkManagerService",
            "androidx.work.impl.background.systemjob.SystemJobService",
            "androidx.work.impl.background.systemalarm.SystemAlarmService",
            "androidx.work.impl.foreground.SystemForegroundService").forEach { name ->
            assertEquals(":bg", owners[name], name)
        }
        val jobService = (0 until services.length).map { services.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(android, "name") == "androidx.work.impl.background.systemjob.SystemJobService" }
        assertEquals("@bool/enable_system_job_service_default", jobService.getAttributeNS(android, "enabled"))
        assertEquals("23", jobService.getAttributeNS("http://schemas.android.com/tools", "targetApi"))
    }

    @Test
    fun failureBeforeCoreCreationIsSealedWithoutClaimingRecovery() {
        val store = MemoryIncidentStore()
        val recorder = IncidentRecorder(IncidentPolicy(), store) { ID }
        recordFailedConnectionAttempt(recorder, SESSION, 100, 50)
        val archive = store.archives.single()
        assertEquals(DiagnosticEventKind.RUNTIME_FAILURE, archive.trigger.kind)
        assertEquals(IncidentStatus.ENDED, archive.status)
        assertFalse(archive.recovered)
        assertEquals(null, store.checkpoint?.active)
    }

    @Test
    fun failedDeliveryRetainsStableReportAndBacksOff() {
        val store = MemoryDeliveryStore()
        val queue = RecoveryDeliveryQueue(store)
        queue.enqueue(report())

        queue.complete(ID, 1_000L, delivered = false)

        val pending = queue.snapshot().pending.single()
        assertEquals(ID, pending.report.id)
        assertEquals(1, pending.attempts)
        assertEquals(null, queue.nextDue(60_999L, enabled = true, networkReady = true))
        assertEquals(ID, queue.nextDue(61_000L, enabled = true, networkReady = true)?.report?.id)
    }

    private fun report() = RecoveryReport(
        id = ID,
        sessionId = SESSION,
        triggeredAtMillis = 100L,
        status = IncidentStatus.CONFIRMED,
        recovered = false,
        truncated = false,
        eventCount = 1,
        events = listOf(
            DiagnosticEvent(
                timestampMillis = 100L,
                monotonicMillis = 50L,
                sessionId = SESSION,
                kind = DiagnosticEventKind.RUNTIME_FAILURE,
                target = DiagnosticTarget.NONE,
                durationMillis = null,
                transport = DiagnosticTransport.HYSTERIA2,
                network = DiagnosticNetwork.WIFI,
                code = 5,
                path = DiagnosticPath.CORE,
            ),
        ),
    )

    private class MemoryDeliveryStore : RecoveryDeliveryStore {
        private var state: RecoveryDeliveryState? = null

        override fun load(): RecoveryDeliveryState? = state

        override fun save(state: RecoveryDeliveryState) {
            this.state = state
        }
    }

    private class MemoryIncidentStore : IncidentStore {
        var checkpoint: IncidentCheckpoint? = null
        val archives = mutableListOf<IncidentArchive>()
        override fun loadCheckpoint() = checkpoint
        override fun saveCheckpoint(checkpoint: IncidentCheckpoint) { this.checkpoint = checkpoint }
        override fun saveArchive(archive: IncidentArchive) { archives += archive }
        override fun listArchives() = archives.toList()
        override fun loadArchive(id: String) = archives.firstOrNull { it.id == id }
    }

    private companion object {
        const val ID = "11111111111111111111111111111111"
        const val SESSION = "22222222222222222222222222222222"
    }
}
