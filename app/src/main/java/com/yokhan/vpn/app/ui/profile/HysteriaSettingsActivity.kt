package com.yokhan.gates.app.ui.profile

import com.yokhan.gates.shared.ui.form.YokhanFormField
import com.yokhan.gates.shared.ui.form.YokhanFormSection
import com.yokhan.gates.shared.ui.form.YokhanFormState
import com.yokhan.gates.shared.ui.form.YokhanFormTextMode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues

class HysteriaSettingsActivity : ComposeProfileSettingsActivity<HysteriaBean>() {

    override fun createEntity() = HysteriaBean().applyDefaultValues()

    override fun HysteriaBean.init() {
        DataStore.profileName = name
        DataStore.protocolVersion = protocolVersion
        DataStore.serverAddress = serverAddress
        DataStore.serverPorts = serverPorts
        DataStore.serverObfs = obfuscation
        DataStore.serverAuthType = authPayloadType
        DataStore.serverProtocolInt = protocol
        DataStore.serverPassword = authPayload
        DataStore.serverSNI = sni
        DataStore.serverALPN = alpn
        DataStore.serverCertificates = caText
        DataStore.serverAllowInsecure = allowInsecure
        DataStore.serverUploadSpeed = uploadMbps
        DataStore.serverDownloadSpeed = downloadMbps
        DataStore.serverStreamReceiveWindow = streamReceiveWindow
        DataStore.serverConnectionReceiveWindow = connectionReceiveWindow
        DataStore.serverDisableMtuDiscovery = disableMtuDiscovery
        DataStore.serverHopInterval = hopInterval
    }

    override fun HysteriaBean.serialize() {
        name = DataStore.profileName
        protocolVersion = DataStore.protocolVersion
        serverAddress = DataStore.serverAddress
        serverPorts = DataStore.serverPorts
        obfuscation = DataStore.serverObfs
        authPayloadType = DataStore.serverAuthType
        authPayload = DataStore.serverPassword
        protocol = DataStore.serverProtocolInt
        sni = DataStore.serverSNI
        alpn = DataStore.serverALPN
        caText = DataStore.serverCertificates
        allowInsecure = DataStore.serverAllowInsecure
        uploadMbps = DataStore.serverUploadSpeed
        downloadMbps = DataStore.serverDownloadSpeed
        streamReceiveWindow = DataStore.serverStreamReceiveWindow
        connectionReceiveWindow = DataStore.serverConnectionReceiveWindow
        disableMtuDiscovery = DataStore.serverDisableMtuDiscovery
        hopInterval = DataStore.serverHopInterval
    }

