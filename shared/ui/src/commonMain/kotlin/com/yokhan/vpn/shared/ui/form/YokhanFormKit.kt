package com.yokhan.gates.shared.ui.form

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yokhan.gates.shared.ui.design.YokhanAccent
import com.yokhan.gates.shared.ui.design.YokhanGreen
import com.yokhan.gates.shared.ui.design.YokhanMotion
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanRadius
import com.yokhan.gates.shared.ui.design.YokhanRed
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import com.yokhan.gates.shared.ui.design.YokhanStroke
import com.yokhan.gates.shared.ui.design.YokhanTypography
import com.yokhan.gates.shared.ui.design.yokhanPalette
import com.yokhan.gates.shared.ui.design.yokhanTypography

@Composable
fun YokhanFormScreen(
    state: YokhanFormState,
    onEvent: (YokhanFormEvent) -> Unit,
    modifier: Modifier = Modifier,
    colors: YokhanPalette = yokhanPalette(spaceActive = true),
    typography: YokhanTypography = yokhanTypography(),
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = YokhanSpacing.CardPadding,
                vertical = YokhanSpacing.Icon,
            ),
        verticalArrangement = Arrangement.spacedBy(YokhanSpacing.ControlHorizontal),
    ) {
        YokhanFormHeader(state, colors, typography)
        if (state.loading) {
            YokhanFormLoadingCard(colors, typography)
        } else {
            state.sections.filter { section -> section.fields.any { it.visible } }.forEach { section ->
                YokhanFormSectionCard(
                    section = section,
                    colors = colors,
                    typography = typography,
                    readOnly = state.readOnly || state.saving,
                    onEvent = onEvent,
                )
            }
        }
        YokhanFormActionBar(
            state = state,
            colors = colors,
            typography = typography,
            onEvent = onEvent,
        )
        Spacer(Modifier.height(YokhanSpacing.ScreenHorizontal))
    }
}

@Composable
fun YokhanFormSectionCard(
    section: YokhanFormSection,
    colors: YokhanPalette,
    typography: YokhanTypography,
    readOnly: Boolean,
    onEvent: (YokhanFormEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(YokhanStroke.Thin, colors.border, RoundedCornerShape(YokhanRadius.Card))
            .padding(YokhanSpacing.ControlHorizontal),
        verticalArrangement = Arrangement.spacedBy(YokhanSpacing.CardTitleGap),
    ) {
        YokhanFormSectionLabel(section.title, colors, typography)
        section.description?.let {
            YokhanFormCaption(
                text = it,
                colors = colors,
                typography = typography,
            )
        }
        section.fields.filter { it.visible }.forEachIndexed { index, field ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(YokhanStroke.Thin)
                        .background(colors.divider),
                )
            }
            when (field) {
                is YokhanFormField.Text -> YokhanFormTextFieldRow(
                    id = field.id,
                    label = field.label,
                    value = field.value,
                    placeholder = field.placeholder,
                    helper = field.helper,
                    enabled = field.enabled,
                    required = field.required,
                    validation = field.validation,
                    mode = field.mode,
                    readOnly = readOnly,
                    colors = colors,
                    typography = typography,
                    onEvent = onEvent,
                )

                is YokhanFormField.Password -> YokhanFormTextFieldRow(
                    id = field.id,
                    label = field.label,
                    value = field.value,
                    placeholder = field.placeholder,
                    helper = field.helper,
                    enabled = field.enabled,
                    required = field.required,
                    validation = field.validation,
                    mode = YokhanFormTextMode.Text,
                    password = true,
                    readOnly = readOnly,
                    colors = colors,
                    typography = typography,
                    onEvent = onEvent,
                )

                is YokhanFormField.Number -> YokhanFormTextFieldRow(
                    id = field.id,
                    label = field.label,
                    value = field.value,
                    placeholder = field.placeholder,
                    helper = field.helper,
                    enabled = field.enabled,
                    required = field.required,
                    validation = field.validation,
                    mode = YokhanFormTextMode.Number,
                    readOnly = readOnly,
                    colors = colors,
                    typography = typography,
                    onEvent = onEvent,
                )

                is YokhanFormField.Port -> YokhanFormTextFieldRow(
                    id = field.id,
                    label = field.label,
                    value = field.value,
                    placeholder = field.placeholder,
                    helper = field.helper,
                    enabled = field.enabled,
                    required = field.required,
                    validation = field.validation,
                    mode = YokhanFormTextMode.Port,
                    readOnly = readOnly,
                    colors = colors,
                    typography = typography,
                    onEvent = onEvent,
                )

                is YokhanFormField.Switch -> YokhanFormSwitchRow(
                    field = field,
                    readOnly = readOnly,
                    colors = colors,
                    typography = typography,
                    onEvent = onEvent,
                )

                is YokhanFormField.Select -> YokhanFormSelectRow(
                    field = field,
                    readOnly = readOnly,
                    colors = colors,
                    typography = typography,
                    onEvent = onEvent,
                )

                is YokhanFormField.Info -> YokhanFormInfoRow(
                    field = field,
                    colors = colors,
                    typography = typography,
                )

                is YokhanFormField.Action -> YokhanFormActionRow(
                    field = field,
                    readOnly = readOnly,
                    colors = colors,
                    typography = typography,
                    onEvent = onEvent,
                )
            }
        }
    }
}

