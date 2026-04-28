package com.yokhan.vpn.shared.ui.form

import androidx.compose.runtime.Immutable

@Immutable
data class YokhanFormState(
    val title: String,
    val subtitle: String? = null,
    val sections: List<YokhanFormSection> = emptyList(),
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val loading: Boolean = false,
    val readOnly: Boolean = false,
    val primaryAction: YokhanFormAction = YokhanFormAction.Save,
    val secondaryAction: YokhanFormAction = YokhanFormAction.Cancel,
)

@Immutable
data class YokhanFormSection(
    val id: String,
    val title: String,
    val fields: List<YokhanFormField>,
    val description: String? = null,
)

@Immutable
sealed interface YokhanFormField {
    val id: String
    val label: String
    val helper: String?
    val enabled: Boolean
    val visible: Boolean
    val required: Boolean
    val validation: YokhanFormValidation

    @Immutable
    data class Text(
        override val id: String,
        override val label: String,
        val value: String,
        val placeholder: String? = null,
        val mode: YokhanFormTextMode = YokhanFormTextMode.Text,
        override val helper: String? = null,
        override val enabled: Boolean = true,
        override val visible: Boolean = true,
        override val required: Boolean = false,
        override val validation: YokhanFormValidation = YokhanFormValidation(),
    ) : YokhanFormField

    @Immutable
    data class Password(
        override val id: String,
        override val label: String,
        val value: String,
        val placeholder: String? = null,
        override val helper: String? = null,
        override val enabled: Boolean = true,
        override val visible: Boolean = true,
        override val required: Boolean = false,
        override val validation: YokhanFormValidation = YokhanFormValidation(),
    ) : YokhanFormField

    @Immutable
    data class Number(
        override val id: String,
        override val label: String,
        val value: String,
        val placeholder: String? = null,
        override val helper: String? = null,
        override val enabled: Boolean = true,
        override val visible: Boolean = true,
        override val required: Boolean = false,
        override val validation: YokhanFormValidation = YokhanFormValidation(),
    ) : YokhanFormField

    @Immutable
    data class Port(
        override val id: String,
        override val label: String,
        val value: String,
        val placeholder: String? = "1-65535",
        override val helper: String? = null,
        override val enabled: Boolean = true,
        override val visible: Boolean = true,
        override val required: Boolean = true,
        override val validation: YokhanFormValidation = YokhanFormValidation(),
    ) : YokhanFormField

    @Immutable
    data class Switch(
        override val id: String,
        override val label: String,
        val checked: Boolean,
        override val helper: String? = null,
        override val enabled: Boolean = true,
        override val visible: Boolean = true,
        override val required: Boolean = false,
        override val validation: YokhanFormValidation = YokhanFormValidation(),
    ) : YokhanFormField

    @Immutable
    data class Select(
        override val id: String,
        override val label: String,
        val options: List<YokhanFormOption>,
        val selectedOptionId: String,
        override val helper: String? = null,
        override val enabled: Boolean = true,
        override val visible: Boolean = true,
        override val required: Boolean = false,
        override val validation: YokhanFormValidation = YokhanFormValidation(),
    ) : YokhanFormField

    @Immutable
    data class Info(
        override val id: String,
        override val label: String,
        val value: String,
        override val helper: String? = null,
        override val enabled: Boolean = true,
        override val visible: Boolean = true,
        override val required: Boolean = false,
        override val validation: YokhanFormValidation = YokhanFormValidation(),
    ) : YokhanFormField
}

@Immutable
data class YokhanFormOption(
    val id: String,
    val label: String,
    val description: String? = null,
)

@Immutable
data class YokhanFormValidation(
    val state: YokhanFormValidationState = YokhanFormValidationState.None,
    val message: String? = null,
)

enum class YokhanFormValidationState {
    None,
    Info,
    Warning,
    Error,
    Success,
}

enum class YokhanFormTextMode {
    Text,
    Number,
    Port,
    Multiline,
}

enum class YokhanFormAction {
    Save,
    Apply,
    Cancel,
    Reset,
}

sealed interface YokhanFormEvent {
    data class TextChanged(val id: String, val value: String) : YokhanFormEvent
    data class SwitchChanged(val id: String, val value: Boolean) : YokhanFormEvent
    data class OptionSelected(val id: String, val optionId: String) : YokhanFormEvent
    object Save : YokhanFormEvent
    object Cancel : YokhanFormEvent
    object Reset : YokhanFormEvent
}

fun YokhanFormState.withFieldEvent(event: YokhanFormEvent): YokhanFormState {
    return when (event) {
        is YokhanFormEvent.TextChanged -> updateField(event.id) { field ->
            when (field) {
                is YokhanFormField.Text -> field.copy(value = event.value)
                is YokhanFormField.Password -> field.copy(value = event.value)
                is YokhanFormField.Number -> field.copy(value = event.value)
                is YokhanFormField.Port -> field.copy(value = event.value)
                else -> field
            }
        }

        is YokhanFormEvent.SwitchChanged -> updateField(event.id) { field ->
            if (field is YokhanFormField.Switch) {
                field.copy(checked = event.value)
            } else {
                field
            }
        }

        is YokhanFormEvent.OptionSelected -> updateField(event.id) { field ->
            if (field is YokhanFormField.Select) {
                field.copy(selectedOptionId = event.optionId)
            } else {
                field
            }
        }

        YokhanFormEvent.Save,
        YokhanFormEvent.Cancel,
        YokhanFormEvent.Reset,
        -> this
    }
}

private fun YokhanFormState.updateField(
    fieldId: String,
    transform: (YokhanFormField) -> YokhanFormField,
): YokhanFormState {
    var changed = false
    val updatedSections = sections.map { section ->
        val updatedFields = section.fields.map { field ->
            if (field.id == fieldId) {
                val updated = transform(field)
                if (updated != field) {
                    changed = true
                }
                updated
            } else {
                field
            }
        }
        if (updatedFields == section.fields) {
            section
        } else {
            section.copy(fields = updatedFields)
        }
    }
    return if (changed) copy(sections = updatedSections, dirty = true) else this
}
