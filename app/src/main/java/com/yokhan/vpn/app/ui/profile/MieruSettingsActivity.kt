package com.yokhan.vpn.app.ui.profile

import com.yokhan.vpn.shared.ui.form.YokhanFormField
import com.yokhan.vpn.shared.ui.form.YokhanFormSection
import com.yokhan.vpn.shared.ui.form.YokhanFormState
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues

class MieruSettingsActivity : ComposeProfileSettingsActivity<MieruBean>() {

    override fun createEntity() = MieruBean().applyDefaultValues()

    override fun MieruBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverProtocol = protocol
        DataStore.serverUsername = username
        DataStore.serverPassword = password
        DataStore.serverMTU = mtu
    }

    override fun MieruBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        protocol = DataStore.serverProtocol
        username = DataStore.serverUsername
        password = DataStore.serverPassword
        mtu = DataStore.serverMTU
    }

    override fun buildFormState(): YokhanFormState {
        return YokhanFormState(
            title = getString(R.string.profile_config),
            subtitle = "Mieru",
            sections = listOf(
                YokhanFormSection(
                    id = "profile",
                    title = getString(R.string.profile_name).uppercase(),
                    fields = listOf(
                        YokhanFormField.Text(
                            id = Key.PROFILE_NAME,
                            label = getString(R.string.profile_name),
                            value = DataStore.profileName,
                            required = true,
                        ),
                    ),
                ),
                YokhanFormSection(
                    id = "proxy",
                    title = getString(R.string.proxy_cat).uppercase(),
                    fields = listOf(
                        YokhanFormField.Text(
                            id = Key.SERVER_ADDRESS,
                            label = getString(R.string.server_address),
                            value = DataStore.serverAddress,
                            required = true,
                        ),
                        YokhanFormField.Port(
                            id = Key.SERVER_PORT,
                            label = getString(R.string.server_port),
                            value = DataStore.serverPort.toString(),
                        ),
                        YokhanFormField.Select(
                            id = Key.SERVER_PROTOCOL,
                            label = getString(R.string.protocol),
                            options = arrayOptions(R.array.mieru_protocol),
                            selectedOptionId = DataStore.serverProtocol.ifBlank { "TCP" },
                        ),
                        YokhanFormField.Text(
                            id = Key.SERVER_USERNAME,
                            label = getString(R.string.username),
                            value = DataStore.serverUsername,
                            required = true,
                        ),
                        YokhanFormField.Password(
                            id = Key.SERVER_PASSWORD,
                            label = getString(R.string.password),
                            value = DataStore.serverPassword,
                        ),
                        YokhanFormField.Number(
                            id = Key.SERVER_MTU,
                            label = getString(R.string.mtu),
                            value = DataStore.serverMTU.toString(),
                        ),
                    ),
                ),
            ),
        )
    }

    override fun validateFormState(state: YokhanFormState): YokhanFormState {
        val showMtu = state.selectValue(Key.SERVER_PROTOCOL) == "UDP"
        return state.copy(
            sections = state.sections.map { section ->
                section.copy(
                    fields = section.fields.map { field ->
                        if (field.id == Key.SERVER_MTU && field is YokhanFormField.Number) {
                            field.copy(visible = showMtu)
                        } else {
                            field
                        }
                    },
                )
            },
        )
    }

    override fun writeFormStateToCache(state: YokhanFormState) {
        DataStore.profileName = state.textValue(Key.PROFILE_NAME)
        DataStore.serverAddress = state.textValue(Key.SERVER_ADDRESS)
        DataStore.serverPort = state.textValue(Key.SERVER_PORT).toIntOrNull() ?: 0
        DataStore.serverProtocol = state.selectValue(Key.SERVER_PROTOCOL).ifBlank { "TCP" }
        DataStore.serverUsername = state.textValue(Key.SERVER_USERNAME)
        DataStore.serverPassword = state.passwordValue(Key.SERVER_PASSWORD)
        DataStore.serverMTU = state.textValue(Key.SERVER_MTU).toIntOrNull() ?: 0
        DataStore.dirty = state.dirty
    }
}