@Composable
private fun YokhanFormHeader(
    state: YokhanFormState,
    colors: YokhanPalette,
    typography: YokhanTypography,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = state.title,
            color = colors.primary,
            fontFamily = typography.text,
            fontSize = 24.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        state.subtitle?.let {
            Text(
                text = it,
                color = colors.secondary,
                fontFamily = typography.mono,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun YokhanFormLoadingCard(
    colors: YokhanPalette,
    typography: YokhanTypography,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .border(YokhanStroke.Thin, colors.border, RoundedCornerShape(YokhanRadius.Card)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "LOADING...",
            color = colors.secondary,
            fontFamily = typography.mono,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
    }
}

@Composable
private fun YokhanFormTextFieldRow(
    id: String,
    label: String,
    value: String,
    placeholder: String?,
    helper: String?,
    enabled: Boolean,
    required: Boolean,
    validation: YokhanFormValidation,
    mode: YokhanFormTextMode,
    readOnly: Boolean,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onEvent: (YokhanFormEvent) -> Unit,
    password: Boolean = false,
) {
    val canEdit = enabled && !readOnly
    Column(
        modifier = Modifier.alpha(if (enabled) 1f else 0.48f),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        YokhanFormFieldLabel(label, required, colors, typography)
        YokhanFieldFrame(colors = colors, enabled = canEdit) {
            BasicTextField(
                value = value,
                onValueChange = { onEvent(YokhanFormEvent.TextChanged(id, it)) },
                enabled = canEdit,
                singleLine = mode != YokhanFormTextMode.Multiline,
                cursorBrush = SolidColor(colors.accent),
                visualTransformation = if (password) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                textStyle = TextStyle(
                    color = colors.primary,
                    fontFamily = typography.mono,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier.fillMaxWidth().testTag("form.input.$id"),
                decorationBox = { input ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = if (mode == YokhanFormTextMode.Multiline) 86.dp else 42.dp)
                            .padding(
                                horizontal = YokhanSpacing.ControlHorizontal,
                                vertical = YokhanSpacing.ControlVertical,
                            ),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isBlank() && placeholder != null) {
                            Text(
                                text = placeholder,
                                color = colors.tertiary,
                                fontFamily = typography.mono,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        input()
                    }
                },
            )
        }
        YokhanFormSupportText(helper, validation, colors, typography)
    }
}

@Composable
private fun YokhanFormSwitchRow(
    field: YokhanFormField.Switch,
    readOnly: Boolean,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onEvent: (YokhanFormEvent) -> Unit,
) {
    val canChange = field.enabled && !readOnly
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (field.enabled) 1f else 0.48f)
            .clickable(enabled = canChange) {
                onEvent(YokhanFormEvent.SwitchChanged(field.id, !field.checked))
            }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            YokhanFormFieldLabel(field.label, field.required, colors, typography)
            field.helper?.let { YokhanFormCaption(it, colors, typography) }
            YokhanFormValidationText(field.validation, colors, typography)
        }
        YokhanFormToggle(
            checked = field.checked,
            enabled = canChange,
            colors = colors,
        )
    }
}