    override fun buildFormState(): YokhanFormState {
        return YokhanFormState(
            title = getString(R.string.profile_config),
            subtitle = "Hysteria",
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
                        YokhanFormField.Select(
                            id = Key.PROTOCOL_VERSION,
                            label = getString(R.string.protocol_version),
                            options = arrayOptions(R.array.hysteria_version),
                            selectedOptionId = DataStore.protocolVersion.toString(),
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
                        YokhanFormField.Text(
                            id = FieldServerPorts,
                            label = getString(R.string.server_port),
                            value = DataStore.serverPorts,
                            required = true,
                        ),
                        YokhanFormField.Password(
                            id = Key.SERVER_OBFS,
                            label = getString(R.string.hysteria_obfs),
                            value = DataStore.serverObfs,
                        ),
                        YokhanFormField.Select(
                            id = Key.SERVER_AUTH_TYPE,
                            label = getString(R.string.hysteria_auth_type),
                            options = arrayOptions(R.array.hysteria_auth_type, R.array.int_array_3),
                            selectedOptionId = DataStore.serverAuthType.toString(),
                        ),
                        YokhanFormField.Password(
                            id = Key.SERVER_PASSWORD,
                            label = getString(R.string.hysteria_auth_payload),
                            value = DataStore.serverPassword,
                        ),
                        YokhanFormField.Select(
                            id = Key.SERVER_PROTOCOL,
                            label = getString(R.string.protocol),
                            options = arrayOptions(R.array.hysteria_protocol, R.array.int_array_3),
                            selectedOptionId = DataStore.serverProtocolInt.toString(),
                        ),
                        YokhanFormField.Text(
                            id = Key.SERVER_SNI,
                            label = getString(R.string.sni),
                            value = DataStore.serverSNI,
                        ),
                        YokhanFormField.Text(
                            id = Key.SERVER_ALPN,
                            label = getString(R.string.alpn),
                            value = DataStore.serverALPN,
                        ),
                        YokhanFormField.Text(
                            id = Key.SERVER_CERTIFICATES,
                            label = getString(R.string.certificates),
                            value = DataStore.serverCertificates,
                            mode = YokhanFormTextMode.Multiline,
                        ),
                        YokhanFormField.Switch(
                            id = Key.SERVER_ALLOW_INSECURE,
                            label = getString(R.string.allow_insecure),
                            helper = getString(R.string.allow_insecure_sum),
                            checked = DataStore.serverAllowInsecure,
                        ),
                        YokhanFormField.Number(
                            id = Key.SERVER_UPLOAD_SPEED,
                            label = getString(R.string.hysteria_upload_mbps),
                            value = DataStore.serverUploadSpeed.toString(),
                        ),
                        YokhanFormField.Number(
                            id = Key.SERVER_DOWNLOAD_SPEED,
                            label = getString(R.string.hysteria_download_mbps),
                            value = DataStore.serverDownloadSpeed.toString(),
                        ),
                        YokhanFormField.Number(
                            id = Key.SERVER_STREAM_RECEIVE_WINDOW,
                            label = getString(R.string.hysteria_stream_receive_window),
                            value = DataStore.serverStreamReceiveWindow.toString(),
                        ),
                        YokhanFormField.Number(
                            id = Key.SERVER_CONNECTION_RECEIVE_WINDOW,
                            label = getString(R.string.hysteria_connection_receive_window),
                            value = DataStore.serverConnectionReceiveWindow.toString(),
                        ),
                        YokhanFormField.Switch(
                            id = Key.SERVER_DISABLE_MTU_DISCOVERY,
                            label = getString(R.string.hysteria_disable_mtu_discovery),
                            checked = DataStore.serverDisableMtuDiscovery,
                        ),
                        YokhanFormField.Number(
                            id = Key.SERVER_HOP_INTERVAL,
                            label = getString(R.string.hop_interval),
                            value = DataStore.serverHopInterval.toString(),
                        ),
                    ),
                ),
            ),
        )
    }

    override fun validateFormState(state: YokhanFormState): YokhanFormState {
        val version = state.selectValue(Key.PROTOCOL_VERSION).toIntOrNull() ?: 2
        val authType = state.selectValue(Key.SERVER_AUTH_TYPE).toIntOrNull() ?: HysteriaBean.TYPE_NONE
        val v1 = version == 1
        return state.copy(
            sections = state.sections.map { section ->
                section.copy(
                    fields = section.fields.map { field ->
                        when (field.id) {
                            Key.SERVER_AUTH_TYPE -> (field as YokhanFormField.Select).copy(visible = v1)
                            Key.SERVER_PROTOCOL -> (field as YokhanFormField.Select).copy(visible = v1)
                            Key.SERVER_ALPN -> (field as YokhanFormField.Text).copy(visible = v1)
                            Key.SERVER_STREAM_RECEIVE_WINDOW -> (field as YokhanFormField.Number).copy(visible = v1)
                            Key.SERVER_CONNECTION_RECEIVE_WINDOW -> (field as YokhanFormField.Number).copy(visible = v1)
                            Key.SERVER_DISABLE_MTU_DISCOVERY -> (field as YokhanFormField.Switch).copy(visible = v1)
                            Key.SERVER_PASSWORD -> (field as YokhanFormField.Password).copy(
                                label = if (v1) {
                                    getString(R.string.hysteria_auth_payload)
                                } else {
                                    getString(R.string.password)
                                },
                                visible = !v1 || authType != HysteriaBean.TYPE_NONE,
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
        DataStore.protocolVersion = state.selectValue(Key.PROTOCOL_VERSION).toIntOrNull() ?: 2
        DataStore.serverAddress = state.textValue(Key.SERVER_ADDRESS)
        DataStore.serverPorts = state.textValue(FieldServerPorts)
        DataStore.serverObfs = state.passwordValue(Key.SERVER_OBFS)
        DataStore.serverAuthType = state.selectValue(Key.SERVER_AUTH_TYPE).toIntOrNull()
            ?: HysteriaBean.TYPE_NONE
        DataStore.serverPassword = state.passwordValue(Key.SERVER_PASSWORD)
        DataStore.serverProtocolInt = state.selectValue(Key.SERVER_PROTOCOL).toIntOrNull()
            ?: HysteriaBean.PROTOCOL_UDP
        DataStore.serverSNI = state.textValue(Key.SERVER_SNI)
        DataStore.serverALPN = state.textValue(Key.SERVER_ALPN)
        DataStore.serverCertificates = state.textValue(Key.SERVER_CERTIFICATES)
        DataStore.serverAllowInsecure = state.switchValue(Key.SERVER_ALLOW_INSECURE)
        DataStore.serverUploadSpeed = state.textValue(Key.SERVER_UPLOAD_SPEED).toIntOrNull() ?: 0
        DataStore.serverDownloadSpeed = state.textValue(Key.SERVER_DOWNLOAD_SPEED).toIntOrNull() ?: 0
        DataStore.serverStreamReceiveWindow = state.textValue(Key.SERVER_STREAM_RECEIVE_WINDOW).toIntOrNull() ?: 0
        DataStore.serverConnectionReceiveWindow = state.textValue(Key.SERVER_CONNECTION_RECEIVE_WINDOW).toIntOrNull() ?: 0
        DataStore.serverDisableMtuDiscovery = state.switchValue(Key.SERVER_DISABLE_MTU_DISCOVERY)
        DataStore.serverHopInterval = state.textValue(Key.SERVER_HOP_INTERVAL).toIntOrNull() ?: 10
        DataStore.dirty = state.dirty
    }

    private companion object {
        const val FieldServerPorts = "serverPorts"
    }
}
