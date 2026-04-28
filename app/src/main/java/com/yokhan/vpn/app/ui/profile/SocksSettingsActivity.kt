package com.yokhan.vpn.app.ui.profile

import com.yokhan.vpn.shared.ui.form.YokhanFormField
import com.yokhan.vpn.shared.ui.form.YokhanFormSection
import com.yokhan.vpn.shared.ui.form.YokhanFormState
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean

class SocksSettingsActivity : ComposeProfileSettingsActivity<SOCKSBean>() {

    override fun createEntity() = SOCKSBean()

    override fun SOCKSBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverProtocolInt = protocol
        DataStore.serverUsername = username
        DataStore.serverPassword = password
        DataStore.profileCacheStore.putBoolean(FieldUdpOverTcp, sUoT)
    }

    override fun SOCKSBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        protocol = DataStore.serverProtocolInt
        username = DataStore.serverUsername
        password = DataStore.serverPassword
        sUoT = DataStore.profileCacheStore.getBoolean(FieldUdpOverTcp)
    }

    override fun buildFormState(): YokhanFormState {
        return YokhanFormState(
            title = getString(R.string.profile_config),
            subtitle = "SOCKS",
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
                        YokhanFormField.Select(
                            id = Key.SERVER_PROTOCOL,
                            label = getString(R.string.app_version),
                            options = arrayOptions(R.array.socks_versions, R.array.int_array_3),
                            selectedOptionId = DataStore.serverProtocolInt.toString(),
                        ),
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
                        YokhanFormField.Text(
                            id = Key.SERVER_USERNAME,
                            label = getString(R.string.username_opt),
                            value = DataStore.serverUsername,
                        ),
                        YokhanFormField.Password(
                            id = Key.SERVER_PASSWORD,
                            label = getString(R.string.password_opt),
                            value = DataStore.serverPassword,
                        ),
                    ),
                ),
                YokhanFormSection(
                    id = "sing-box",
                    title = "SING-BOX SERVER",
                    fields = listOf(
                        YokhanFormField.Switch(
                            id = FieldUdpOverTcp,
                            label = "UDP over TCP",
                            checked = DataStore.profileCacheStore.getBoolean(FieldUdpOverTcp, false),
                        ),
                    ),
                ),
            ),
        )
    }

    override fun validateFormState(state: YokhanFormState): YokhanFormState {
        val socks5 = state.selectValue(Key.SERVER_PROTOCOL).toIntOrNull() == SOCKSBean.PROTOCOL_SOCKS5
        return state.copy(
            sections = state.sections.map { section ->
                section.copy(
                    fields = section.fields.map { field ->
                        if (field.id == Key.SERVER_PASSWORD && field is YokhanFormField.Password) {
                            field.copy(visible = socks5)
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
        DataStore.serverProtocolInt = state.selectValue(Key.SERVER_PROTOCOL).toIntOrNull()
            ?: SOCKSBean.PROTOCOL_SOCKS5
        DataStore.serverUsername = state.textValue(Key.SERVER_USERNAME)
        DataStore.serverPassword = state.passwordValue(Key.SERVER_PASSWORD)
        DataStore.profileCacheStore.putBoolean(FieldUdpOverTcp, state.switchValue(FieldUdpOverTcp))
        DataStore.dirty = state.dirty
    }

    private companion object {
        const val FieldUdpOverTcp = "sUoT"
    }
}