@Composable
private fun YokhanFormSelectRow(
    field: YokhanFormField.Select,
    readOnly: Boolean,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onEvent: (YokhanFormEvent) -> Unit,
) {
    val canChange = field.enabled && !readOnly
    Column(
        modifier = Modifier.alpha(if (field.enabled) 1f else 0.48f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        YokhanFormFieldLabel(field.label, field.required, colors, typography)
        field.helper?.let { YokhanFormCaption(it, colors, typography) }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            field.options.forEach { option ->
                val selected = option.id == field.selectedOptionId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(YokhanRadius.Panel))
                        .background(if (selected) colors.accent.copy(alpha = 0.14f) else Color.Transparent)
                        .border(
                            YokhanStroke.Thin,
                            if (selected) colors.accent.copy(alpha = 0.72f) else colors.border,
                            RoundedCornerShape(YokhanRadius.Panel),
                        )
                        .clickable(enabled = canChange) {
                            onEvent(YokhanFormEvent.OptionSelected(field.id, option.id))
                        }
                        .padding(
                            horizontal = YokhanSpacing.InlineGap,
                            vertical = YokhanSpacing.ScreenTop,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (selected) colors.accent else colors.tertiary.copy(alpha = 0.28f)),
                    )
                    Column(
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = option.label,
                            color = colors.primary,
                            fontFamily = typography.mono,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        option.description?.let {
                            Text(
                                text = it,
                                color = colors.secondary,
                                fontFamily = typography.mono,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        YokhanFormValidationText(field.validation, colors, typography)
    }
}

@Composable
private fun YokhanFormInfoRow(
    field: YokhanFormField.Info,
    colors: YokhanPalette,
    typography: YokhanTypography,
) {
    Column(
        modifier = Modifier.alpha(if (field.enabled) 1f else 0.48f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        YokhanFormFieldLabel(field.label, field.required, colors, typography)
        Text(
            text = field.value,
            color = colors.secondary,
            fontFamily = typography.mono,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        field.helper?.let { YokhanFormCaption(it, colors, typography) }
        YokhanFormValidationText(field.validation, colors, typography)
    }
}

@Composable
private fun YokhanFormActionRow(
    field: YokhanFormField.Action,
    readOnly: Boolean,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onEvent: (YokhanFormEvent) -> Unit,
) {
    val canOpen = field.enabled && !readOnly
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (field.enabled) 1f else 0.48f)
            .clip(RoundedCornerShape(YokhanRadius.Panel))
            .background(colors.primary.copy(alpha = 0.028f))
            .border(YokhanStroke.Thin, colors.border, RoundedCornerShape(YokhanRadius.Panel))
            .clickable(enabled = canOpen) {
                onEvent(YokhanFormEvent.ActionInvoked(field.id))
            }
            .padding(
                horizontal = YokhanSpacing.ControlHorizontal,
                vertical = YokhanSpacing.ControlVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            YokhanFormFieldLabel(field.label, field.required, colors, typography, maxLines = 3)
            field.value?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = colors.secondary,
                    fontFamily = typography.mono,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            field.helper?.let { YokhanFormCaption(it, colors, typography) }
            YokhanFormValidationText(field.validation, colors, typography)
        }
        Text(
            text = field.actionLabel,
            color = if (canOpen) colors.accent else colors.tertiary,
            fontFamily = typography.mono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun YokhanFormActionBar(
    state: YokhanFormState,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onEvent: (YokhanFormEvent) -> Unit,
) {
    val busy = state.loading || state.saving
    val primaryEnabled = state.dirty && !busy && !state.readOnly && !state.hasBlockingValidationErrors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        YokhanFormActionButton(
            text = state.secondaryAction.label(),
            primary = false,
            enabled = !busy,
            colors = colors,
            typography = typography,
            onClick = { onEvent(state.secondaryAction.event()) },
            modifier = Modifier.weight(1f),
        )
        YokhanFormActionButton(
            text = if (state.saving) "SAVING..." else state.primaryAction.label(),
            primary = true,
            enabled = primaryEnabled,
            colors = colors,
            typography = typography,
            onClick = { onEvent(state.primaryAction.event()) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun YokhanFormActionButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (primary) colors.accent.copy(alpha = 0.72f) else colors.border
    val background = if (primary) colors.accent.copy(alpha = 0.16f) else Color.Transparent
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(YokhanRadius.Pill))
            .background(background)
            .border(YokhanStroke.Thin, borderColor, RoundedCornerShape(YokhanRadius.Pill))
            .alpha(if (enabled) 1f else 0.44f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (primary) colors.accent else colors.secondary,
            fontFamily = typography.mono,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.45.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun YokhanFieldFrame(
    colors: YokhanPalette,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(YokhanRadius.Panel))
            .background(colors.primary.copy(alpha = if (enabled) 0.028f else 0.015f))
            .border(YokhanStroke.Thin, colors.border, RoundedCornerShape(YokhanRadius.Panel)),
    ) {
        content()
    }
}

@Composable
private fun YokhanFormToggle(
    checked: Boolean,
    enabled: Boolean,
    colors: YokhanPalette,
) {
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        animationSpec = tween(180, easing = YokhanMotion.CssEaseOut),
        label = "form-toggle-knob",
    )
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(YokhanRadius.Pill))
            .background(if (checked) colors.accent.copy(alpha = 0.18f) else colors.primary.copy(alpha = 0.035f))
            .border(
                YokhanStroke.Thin,
                if (checked) colors.accent.copy(alpha = 0.65f) else colors.border,
                RoundedCornerShape(YokhanRadius.Pill),
            )
            .alpha(if (enabled) 1f else 0.58f),
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobOffset, y = 2.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (checked) colors.accent else colors.tertiary),
        )
    }
}

@Composable
private fun YokhanFormSectionLabel(
    text: String,
    colors: YokhanPalette,
    typography: YokhanTypography,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .offset(y = (-0.5f).dp)
                .size(6.dp)
                .clip(RoundedCornerShape(YokhanRadius.Tiny))
                .background(colors.tertiary),
        )
        Text(
            text = text,
            color = colors.tertiary,
            fontFamily = typography.mono,
            fontSize = 12.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

@Composable
private fun YokhanFormFieldLabel(
    text: String,
    required: Boolean,
    colors: YokhanPalette,
    typography: YokhanTypography,
    maxLines: Int = 1,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            color = colors.primary,
            fontFamily = typography.mono,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.45.sp,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (required) {
            Text(
                text = " *",
                color = colors.accent,
                fontFamily = typography.mono,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun YokhanFormSupportText(
    helper: String?,
    validation: YokhanFormValidation,
    colors: YokhanPalette,
    typography: YokhanTypography,
) {
    validation.message?.let {
        YokhanFormValidationText(validation, colors, typography)
        return
    }
    helper?.let { YokhanFormCaption(it, colors, typography) }
}

@Composable
private fun YokhanFormValidationText(
    validation: YokhanFormValidation,
    colors: YokhanPalette,
    typography: YokhanTypography,
) {
    val message = validation.message ?: return
    Text(
        text = message,
        color = when (validation.state) {
            YokhanFormValidationState.None,
            YokhanFormValidationState.Info,
            -> colors.secondary

            YokhanFormValidationState.Warning -> YokhanAccent
            YokhanFormValidationState.Error -> YokhanRed
            YokhanFormValidationState.Success -> YokhanGreen
        },
        fontFamily = typography.mono,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )
}

@Composable
private fun YokhanFormCaption(
    text: String,
    colors: YokhanPalette,
    typography: YokhanTypography,
) {
    Text(
        text = text,
        color = colors.secondary,
        fontFamily = typography.mono,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )
}

private fun YokhanFormAction.label(): String {
    return when (this) {
        YokhanFormAction.Save -> "SAVE"
        YokhanFormAction.Apply -> "APPLY"
        YokhanFormAction.Cancel -> "CANCEL"
        YokhanFormAction.Reset -> "RESET"
    }
}

private fun YokhanFormAction.event(): YokhanFormEvent {
    return when (this) {
        YokhanFormAction.Save -> YokhanFormEvent.Save
        YokhanFormAction.Apply -> YokhanFormEvent.Save
        YokhanFormAction.Cancel -> YokhanFormEvent.Cancel
        YokhanFormAction.Reset -> YokhanFormEvent.Reset
    }
}
