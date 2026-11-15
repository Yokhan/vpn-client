package com.yokhan.gates.app.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yokhan.gates.shared.ui.design.YokhanMotion
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanTypography
import com.yokhan.gates.shared.ui.route.YokhanActionRow
import com.yokhan.gates.shared.ui.route.YokhanDivider
import com.yokhan.gates.shared.ui.route.YokhanRouteButton
import com.yokhan.gates.shared.ui.route.YokhanRouteCard
import io.nekohasekai.sagernet.R

data class YokhanActionSheetItem(
    val title: String,
    val subtitle: String? = null,
    val accent: Boolean = false,
    val onClick: () -> Unit,
)

data class YokhanActionSheetState(
    val title: String,
    val items: List<YokhanActionSheetItem>,
)

@Composable
fun YokhanActionSheetDialog(
    state: YokhanActionSheetState?,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onDismiss: () -> Unit,
) {
    if (state == null || state.items.isEmpty()) return
    var appeared by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state) { appeared = true }
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(160, easing = YokhanMotion.CssEaseOut),
        label = "action-dialog-alpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.96f,
        animationSpec = tween(180, easing = YokhanMotion.CssEaseOut),
        label = "action-dialog-scale",
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        YokhanRouteCard(
            title = state.title,
            colors = colors,
            typography = typography,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .graphicsLayer {
                    this.alpha = alpha
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                state.items.forEachIndexed { index, item ->
                    if (index > 0) {
                        YokhanDivider(colors, Modifier.padding(vertical = 4.dp))
                    }
                    YokhanActionRow(
                        title = item.title,
                        subtitle = item.subtitle,
                        accent = item.accent,
                        colors = colors,
                        typography = typography,
                        onClick = {
                            onDismiss()
                            item.onClick()
                        },
                    )
                }
            }
            YokhanRouteButton(
                text = stringResource(R.string.yokhan_dialog_cancel),
                primary = false,
                enabled = true,
                colors = colors,
                typography = typography,
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
        }
    }
}
