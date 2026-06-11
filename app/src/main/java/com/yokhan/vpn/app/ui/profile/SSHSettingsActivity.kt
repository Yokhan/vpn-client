package com.yokhan.gates.app.ui.profile

import com.yokhan.gates.shared.ui.form.YokhanFormField
import com.yokhan.gates.shared.ui.form.YokhanFormSection
import com.yokhan.gates.shared.ui.form.YokhanFormState
import com.yokhan.gates.shared.ui.form.YokhanFormTextMode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.ssh.SSHBean

class SSHSettingsActivity : ComposeProfileSettingsActivity<SSHBean>() {

    override fun createEntity() = SSHBean()

    override fun SSHBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverUsername = username
        DataStore.serverAuthType = authType
        DataStore.serverPassword = password
        DataStore.serverPrivateKey = privateKey
        DataStore.serverPassword1 = privateKeyPassphrase
        DataStore.serverCertificates = publicKey
    }

    override fun SSHBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        username = DataStore.serverUsername
        authType = DataStore.serverAuthType
        when (authType) {
            SSHBean.AUTH_TYPE_PASSWORD -> password = DataStore.serverPassword
            SSHBean.AUTH_TYPE_PRIVATE_KEY -> {
                privateKey = DataStore.serverPrivateKey
                privateKeyPassphrase = DataStore.serverPassword1
            }
        }
        publicKey = DataStore.serverCertificates
    }

    override fun buildFormState(): YokhanFormState {
        return YokhanFormState(
            title = getString(R.string.profile_config),
            subtitle = "SSH",
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
                        YokhanFormField.Text(
                            id = Key.SERVER_USERNAME,
                            label = getString(R.string.username),
                            value = DataStore.serverUsername,
                            required = true,
                        ),
                        YokhanFormField.Select(
                            id = Key.SERVER_AUTH_TYPE,
                            label = getString(R.string.hysteria_auth_type),
                            options = arrayOptions(R.array.ssh_auth_type, R.array.int_array_3),
                            selectedOptionId = DataStore.serverAuthType.toString(),
                        ),
                        YokhanFormField.Password(
                            id = Key.SERVER_PASSWORD,
                            label = getString(R.string.password),
                            value = DataStore.serverPassword,
                        ),
                        YokhanFormField.Text(
                            id = Key.SERVER_PRIVATE_KEY,
                            label = getString(R.string.ssh_private_key),
                            value = DataStore.serverPrivateKey,
                            mode = YokhanFormTextMode.Multiline,
                        ),
                        YokhanFormField.Password(
                            id = Key.SERVER_PASSWORD1,
                            label = getString(R.string.ssh_private_key_passphrase),
                            value = DataStore.serverPassword1,
                        ),
                        YokhanFormField.Text(
                            id = Key.SERVER_CERTIFICATES,
                            label = getString(R.string.ssh_public_key),
                            value = DataStore.serverCertificates,
                            mode = YokhanFormTextMode.Multiline,
                        ),
                    ),
                ),
            ),
        )
    }

    override fun validateFormState(state: YokhanFormState): YokhanFormState {
        val authType = state.selectValue(Key.SERVER_AUTH_TYPE).toIntOrNull()
        return state.copy(
            sections = state.sections.map { section ->
                section.copy(
                    fields = section.fields.map { field ->
                        when (field.id) {
                            Key.SERVER_PASSWORD -> (field as YokhanFormField.Password).copy(
                                visible = authType == SSHBean.AUTH_TYPE_PASSWORD,
                            )

                            Key.SERVER_PRIVATE_KEY -> (field as YokhanFormField.Text).copy(
                                visible = authType == SSHBean.AUTH_TYPE_PRIVATE_KEY,
                            )

                            Key.SERVER_PASSWORD1 -> (field as YokhanFormField.Password).copy(
                                visible = authType == SSHBean.AUTH_TYPE_PRIVATE_KEY,
                            )

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
        DataStore.serverUsername = state.textValue(Key.SERVER_USERNAME)
        DataStore.serverAuthType = state.selectValue(Key.SERVER_AUTH_TYPE).toIntOrNull()
            ?: SSHBean.AUTH_TYPE_NONE
        DataStore.serverPassword = state.passwordValue(Key.SERVER_PASSWORD)
        DataStore.serverPrivateKey = state.textValue(Key.SERVER_PRIVATE_KEY)
        DataStore.serverPassword1 = state.passwordValue(Key.SERVER_PASSWORD1)
        DataStore.serverCertificates = state.textValue(Key.SERVER_CERTIFICATES)
        DataStore.dirty = state.dirty
    }
}
