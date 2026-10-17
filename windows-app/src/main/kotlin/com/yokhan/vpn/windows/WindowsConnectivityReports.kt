package com.yokhan.gates.windows

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.yokhan.gates.windows.service.ConnectivityHistory
import com.yokhan.gates.windows.service.WindowsIncidentReportsReader
import java.nio.file.Files
import java.nio.file.Path

internal fun windowsConnectivityHistory(
    directory: Path? = System.getenv("ProgramData")?.takeIf(String::isNotBlank)
        ?.let { Path.of(it, "GATES", "Service", "connectivity") },
): JsonObject {
    val result = JsonObject().apply { addProperty("schema", "gates-connectivity-history-v1") }
    if (directory == null) return result.apply { addProperty("availability", "unavailable") }
    if (Files.notExists(directory)) return result.apply { addProperty("availability", "not-recorded") }
    return try {
        val events = JsonParser.parseString(ConnectivityHistory(directory).recentEvents(12_000)).asJsonArray
        result.apply {
            addProperty("availability", "available")
            add("events", events)
            add("incidents", windowsIncidentReports(directory))
        }
    } catch (_: Exception) {
        result.apply { addProperty("availability", "unavailable") }
    }
}

internal fun windowsIncidentReports(
    connectivityDirectory: Path? = defaultConnectivityDirectory(),
): JsonObject {
    val result = JsonObject().apply { addProperty("schema", "gates-incident-summary-v1") }
    val incidentsDirectory = connectivityDirectory?.resolve("incidents")
        ?: return result.apply {
            addProperty("availability", "unavailable")
            addProperty("truncated", false)
            add("archives", JsonArray())
        }
    if (Files.notExists(incidentsDirectory)) {
        return result.apply {
            addProperty("availability", "not-recorded")
            addProperty("truncated", false)
            add("archives", JsonArray())
        }
    }
    return try {
        val listing = WindowsIncidentReportsReader(incidentsDirectory).listArchives()
        val archives = JsonArray().apply {
            listing.summaries.forEach { summary ->
                add(JsonObject().apply {
                    addProperty("id", summary.id)
                    addProperty("triggered_at_millis", summary.triggeredAtMillis)
                    addProperty("status", summary.status)
                    addProperty("recovered", summary.recovered)
                    addProperty("confirmed", summary.confirmed)
                    addProperty("truncated", summary.truncated)
                    addProperty("event_count", summary.eventCount)
                    addProperty("recording", summary.recording)
                })
            }
        }
        val storageAvailability = WindowsIncidentReportsReader(incidentsDirectory).storageAvailability()
        result.apply {
            addProperty("availability", if (storageAvailability == "unavailable") "unavailable" else "available")
            addProperty("storage_availability", storageAvailability)
            addProperty("truncated", listing.truncated)
            add("archives", archives)
        }
        .also {
            if (it.toString().toByteArray(Charsets.UTF_8).size > MAX_INCIDENT_SUMMARY_BYTES) {
                it.addProperty("availability", "unavailable")
                it.addProperty("truncated", true)
                it.add("archives", JsonArray())
            }
        }
    } catch (_: Exception) {
        result.apply {
            addProperty("availability", "unavailable")
            addProperty("truncated", true)
            add("archives", JsonArray())
        }
    }
}

/** Returns only typed, allowlisted event fields; this performs local reads and no service or network action. */
internal fun windowsIncidentArchiveExport(
    id: String,
    connectivityDirectory: Path? = defaultConnectivityDirectory(),
): JsonObject {
    val result = JsonObject().apply { addProperty("schema", "gates-incident-export-v1") }
    val incidentsDirectory = connectivityDirectory?.resolve("incidents")
        ?: return result.apply { addProperty("availability", "unavailable") }
    return try {
        val export = WindowsIncidentReportsReader(incidentsDirectory).loadArchive(id)
            ?: return result.apply { addProperty("availability", "not-found") }
        result.apply {
            addProperty("availability", "available")
            addProperty("recording", export.recording)
            add("archive", JsonParser.parseString(export.archiveJson))
        }
    } catch (_: Exception) {
        result.apply { addProperty("availability", "unavailable") }
    }
}

private fun defaultConnectivityDirectory(): Path? = System.getenv("ProgramData")
    ?.takeIf(String::isNotBlank)
    ?.let { Path.of(it, "GATES", "Service", "connectivity") }

private const val MAX_INCIDENT_SUMMARY_BYTES = 8_192
