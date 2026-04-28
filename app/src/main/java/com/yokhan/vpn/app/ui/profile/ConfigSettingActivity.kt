package com.yokhan.vpn.app.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yokhan.vpn.shared.ui.form.YokhanFormEvent
import com.yokhan.vpn.shared.ui.form.YokhanFormField
import com.yokhan.vpn.shared.ui.form.YokhanFormScreen
import com.yokhan.vpn.shared.ui.form.YokhanFormSection
import com.yokhan.vpn.shared.ui.form.YokhanFormState
import com.yokhan.vpn.shared.ui.form.YokhanFormTextMode
import com.yokhan.vpn.shared.ui.form.YokhanFormValidation
import com.yokhan.vpn.shared.ui.form.YokhanFormValidationState
import com.yokhan.vpn.shared.ui.form.withFieldEvent
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import moe.matsuri.nb4a.proxy.config.ConfigBean

class ConfigSettingActivity : ProfileSettingsActivity<ConfigBean>() {

    private val isOutboundOnlyKey = "isOutboundOnly"

    override fun createEntity() = ConfigBean()

    override fun useComposeProfileForm(): Boolean = true

    override fun ConfigBean.init() {
        DataStore.profileCacheStore.putBoolean(isOutboundOnlyKey, type == 1)
        DataStore.profileName = name
        DataStore.serverConfig = config
    }

    override fun ConfigBean.serialize() {
        type = if (DataStore.profileCacheStore.getBoolean(isOutboundOnlyKey, false)) 1 else 0
        name = DataStore.profileName
        config = DataStore.serverConfig
    }

    @Composable
    override fun ComposeProfileContent() {
        var formState by remember {
            mutableStateOf(buildFormState().withConfigValidation())
        }
        YokhanFormScreen(
            state = formState,
            onEvent = { event ->
                when (event) {
                    YokhanFormEvent.Save -> {
                        writeFormToDataStore(formState.copy(dirty = true))
                        runOnDefaultDispatcher { saveAndExit() }
                    }

                    YokhanFormEvent.Cancel,
                    YokhanFormEvent.Reset,
                    -> {
                        DataStore.dirty = false
                        finish()
                    }

                    else -> {
                        formState = formState.withFieldEvent(event).withConfigValidation()
                        writeFormToDataStore(formState)
                    }
                }
            },
        )
    }

    private fun buildFormState(): YokhanFormState {
        val config = DataStore.serverConfig
        return YokhanFormState(
            title = getString(R.string.profile_config),
            subtitle = if (DataStore.editingId == 0L) {
                getString(R.string.add_profile)
            } else {
                getString(R.string.edit)
            },
            sections = listOf(
                YokhanFormSection(
                    id = "config",
                    title = getString(R.string.custom_config).uppercase(),
                    fields = listOf(
                        YokhanFormField.Text(
                            id = FieldProfileName,
                            label = getString(R.string.profile_name),
                            value = DataStore.profileName,
                            required = true,
                        ),
                        YokhanFormField.Switch(
                            id = FieldOutboundOnly,
                            label = getString(R.string.is_outbound_only),
                            checked = DataStore.profileCacheStore.getBoolean(isOutboundOnlyKey, false),
                        ),
                        YokhanFormField.Text(
                            id = FieldServerConfig,
                            label = getString(R.string.custom_config),
                            value = config,
                            placeholder = "{\n  \"type\": \"...\"\n}",
                            mode = YokhanFormTextMode.Multiline,
                            required = true,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun YokhanFormState.withConfigValidation(): YokhanFormState {
        val profileName = textValue(FieldProfileName)
        val config = textValue(FieldServerConfig)
        return copy(
            sections = sections.map { section ->
                section.copy(
                    fields = section.fields.map { field ->
                        when (field.id) {
                            FieldProfileName -> (field as YokhanFormField.Text).copy(
                                validation = if (profileName.isBlank()) {
                                    YokhanFormValidation(
                                        state = YokhanFormValidationState.Warning,
                                        message = getString(R.string.profile_name),
                                    )
                                } else {
                                    YokhanFormValidation()
                                },
                            )

                            FieldServerConfig -> (field as YokhanFormField.Text).copy(
                                validation = if (config.isBlank()) {
                                    YokhanFormValidation(
                                        state = YokhanFormValidationState.Warning,
                                        message = getString(androidx.preference.R.string.not_set),
                                    )
                                } else {
                                    YokhanFormValidation(
                                        state = YokhanFormValidationState.Info,
                                        message = getString(R.string.lines, config.lines().size),
                                    )
                                },
                            )

                            else -> field
                        }
                    },
                )
            },
        )
    }

    private fun writeFormToDataStore(state: YokhanFormState) {
        DataStore.profileName = state.textValue(FieldProfileName)
        DataStore.profileCacheStore.putBoolean(isOutboundOnlyKey, state.switchValue(FieldOutboundOnly))
        DataStore.serverConfig = state.textValue(FieldServerConfig)
        DataStore.dirty = state.dirty
    }

    private fun YokhanFormState.textValue(id: String): String {
        return sections.asSequence()
            .flatMap { it.fields.asSequence() }
            .filterIsInstance<YokhanFormField.Text>()
            .firstOrNull { it.id == id }
            ?.value
            .orEmpty()
    }

    private fun YokhanFormState.switchValue(id: String): Boolean {
        return sections.asSequence()
            .flatMap { it.fields.asSequence() }
            .filterIsInstance<YokhanFormField.Switch>()
            .firstOrNull { it.id == id }
            ?.checked
            ?: false
    }

    private companion object {
        const val FieldProfileName = "profileName"
        const val FieldOutboundOnly = "isOutboundOnly"
        const val FieldServerConfig = "serverConfig"
    }
}
