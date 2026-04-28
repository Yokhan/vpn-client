package com.yokhan.vpn.app.ui.form

import android.content.Context
import com.yokhan.vpn.shared.ui.form.YokhanFormField
import com.yokhan.vpn.shared.ui.form.YokhanFormOption
import com.yokhan.vpn.shared.ui.form.YokhanFormState

internal fun Context.arrayOptions(
    entriesResId: Int,
    valuesResId: Int = entriesResId,
): List<YokhanFormOption> {
    val entries = resources.getStringArray(entriesResId)
    val values = resources.getStringArray(valuesResId)
    return values.mapIndexed { index, value ->
        YokhanFormOption(value, entries.getOrNull(index) ?: value)
    }
}

internal fun YokhanFormState.textValue(id: String): String {
    val field = sections.asSequence()
        .flatMap { it.fields.asSequence() }
        .firstOrNull { it.id == id }
    return when (field) {
        is YokhanFormField.Text -> field.value
        is YokhanFormField.Number -> field.value
        is YokhanFormField.Port -> field.value
        else -> ""
    }
}

internal fun YokhanFormState.switchValue(id: String): Boolean {
    return sections.asSequence()
        .flatMap { it.fields.asSequence() }
        .filterIsInstance<YokhanFormField.Switch>()
        .firstOrNull { it.id == id }
        ?.checked ?: false
}

internal fun YokhanFormState.selectValue(id: String): String {
    return sections.asSequence()
        .flatMap { it.fields.asSequence() }
        .filterIsInstance<YokhanFormField.Select>()
        .firstOrNull { it.id == id }
        ?.selectedOptionId.orEmpty()
}

internal fun YokhanFormField.withVisible(visible: Boolean): YokhanFormField {
    return when (this) {
        is YokhanFormField.Text -> copy(visible = visible)
        is YokhanFormField.Password -> copy(visible = visible)
        is YokhanFormField.Number -> copy(visible = visible)
        is YokhanFormField.Port -> copy(visible = visible)
        is YokhanFormField.Switch -> copy(visible = visible)
        is YokhanFormField.Select -> copy(visible = visible)
        is YokhanFormField.Info -> copy(visible = visible)
        is YokhanFormField.Action -> copy(visible = visible)
    }
}
