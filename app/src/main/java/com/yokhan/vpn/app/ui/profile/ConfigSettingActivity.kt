package com.yokhan.vpn.app.ui.profile

import com.yokhan.vpn.shared.ui.form.YokhanFormField
import com.yokhan.vpn.shared.ui.form.YokhanFormSection
import com.yokhan.vpn.shared.ui.form.YokhanFormState
import com.yokhan.vpn.shared.ui.form.YokhanFormTextMode
import com.yokhan.vpn.shared.ui.form.YokhanFormValidation
import com.yokhan.vpn.shared.ui.form.YokhanFormValidationState
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import moe.matsuri.nb4a.proxy.config.ConfigBean

class ConfigSettingActivity : ComposeProfileSettingsActivity<ConfigBean>() {

    private val isOutboundOnlyKey = "isOutboundOnly"

    override fun createEntity() = ConfigBean()

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

    override fun buildFormState(): YokhanFormState {
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

    override fun validateFormState(state: YokhanFormState): YokhanFormState {
        return state.withConfigValidation()
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

    override fun writeFormStateToCache(state: YokhanFormState) {
        DataStore.profileName = state.textValue(FieldProfileName)
        DataStore.profileCacheStore.putBoolean(isOutboundOnlyKey, state.switchValue(FieldOutboundOnly))
        DataStore.serverConfig = state.textValue(FieldServerConfig)
        DataStore.dirty = state.dirty
    }

    private companion object {
        const val FieldProfileName = "profileName"
        const val FieldOutboundOnly = "isOutboundOnly"
        const val FieldServerConfig = "serverConfig"
    }
}
