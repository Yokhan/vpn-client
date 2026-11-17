package com.yokhan.gates.shared.ui.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import com.yokhan.gates.shared.ui.design.YokhanSpacePalette
import com.yokhan.gates.shared.ui.design.yokhanPalette
import com.yokhan.gates.shared.ui.route.YokhanAdaptiveActionPair
import com.yokhan.gates.shared.ui.route.YokhanBody
import com.yokhan.gates.shared.ui.route.YokhanCaption
import com.yokhan.gates.shared.ui.route.YokhanMonoValue
import com.yokhan.gates.shared.ui.route.YokhanRouteButton
import com.yokhan.gates.shared.ui.route.YokhanRouteCard
import com.yokhan.gates.shared.ui.route.YokhanRouteFrame
import com.yokhan.gates.shared.ui.route.yokhanOutlinedSurface

@Immutable
data class GatesAccessUiLabels(
    val brandTitle: String = "GATES",
    val loginTitle: String = "Вход",
    val accessTitle: String = "Доступ",
    val manualTitle: String = "Подписка",
    val browserCardTitle: String = "Вход через QR-код или браузер",
    val qrHint: String = "Отсканируйте QR-код камерой телефона (где работает GATES или интернет) — или подтвердите вход в браузере на этом ПК.",
    val copyAuthLink: String = "Скопировать ссылку",
    val openBrowser: String = "Открыть браузер",
    val cancelBrowser: String = "Отменить вход",
    val accountCardTitle: String = "Аккаунт",
    val manualCaption: String = "Ссылка подписки GATES",
    val manualPlaceholder: String = "https://…",
    val bootstrapTitle: String = "Мудрое Древо · Разовый доступ",
    val bootstrapCaption: String = "Пароль Древа",
    val bootstrapPlaceholder: String = "Произнеси пароль…",
    val bootstrapAction: String = "Произнести пароль",
    val bootstrapChallengeTrigger: String = "Пройти испытание Древа (15 мин)",
    val bootstrapHint: String = "Перед тобой стоит Мудрое Древо. Из пустоты доносится шёпот:\n«Эта дверь открывается лишь ищущему знания… Произнеси пароль, Сладкий.»",
    val bootstrapScopeNote: String = "Откроет только вход в GATES и получение вашего ключа на 15 минут. Остальной интернет останется закрыт.",
    val bootstrapRemainingLabel: String = "Проход Древа открыт",
    val showSecret: String = "Показать",
    val hideSecret: String = "Скрыть",
    val addSubscription: String = "Добавить подписку",
    val loginBrowser: String = "Войти через браузер / QR",
    val requestAccess: String = "Запросить доступ",
    val checkStatus: String = "Проверить статус",
    val checkAccess: String = "Проверить доступ",
    val retryConfig: String = "Повторить получение",
    val refreshAccess: String = "Обновить доступ",
    val restartLogin: String = "Войти заново (QR / браузер)",
    val signOut: String = "Выйти из аккаунта",
    val supportCardTitle: String = "Поддержка",
    val historyAction: String = "История соединения",
    val bugReportAction: String = "Сообщить об ошибке",
    val accountPrefix: String = "Аккаунт",
) {
    companion object {
        val Russian = GatesAccessUiLabels()
    }
}

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
    spaceActive: Boolean = colors.background == YokhanSpacePalette.background,
    canCancel: Boolean = false,
    onHistory: (() -> Unit)? = null,
    authorizationCode: String = "",
    authorizationUri: String = "",
    authorizationPending: Boolean = false,
    onOpenAuthorization: (() -> Unit)? = null,
    onCopyAuthorizationUri: (() -> Unit)? = null,
    onCancelAuthorization: (() -> Unit)? = null,
    profileReady: Boolean = true,
    accountLabel: String = "",
    accessPending: Boolean = false,
    onRetryConfig: (() -> Unit)? = null,
    onRestartLogin: (() -> Unit)? = null,
    onSignOut: (() -> Unit)? = null,
    onRequestAccess: (() -> Unit)? = null,
    onBootstrapClaim: ((String) -> Unit)? = null,
    bootstrapBusy: Boolean = false,
    bootstrapMessage: String = "",
    bootstrapRemaining: String = "",
    suggestBootstrap: Boolean = false,
    labels: GatesAccessUiLabels = GatesAccessUiLabels.Russian,
) {
    var manualMode by rememberSaveable { mutableStateOf(false) }
    var secretVisible by rememberSaveable { mutableStateOf(false) }
    var bootstrapInput by rememberSaveable { mutableStateOf("") }
    var bootstrapSecretVisible by rememberSaveable { mutableStateOf(false) }
    var bootstrapExpanded by rememberSaveable { mutableStateOf(false) }
    val back: (() -> Unit)? = when {
        busy -> null
        manualMode -> ({ manualMode = false })
        canCancel -> onCancel
        else -> null
    }
    val screenTitle = when {
        manualMode -> labels.manualTitle
        !signedIn -> labels.loginTitle
        canCancel -> labels.accessTitle
        else -> labels.brandTitle
    }
    YokhanRouteFrame(
        title = screenTitle,
        systemLabel = labels.brandTitle,
        spaceActive = spaceActive,
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
            YokhanRouteCard(labels.browserCardTitle, palette, typography) {
                if (authorizationUri.isNotBlank() && !authorizationUri.contains("127.0.0.1")) {
                    YokhanCaption(labels.qrHint, palette, typography)
                    YokhanQrCode(
                        content = authorizationUri,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                            .padding(vertical = YokhanSpacing.CompactGap),
                    )
                }
                if (authorizationCode.isNotBlank()) YokhanMonoValue(authorizationCode, palette, typography)
                YokhanAdaptiveActionPair(first = { actionModifier ->
                    if (onOpenAuthorization != null) YokhanRouteButton(labels.openBrowser, true, true,
                        palette, typography, onOpenAuthorization, actionModifier.testTag("form.action.open-auth"))
                }, second = { actionModifier ->
                    if (onCancelAuthorization != null) YokhanRouteButton(labels.cancelBrowser, false, true,
                        palette, typography, onCancelAuthorization, actionModifier.testTag("form.action.cancel-auth"))
                })
                if (onCopyAuthorizationUri != null && authorizationUri.isNotBlank()) {
                    YokhanRouteButton(
                        labels.copyAuthLink,
                        false,
                        true,
                        palette,
                        typography,
                        onCopyAuthorizationUri,
                        Modifier.fillMaxWidth().testTag("form.action.copy-auth-uri"),
                    )
                }
            }
        }
        if (onBootstrapClaim != null) {
            val showBootstrapBody = bootstrapExpanded || suggestBootstrap || bootstrapMessage.isNotBlank() || bootstrapRemaining.isNotBlank()
            YokhanRouteCard(labels.bootstrapTitle, palette, typography) {
                YokhanCaption(labels.bootstrapScopeNote, palette, typography)
                if (bootstrapRemaining.isNotBlank()) {
                    YokhanMonoValue(
                        "${labels.bootstrapRemainingLabel}: $bootstrapRemaining",
                        palette,
                        typography,
                    )
                }
                if (!showBootstrapBody) {
                    YokhanRouteButton(
                        text = labels.bootstrapChallengeTrigger,
                        primary = false,
                        enabled = !busy && !bootstrapBusy,
                        colors = palette,
                        typography = typography,
                        onClick = { bootstrapExpanded = true },
                        modifier = Modifier.fillMaxWidth().testTag("form.action.bootstrap-open"),
                    )
                } else {
                    YokhanBody(labels.bootstrapHint, palette, typography)
                    if (bootstrapMessage.isNotBlank()) {
                        YokhanBody(bootstrapMessage, palette, typography)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        YokhanCaption(labels.bootstrapCaption, palette, typography)
                        YokhanRouteButton(
                            text = if (bootstrapSecretVisible) labels.hideSecret else labels.showSecret,
                            primary = false,
                            enabled = !busy && !bootstrapBusy,
                            colors = palette,
                            typography = typography,
                            onClick = { bootstrapSecretVisible = !bootstrapSecretVisible },
                            modifier = Modifier.testTag("form.action.toggle-bootstrap-visibility"),
                        )
                    }
                    val submitBootstrap: () -> Unit = {
                        val value = bootstrapInput.trim()
                        if (!busy && !bootstrapBusy && value.isNotEmpty()) {
                            bootstrapInput = ""
                            onBootstrapClaim(value)
                        }
                    }
                    BasicTextField(
                        value = bootstrapInput,
                        onValueChange = { bootstrapInput = it },
                        enabled = !busy && !bootstrapBusy,
                        singleLine = true,
                        visualTransformation = if (bootstrapSecretVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        cursorBrush = SolidColor(palette.accent),
                        textStyle = TextStyle(
                            color = palette.primary,
                            fontFamily = typography.mono,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        ),
                        decorationBox = { innerTextField ->
                            Box(Modifier.fillMaxWidth()) {
                                if (bootstrapInput.isEmpty()) {
                                    Text(
                                        text = labels.bootstrapPlaceholder,
                                        color = palette.tertiary,
                                        fontFamily = typography.mono,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                    )
                                }
                                innerTextField()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                                    submitBootstrap()
                                    true
                                } else false
                            }
                            .testTag("form.input.bootstrap-password")
                            .padding(vertical = YokhanSpacing.CompactGap)
                            .yokhanOutlinedSurface(palette).padding(YokhanSpacing.ControlHorizontal),
                    )
                    YokhanRouteButton(
                        labels.bootstrapAction,
                        true,
                        !busy && !bootstrapBusy && bootstrapInput.isNotBlank(),
                        palette,
                        typography,
                        submitBootstrap,
                        Modifier.fillMaxWidth().testTag("form.action.bootstrap"),
                    )
                }
            }
        }
        YokhanRouteCard(if (manualMode) labels.manualTitle else labels.accountCardTitle, palette, typography) {
            if (manualMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    YokhanCaption(labels.manualCaption, palette, typography)
                    YokhanRouteButton(
                        text = if (secretVisible) labels.hideSecret else labels.showSecret,
                        primary = false,
                        enabled = !busy,
                        colors = palette,
                        typography = typography,
                        onClick = { secretVisible = !secretVisible },
                        modifier = Modifier.testTag("form.action.toggle-manual-visibility"),
                    )
                }
                BasicTextField(
                    value = manualInput,
                    onValueChange = onManualInputChanged,
                    enabled = !busy,
                    singleLine = true,
                    visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    cursorBrush = SolidColor(palette.accent),
                    textStyle = TextStyle(color = palette.primary, fontFamily = typography.mono,
                        fontSize = 13.sp, lineHeight = 18.sp),
                    decorationBox = { innerTextField ->
                        Box(Modifier.fillMaxWidth()) {
                            if (manualInput.isEmpty()) {
                                Text(
                                    text = labels.manualPlaceholder,
                                    color = palette.tertiary,
                                    fontFamily = typography.mono,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                )
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("form.input.manual-input")
                        .padding(vertical = YokhanSpacing.CompactGap)
                        .yokhanOutlinedSurface(palette).padding(YokhanSpacing.ControlHorizontal),
                )
                YokhanRouteButton(labels.addSubscription, true, !busy && manualInput.isNotBlank(),
                    palette, typography, onImport,
                    Modifier.fillMaxWidth().testTag("form.action.import"))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                    if (signedIn && accountLabel.isNotBlank()) {
                        YokhanCaption("${labels.accountPrefix}: $accountLabel", palette, typography)
                    }
                    if (!signedIn) {
                        YokhanRouteButton(labels.loginBrowser, true, !busy, palette, typography, onLogin,
                            Modifier.fillMaxWidth().testTag("form.action.login"))
                    } else {
                        val retry = onRetryConfig ?: onLogin
                        if (onRequestAccess != null && !accessPending) {
                            YokhanRouteButton(labels.requestAccess, true, !busy, palette, typography,
                                onRequestAccess, Modifier.fillMaxWidth().testTag("form.action.request-access"))
                            YokhanRouteButton(if (accessPending) labels.checkStatus else labels.checkAccess,
                                false, !busy, palette, typography, retry,
                                Modifier.fillMaxWidth().testTag("form.action.retry-config"))
                        } else {
                            val label = when {
                                accessPending -> labels.checkStatus
                                !profileReady -> labels.retryConfig
                                else -> labels.refreshAccess
                            }
                            val tag = if (profileReady && !accessPending) "form.action.login"
                                else "form.action.retry-config"
                            YokhanRouteButton(label, true, !busy, palette, typography, retry,
                                Modifier.fillMaxWidth().testTag(tag))
                        }
                        if (onRestartLogin != null) {
                            YokhanRouteButton(labels.restartLogin, false, !busy, palette, typography,
                                onRestartLogin, Modifier.fillMaxWidth().testTag("form.action.restart-login"))
                        }
                        if (onSignOut != null) {
                            YokhanRouteButton(labels.signOut, false, !busy, palette, typography,
                                onSignOut, Modifier.fillMaxWidth().testTag("form.action.sign-out"))
                        }
                    }
                    YokhanRouteButton(labels.addSubscription, false, !busy, palette, typography,
                        { manualMode = true }, Modifier.fillMaxWidth().testTag("form.action.manual"))
                }
            }
        }
        YokhanRouteCard(labels.supportCardTitle, palette, typography) {
            YokhanAdaptiveActionPair(first = { actionModifier ->
                if (onHistory != null) YokhanRouteButton(labels.historyAction, false, !busy,
                    palette, typography, onHistory,
                    actionModifier.testTag("form.action.incident-history"))
            }, second = { actionModifier ->
                YokhanRouteButton(labels.bugReportAction, false, !busy, palette, typography, onBugReport,
                    actionModifier.testTag("form.action.bug-report"))
            })
        }
    }
}
