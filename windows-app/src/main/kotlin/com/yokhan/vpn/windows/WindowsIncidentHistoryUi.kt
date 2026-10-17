package com.yokhan.gates.windows

import com.google.gson.JsonObject
import com.yokhan.gates.shared.ui.diagnostics.GatesIncidentHistoryItem
import com.yokhan.gates.shared.ui.diagnostics.gatesIncidentOutcome
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun windowsIncidentHistoryItems(summary: JsonObject): List<GatesIncidentHistoryItem> {
    val entries = summary.getAsJsonArray("archives") ?: return emptyList()
    return entries.take(8).mapNotNull { entry ->
        runCatching {
            val row = entry.asJsonObject
            val id = row.get("id").asString
            require(id.matches(Regex("[a-f0-9]{32,64}")))
            val time = row.get("triggered_at_millis").asLong
            val status = row.get("status").asString.uppercase(java.util.Locale.ROOT)
            GatesIncidentHistoryItem(
                id = id,
                capturedAt = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")
                    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time)),
                outcome = gatesIncidentOutcome(status, row.get("recovered")?.asBoolean == true,
                    row.get("recording")?.asBoolean == true),
                eventCount = row.get("event_count").asInt.coerceIn(0, 2048),
                incomplete = row.get("truncated")?.asBoolean == true || status == "INTERRUPTED",
            )
        }.getOrNull()
    }
}

internal fun windowsIncidentHistoryMessage(summary: JsonObject): String =
    when (summary.get("availability")?.asString) {
        "unavailable" -> "Не удалось прочитать историю."
        "not-recorded" -> "Истории GATES пока нет."
        else -> when (summary.get("storage_availability")?.asString) {
            "quota-full" -> "Хранилище истории заполнено."
            "write-pending" -> "Не все события сохранены."
            else -> if (summary.get("truncated")?.asBoolean == true) "Часть истории недоступна." else ""
        }
    }
