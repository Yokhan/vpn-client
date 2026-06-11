package com.yokhan.gates.app.ui.profile

import com.yokhan.gates.shared.ui.form.YokhanFormField
import com.yokhan.gates.shared.ui.form.YokhanFormSection
import com.yokhan.gates.shared.ui.form.YokhanFormState
import com.yokhan.gates.shared.ui.form.YokhanFormTextMode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues

class TuicSettingsActivity : ComposeProfileSettingsActivity<TuicBean>() {

    override fun createEntity() = TuicBean().applyDefaultValues()

    override fun TuicBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverUsername = uuid
        DataStore.serverPassword = token
        DataStore.serverALPN = alpn
        DataStore.serverCertificates = caText
        DataStore.serverUDPRelayMode = udpRelayMode
        DataStore.serverCongestionController = congestionController
        DataStore.serverDisableSNI = disableSNI
        DataStore.serverSNI = sni
        DataStore.serverReduceRTT = reduceRTT
        DataStore.serverAllowInsecure = allowInsecure
    }

    override fun TuicBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        uuid = DataStore.serverUsername
        token = DataStore.serverPassword
        alpn = DataStore.serverALPN
        caText = DataStore.serverCertificates
        udpRelayMode = DataStore.serverUDPRelayMode
        congestionController = DataStore.serverCongestionController
        disableSNI = DataStore.serverDisableSNI
        sni = DataStore.serverSNI
        reduceRTT = DataStore.serverReduceRTT
        allowInsecure = DataStore.serverAllowInsecure
    }

    override fun buildFormState(): YokhanFormState {
        return YokhanFormState(
            title = getString(R.string.profile_config),
            subtitle = "TUIC",
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
                            label = getString(R.string.uuid),
                            value = DataStore.serverUsername,
                            required = true,
                        ),
                        YokhanFormField.Password(
                            id = Key.SERVER_PASSWORD,
                            label = getString(R.string.password),
                            value = DataStore.serverPassword,
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
                        YokhanFormField.Select(
                            id = Key.SERVER_UDP_RELAY_MODE,
                            label = getString(R.string.tuic_udp_relay_mode),
                            options = arrayOptions(R.array.tuic_udp_relay_mode_value),
                            selectedOptionId = DataStore.serverUDPRelayMode.ifBlank { "native" },
                        ),
                        YokhanFormField.Select(
                            id = Key.SERVER_CONGESTION_CONTROLLER,
                            label = getString(R.string.tuic_congestion_controller),
                            options = arrayOptions(R.array.tuic_congestion_controller_value),
                            selectedOptionId = DataStore.serverCongestionController.ifBlank { "cubic" },
                        ),
                        YokhanFormField.Switch(
                            id = Key.SERVER_DISABLE_SNI,
                            label = getString(R.string.tuic_disable_sni),
                            checked = DataStore.serverDisableSNI,
                        ),
                        YokhanFormField.Text(
                            id = Key.SERVER_SNI,
                            label = getString(R.string.sni),
                            value = DataStore.serverSNI,
                        ),
                        YokhanFormField.Switch(
                            id = Key.SERVER_REDUCE_RTT,
                            label = getString(R.string.tuic_reduce_rtt),
                            checked = DataStore.serverReduceRTT,
                        ),
                        YokhanFormField.Switch(
                            id = Key.SERVER_ALLOW_INSECURE,
                            label = getString(R.string.allow_insecure),
                            checked = DataStore.serverAllowInsecure,
                        ),
                    ),
                ),
            ),
        )
    }

    override fun validateFormState(state: YokhanFormState): YokhanFormState {
        val sniEnabled = !state.switchValue(Key.SERVER_DISABLE_SNI)
        return state.copy(
            sections = state.sections.map { section ->
                section.copy(
                    fields = section.fields.map { field ->
                        if (field.id == Key.SERVER_SNI && field is YokhanFormField.Text) {
                            field.copy(enabled = sniEnabled)
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
        DataStore.serverUsername = state.textValue(Key.SERVER_USERNAME)
        DataStore.serverPassword = state.passwordValue(Key.SERVER_PASSWORD)
        DataStore.serverALPN = state.textValue(Key.SERVER_ALPN)
        DataStore.serverCertificates = state.textValue(Key.SERVER_CERTIFICATES)
        DataStore.serverUDPRelayMode = state.selectValue(Key.SERVER_UDP_RELAY_MODE).ifBlank { "native" }
        DataStore.serverCongestionController = state.selectValue(Key.SERVER_CONGESTION_CONTROLLER).ifBlank { "cubic" }
        DataStore.serverDisableSNI = state.switchValue(Key.SERVER_DISABLE_SNI)
        DataStore.serverSNI = state.textValue(Key.SERVER_SNI)
        DataStore.serverReduceRTT = state.switchValue(Key.SERVER_REDUCE_RTT)
        DataStore.serverAllowInsecure = state.switchValue(Key.SERVER_ALLOW_INSECURE)
        DataStore.dirty = state.dirty
    }
}
