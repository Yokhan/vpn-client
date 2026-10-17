package com.yokhan.gates.shared.ui.diagnostics

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.yokhan.gates.shared.ui.design.YokhanPalette
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
    palette: YokhanPalette,
    spaceActive: Boolean = false,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onExport: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    YokhanRouteFrame(
        title = "История", systemLabel = "GATES", spaceActive = spaceActive,
        onMenuClick = onBack, onBack = onBack, modifier = modifier,
    ) { colors, typography ->
        YokhanRouteButton("Обновить", false, !loading, colors, typography, onRefresh,
            Modifier.fillMaxWidth().testTag("history.refresh"))
        if (message.isNotBlank()) YokhanBody(message, colors, typography)
        if (loading) {
            YokhanBody("Загрузка…", colors, typography)
        } else if (items.isEmpty() && message.isBlank()) {
            YokhanBody("Сохранённых сбоев пока нет.", colors, typography)
        }
        items.take(8).forEach { item ->
            YokhanRouteCard(item.capturedAt, colors, typography) {
                YokhanHeadline(item.outcome, colors, typography)
                YokhanBody("Событий: ${item.eventCount}", colors, typography)
                if (item.incomplete) YokhanBody("Запись неполная", colors, typography)
                YokhanRouteButton("Экспорт отчёта", false, !loading, colors, typography,
                    { onExport(item.id) }, Modifier.fillMaxWidth().testTag("history.export.${item.id}"))
            }
        }
    }
}
