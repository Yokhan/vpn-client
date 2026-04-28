package com.yokhan.vpn.app.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yokhan.vpn.shared.ui.form.YokhanFormEvent
import com.yokhan.vpn.shared.ui.form.YokhanFormField
import com.yokhan.vpn.shared.ui.form.YokhanFormOption
import com.yokhan.vpn.shared.ui.form.YokhanFormScreen
import com.yokhan.vpn.shared.ui.form.YokhanFormState
import com.yokhan.vpn.shared.ui.form.withFieldEvent
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

abstract class ComposeProfileSettingsActivity<T : AbstractBean> : ProfileSettingsActivity<T>() {

    final override fun useComposeProfileForm(): Boolean = true

    @Composable
    final override fun ComposeProfileContent() {
        var formState by remember {
            mutableStateOf(validateFormState(buildFormState()))
        }
        YokhanFormScreen(
            state = formState,
            onEvent = { event ->
                when (event) {
                    YokhanFormEvent.Save -> saveForm(formState)
                    YokhanFormEvent.Cancel,
                    YokhanFormEvent.Reset,
                    -> cancelForm()

                    else -> {
                        formState = validateFormState(formState.withFieldEvent(event))
                        writeFormStateToCache(formState)
                    }
                }
            },
        )
    }

    protected abstract fun buildFormState(): YokhanFormState

    protected abstract fun writeFormStateToCache(state: YokhanFormState)

    protected open fun validateFormState(state: YokhanFormState): YokhanFormState = state

    protected open fun saveForm(state: YokhanFormState) {
        writeFormStateToCache(state.copy(dirty = true))
        runOnDefaultDispatcher { saveAndExit() }
    }

    protected open fun cancelForm() {
        DataStore.dirty = false
        finish()
    }

    protected fun YokhanFormState.textValue(id: String): String {
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

    protected fun YokhanFormState.passwordValue(id: String): String {
        return sections.asSequence()
            .flatMap { it.fields.asSequence() }
            .filterIsInstance<YokhanFormField.Password>()
            .firstOrNull { it.id == id }
            ?.value
            .orEmpty()
    }

    protected fun YokhanFormState.switchValue(id: String): Boolean {
        return sections.asSequence()
            .flatMap { it.fields.asSequence() }
            .filterIsInstance<YokhanFormField.Switch>()
            .firstOrNull { it.id == id }
            ?.checked
            ?: false
    }

    protected fun YokhanFormState.selectValue(id: String): String {
        return sections.asSequence()
            .flatMap { it.fields.asSequence() }
            .filterIsInstance<YokhanFormField.Select>()
            .firstOrNull { it.id == id }
            ?.selectedOptionId
            .orEmpty()
    }

    protected fun arrayOptions(entriesResId: Int, valuesResId: Int = entriesResId): List<YokhanFormOption> {
        val entries = resources.getStringArray(entriesResId)
        val values = resources.getStringArray(valuesResId)
        return values.mapIndexed { index, value ->
            YokhanFormOption(
                id = value,
                label = entries.getOrNull(index) ?: value,
            )
        }
    }

    protected fun cacheString(key: String): String {
        return DataStore.profileCacheStore.getString(key) ?: ""
    }

    protected fun cacheBoolean(key: String): Boolean {
        return DataStore.profileCacheStore.getBoolean(key, false)
    }
}
