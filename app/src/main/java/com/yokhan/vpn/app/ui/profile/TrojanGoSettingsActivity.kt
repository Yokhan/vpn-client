package com.yokhan.vpn.app.ui.profile

import com.yokhan.vpn.shared.ui.form.YokhanFormField
import com.yokhan.vpn.shared.ui.form.YokhanFormSection
import com.yokhan.vpn.shared.ui.form.YokhanFormState
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean

class TrojanGoSettingsActivity : ComposeProfileSettingsActivity<TrojanGoBean>() {

    override fun createEntity() = TrojanGoBean()

    override fun TrojanGoBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverPassword = password
        DataStore.serverSNI = sni
        DataStore.serverAllowInsecure = allowInsecure
        DataStore.serverNetwork = type
        DataStore.serverHost = host
        DataStore.serverPath = path
        if (encryption.startsWith("ss;")) {
            DataStore.serverEncryption = "ss"
            DataStore.serverMethod = encryption.substringAfter(";").substringBefore(":")
            DataStore.serverPassword1 = encryption.substringAfter(":")
        } else {
            DataStore.serverEncryption = encryption
        }
    }

    override fun TrojanGoBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        password = DataStore.serverPassword
        sni = DataStore.serverSNI
        allowInsecure = DataStore.serverAllowInsecure
        type = DataStore.serverNetwork
        host = DataStore.serverHost
        path = DataStore.serverPath
        encryption = when (val security = DataStore.serverEncryption) {
            "ss" -> "ss;" + DataStore.serverMethod + ":" + DataStore.serverPassword1
            else -> security
        }
    }

    override fun buildFormState(): YokhanFormState {
        return YokhanFormState(
            title = getString(R.string.profile_config),
            subtitle = "Trojan-Go",
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
                        YokhanFormField.Password(
                            id = Key.SERVER_PASSWORD,
                            label = getString(R.string.password),
                            value = DataStore.serverPassword,
                        ),
                        YokhanFormField.Text(
                            id = Key.SERVER_SNI,
                            label = getString(R.string.sni),
                            value = DataStore.serverSNI,
                        ),
                        YokhanFormField.Switch(
                            id = Key.SERVER_ALLOW_INSECURE,
                            label = getString(R.string.allow_insecure),
                            helper = getString(R.string.allow_insecure_sum),
                            checked = DataStore.serverAllowInsecure,
                        ),
                        YokhanFormField.Select(
                            id = Key.SERVER_NETWORK,
                            label = getString(R.string.network),
                            options = arrayOptions(R.array.trojan_go_networks_entry, R.array.trojan_go_networks_value),
                            selectedOptionId = DataStore.serverNetwork.ifBlank { "none" },
                        ),
                        YokhanFormField.Select(
                            id = Key.SERVER_ENCRYPTION,
                            label = getString(R.string.encryption),
                            options = arrayOptions(R.array.trojan_go_security_entry, R.array.trojan_go_security_value),
                            selectedOptionId = DataStore.serverEncryption,
                        ),
                    ),
                ),
                YokhanFormSection(
                    id = "websocket",
                    title = getString(R.string.cag_ws).uppercase(),
                    fields = listOf(
                        YokhanFormField.Text(
                            id = Key.SERVER_HOST,
                            label = getString(R.string.ws_host),
                            value = DataStore.serverHost,
                        ),
                        YokhanFormField.Text(
                            id = Key.SERVER_PATH,
                            label = getString(R.string.ws_path),
                            value = DataStore.serverPath,
                        ),
                    ),
                ),
                YokhanFormSection(
                    id = "shadowsocks",
                    title = getString(R.string.ss_cat).uppercase(),
                    fields = listOf(
                        YokhanFormField.Select(
                            id = Key.SERVER_METHOD,
                            label = getString(R.string.enc_method),
                            options = arrayOptions(R.array.trojan_go_methods),
                            selectedOptionId = DataStore.serverMethod,
                        ),
                        YokhanFormField.Password(
                            id = Key.SERVER_PASSWORD1,
                            label = getString(R.string.password),
                            value = DataStore.serverPassword1,
                        ),
                    ),
                ),
            ),
        )
    }

    override fun validateFormState(state: YokhanFormState): YokhanFormState {
        val showWs = state.selectValue(Key.SERVER_NETWORK) == "ws"
        val showSs = state.selectValue(Key.SERVER_ENCRYPTION) == "ss"
        return state.copy(
            sections = state.sections.map { section ->
                section.copy(
                    fields = section.fields.map { field ->
                        when (field.id) {
                            Key.SERVER_HOST,
                            Key.SERVER_PATH,
                            -> (field as YokhanFormField.Text).copy(visible = showWs)

                            Key.SERVER_METHOD -> (field as YokhanFormField.Select).copy(visible = showSs)
                            Key.SERVER_PASSWORD1 -> (field as YokhanFormField.Password).copy(visible = showSs)
                            else -> field
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
        DataStore.serverPassword = state.passwordValue(Key.SERVER_PASSWORD)
        DataStore.serverSNI = state.textValue(Key.SERVER_SNI)
        DataStore.serverAllowInsecure = state.switchValue(Key.SERVER_ALLOW_INSECURE)
        DataStore.serverNetwork = state.selectValue(Key.SERVER_NETWORK).ifBlank { "none" }
        DataStore.serverEncryption = state.selectValue(Key.SERVER_ENCRYPTION)
        DataStore.serverHost = state.textValue(Key.SERVER_HOST)
        DataStore.serverPath = state.textValue(Key.SERVER_PATH)
        DataStore.serverMethod = state.selectValue(Key.SERVER_METHOD)
        DataStore.serverPassword1 = state.passwordValue(Key.SERVER_PASSWORD1)
        DataStore.dirty = state.dirty
    }
}
