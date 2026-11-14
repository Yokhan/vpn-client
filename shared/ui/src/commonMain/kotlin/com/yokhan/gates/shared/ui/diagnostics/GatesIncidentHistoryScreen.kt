package com.yokhan.gates.shared.ui.diagnostics

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanSpacePalette
import com.yokhan.gates.shared.ui.design.yokhanPalette
import com.yokhan.gates.shared.ui.route.YokhanRouteFrame
import com.yokhan.gates.shared.ui.route.YokhanRouteCard
import com.yokhan.gates.shared.ui.route.YokhanRouteButton
import com.yokhan.gates.shared.ui.route.YokhanHeadline
import com.yokhan.gates.shared.ui.route.YokhanBody

@Immutable
data class GatesIncidentHistoryItem(
    val id: String,
    val capturedAt: String,
    val outcome: String,
    val eventCount: Int,
    val incomplete: Boolean,
)

@Immutable
data class GatesIncidentHistoryLabels(
    val title: String = "История",
    val systemLabel: String = "GATES",
    val refresh: String = "Обновить",
    val loading: String = "Загрузка…",
    val empty: String = "Сохранённых сбоев пока нет.",
    val eventCountPrefix: String = "Событий",
    val incomplete: String = "Запись неполная",
    val exportReport: String = "Экспорт отчёта",
    val showMore: String = "Показать ещё",
) {
    companion object {
        val Russian = GatesIncidentHistoryLabels()
    }
}

fun gatesIncidentOutcome(status: String, recovered: Boolean, recording: Boolean): String = when {
    recording -> "Сбой записывается"
    recovered -> "Связь восстановилась"
    status.uppercase() == "INTERRUPTED" -> "Запись прервана"
    status.uppercase() == "ENDED" -> "Подключение завершено"
    status.uppercase() == "CONFIRMED" -> "Обнаружен сбой"
    else -> "Возможный сбой"
}

/** Platform adapters own local reads and exports; this screen cannot control a tunnel. */
@Composable
fun GatesIncidentHistoryScreen(
    items: List<GatesIncidentHistoryItem>,
    loading: Boolean,
    message: String,
    palette: YokhanPalette = yokhanPalette(spaceActive = false),
    spaceActive: Boolean = false,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onExport: (String) -> Unit,
    modifier: Modifier = Modifier,
    pageSize: Int = 8,
    labels: GatesIncidentHistoryLabels = GatesIncidentHistoryLabels.Russian,
) {
    val effectivePageSize = pageSize.coerceAtLeast(1)
    var visibleCount by rememberSaveable(effectivePageSize) { mutableStateOf(effectivePageSize) }
    val effectiveSpaceActive = spaceActive || palette.background == YokhanSpacePalette.background
    val visibleItems = items.take(visibleCount)
    YokhanRouteFrame(
        title = labels.title,
        systemLabel = labels.systemLabel,
        spaceActive = effectiveSpaceActive,
        onMenuClick = onBack,
        onBack = onBack,
        modifier = modifier,
    ) { colors, typography ->
        YokhanRouteButton(
            labels.refresh,
            false,
            !loading,
            colors,
            typography,
            onRefresh,
            Modifier.fillMaxWidth().testTag("history.refresh"),
        )
        if (message.isNotBlank()) YokhanBody(message, colors, typography)
        if (loading) {
            YokhanBody(labels.loading, colors, typography)
        } else if (items.isEmpty() && message.isBlank()) {
            YokhanBody(labels.empty, colors, typography)
        }
        visibleItems.forEach { item ->
            YokhanRouteCard(item.capturedAt, colors, typography) {
                YokhanHeadline(item.outcome, colors, typography)
                YokhanBody("${labels.eventCountPrefix}: ${item.eventCount}", colors, typography)
                if (item.incomplete) YokhanBody(labels.incomplete, colors, typography)
                YokhanRouteButton(
                    labels.exportReport,
                    false,
                    !loading,
                    colors,
                    typography,
                    { onExport(item.id) },
                    Modifier.fillMaxWidth().testTag("history.export.${item.id}"),
                )
            }
        }
        val remaining = items.size - visibleItems.size
        if (remaining > 0) {
            YokhanRouteButton(
                "${labels.showMore} ($remaining)",
                false,
                !loading,
                colors,
                typography,
                { visibleCount += effectivePageSize },
                Modifier.fillMaxWidth().testTag("history.show-more"),
            )
        }
    }
}
