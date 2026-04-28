package com.yokhan.vpn.app.ui.profile

import com.yokhan.vpn.shared.ui.form.YokhanFormField
import com.yokhan.vpn.shared.ui.form.YokhanFormSection
import com.yokhan.vpn.shared.ui.form.YokhanFormState
import com.yokhan.vpn.shared.ui.form.YokhanFormTextMode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.naive.NaiveBean

class NaiveSettingsActivity : ComposeProfileSettingsActivity<NaiveBean>() {

    override fun createEntity() = NaiveBean()

    override fun NaiveBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverUsername = username
        DataStore.serverPassword = password
        DataStore.serverProtocol = proto
        DataStore.serverSNI = sni
        DataStore.serverCertificates = certificates
        DataStore.serverHeaders = extraHeaders
        DataStore.serverInsecureConcurrency = insecureConcurrency
        DataStore.profileCacheStore.putBoolean(FieldUdpOverTcp, sUoT)
    }

    override fun NaiveBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        username = DataStore.serverUsername
        password = DataStore.serverPassword
        proto = DataStore.serverProtocol
        sni = DataStore.serverSNI
        certificates = DataStore.serverCertificates
        extraHeaders = DataStore.serverHeaders.replace("\r\n", "\n")
        insecureConcurrency = DataStore.serverInsecureConcurrency
        sUoT = DataStore.profileCacheStore.getBoolean(FieldUdpOverTcp)
    }

    override fun buildFormState(): YokhanFormState {
        return YokhanFormState(
            title = getString(R.string.profile_config),
            subtitle = "Naive",
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
                            label = getString(R.string.username_opt),
                            value = DataStore.serverUsername,
                        ),
                        YokhanFormField.Password(
                            id = Key.SERVER_PASSWORD,
                            label = getString(R.string.password_opt),
                            value = DataStore.serverPassword,
                        ),
                        YokhanFormField.Select(
                            id = Key.SERVER_PROTOCOL,
                            label = getString(R.string.protocol),
                            options = arrayOptions(R.array.naive_proto_entry, R.array.naive_proto_value),
                            selectedOptionId = DataStore.serverProtocol.ifBlank { "https" },
                        ),
                        YokhanFormField.Text(
                            id = Key.SERVER_HEADERS,
                            label = getString(R.string.extra_headers),
                            value = DataStore.serverHeaders,
                            mode = YokhanFormTextMode.Multiline,
                        ),
                        YokhanFormField.Text(
                            id = Key.SERVER_SNI,
                            label = getString(R.string.sni),
                            value = DataStore.serverSNI,
                        ),
                        YokhanFormField.Text(
                            id = Key.SERVER_CERTIFICATES,
                            label = getString(R.string.certificates),
                            value = DataStore.serverCertificates,
                            mode = YokhanFormTextMode.Multiline,
                        ),
                        YokhanFormField.Number(
                            id = Key.SERVER_INSECURE_CONCURRENCY,
                            label = getString(R.string.naive_insecure_concurrency),
                            value = DataStore.serverInsecureConcurrency.toString(),
                            helper = getString(R.string.naive_insecure_concurrency_summary),
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

    override fun writeFormStateToCache(state: YokhanFormState) {
        DataStore.profileName = state.textValue(Key.PROFILE_NAME)
        DataStore.serverAddress = state.textValue(Key.SERVER_ADDRESS)
        DataStore.serverPort = state.textValue(Key.SERVER_PORT).toIntOrNull() ?: 0
        DataStore.serverUsername = state.textValue(Key.SERVER_USERNAME)
        DataStore.serverPassword = state.passwordValue(Key.SERVER_PASSWORD)
        DataStore.serverProtocol = state.selectValue(Key.SERVER_PROTOCOL).ifBlank { "https" }
        DataStore.serverHeaders = state.textValue(Key.SERVER_HEADERS)
        DataStore.serverSNI = state.textValue(Key.SERVER_SNI)
        DataStore.serverCertificates = state.textValue(Key.SERVER_CERTIFICATES)
        DataStore.serverInsecureConcurrency = state.textValue(Key.SERVER_INSECURE_CONCURRENCY).toIntOrNull() ?: 0
        DataStore.profileCacheStore.putBoolean(FieldUdpOverTcp, state.switchValue(FieldUdpOverTcp))
        DataStore.dirty = state.dirty
    }

    override fun finish() {
        if (DataStore.profileName == "е–µи¦Ѓж‰“ејЂйљђи—ЏеЉџиѓЅ") {
            DataStore.isExpert = true
        } else if (DataStore.profileName == "е–µи¦Ѓе…ій—­йљђи—ЏеЉџиѓЅ") {
            DataStore.isExpert = false
        }
        super.finish()
    }

    private companion object {
        const val FieldUdpOverTcp = "sUoT"
    }
}
