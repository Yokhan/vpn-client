package com.yokhan.gates.shared.ui.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.sp
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import com.yokhan.gates.shared.ui.design.yokhanPalette
import com.yokhan.gates.shared.ui.route.YokhanBody
import com.yokhan.gates.shared.ui.route.YokhanAdaptiveActionPair
import com.yokhan.gates.shared.ui.route.YokhanCaption
import com.yokhan.gates.shared.ui.route.YokhanMonoValue
import com.yokhan.gates.shared.ui.route.YokhanRouteButton
import com.yokhan.gates.shared.ui.route.YokhanRouteCard
import com.yokhan.gates.shared.ui.route.YokhanRouteFrame
import com.yokhan.gates.shared.ui.route.yokhanOutlinedSurface

@Composable
fun GatesAccessScreen(
    signedIn: Boolean,
    busy: Boolean,
    message: String,
    manualInput: String,
    onManualInputChanged: (String) -> Unit,
    onLogin: () -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
    onBugReport: () -> Unit,
    modifier: Modifier = Modifier,
    colors: YokhanPalette = yokhanPalette(spaceActive = false),
    canCancel: Boolean = false,
    onHistory: (() -> Unit)? = null,
    authorizationCode: String = "",
    authorizationPending: Boolean = false,
    onOpenAuthorization: (() -> Unit)? = null,
    onCancelAuthorization: (() -> Unit)? = null,
) {
    var manualMode by rememberSaveable { mutableStateOf(false) }
    val back: (() -> Unit)? = when {
        busy -> null
        manualMode -> ({ manualMode = false })
        canCancel -> onCancel
        else -> null
    }
    YokhanRouteFrame(
        title = if (canCancel) "Доступ" else "GATES",
        systemLabel = "GATES",
        spaceActive = false,
        onMenuClick = { back?.invoke() },
        onBack = back,
        showMenu = false,
        modifier = modifier.onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape && back != null) {
                back(); true
            } else false
        },
    ) { palette, typography ->
        if (message.isNotBlank()) YokhanBody(message, palette, typography)
        if (authorizationPending) {
            YokhanRouteCard("Вход через браузер", palette, typography) {
                if (authorizationCode.isNotBlank()) YokhanMonoValue(authorizationCode, palette, typography)
                YokhanAdaptiveActionPair(first = { actionModifier ->
                    if (onOpenAuthorization != null) YokhanRouteButton("Открыть браузер", true, true,
                        palette, typography, onOpenAuthorization, actionModifier.testTag("form.action.open-auth"))
                }, second = { actionModifier ->
                    if (onCancelAuthorization != null) YokhanRouteButton("Отменить вход", false, true,
                        palette, typography, onCancelAuthorization, actionModifier.testTag("form.action.cancel-auth"))
                })
            }
        }
        YokhanRouteCard(if (manualMode) "Подписка" else "Аккаунт", palette, typography) {
            if (manualMode) {
                YokhanCaption("Ссылка подписки GATES", palette, typography)
                BasicTextField(
                    value = manualInput,
                    onValueChange = onManualInputChanged,
                    enabled = !busy,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    cursorBrush = SolidColor(palette.accent),
                    textStyle = TextStyle(color = palette.primary, fontFamily = typography.mono,
                        fontSize = 13.sp, lineHeight = 18.sp),
                    modifier = Modifier.fillMaxWidth().testTag("form.input.manual-input")
                        .padding(vertical = YokhanSpacing.CompactGap)
                        .yokhanOutlinedSurface(palette).padding(YokhanSpacing.ControlHorizontal),
                )
                YokhanRouteButton("Добавить подписку", true, !busy && manualInput.isNotBlank(),
                    palette, typography, onImport,
                    Modifier.fillMaxWidth().testTag("form.action.import"))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                    YokhanRouteButton(if (signedIn) "Обновить доступ" else "Войти через браузер",
                        true, !busy, palette, typography, onLogin,
                        Modifier.fillMaxWidth().testTag("form.action.login"))
                    YokhanRouteButton("Добавить подписку", false, !busy, palette, typography,
                        { manualMode = true }, Modifier.fillMaxWidth().testTag("form.action.manual"))
                }
            }
        }
        YokhanRouteCard("Поддержка", palette, typography) {
            YokhanAdaptiveActionPair(first = { actionModifier ->
                if (onHistory != null) YokhanRouteButton("История соединения", false, !busy,
                    palette, typography, onHistory,
                    actionModifier.testTag("form.action.incident-history"))
            }, second = { actionModifier ->
                YokhanRouteButton("Сообщить об ошибке", false, !busy, palette, typography, onBugReport,
                    actionModifier.testTag("form.action.bug-report"))
            })
        }
    }
}
