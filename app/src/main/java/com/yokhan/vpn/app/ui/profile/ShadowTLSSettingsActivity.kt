package com.yokhan.gates.app.ui.profile

import com.yokhan.gates.app.ui.profile.binding.PreferenceBinding
import com.yokhan.gates.app.ui.profile.binding.PreferenceBindingManager
import com.yokhan.gates.app.ui.profile.binding.Type
import com.yokhan.gates.shared.ui.form.YokhanFormField
import com.yokhan.gates.shared.ui.form.YokhanFormSection
import com.yokhan.gates.shared.ui.form.YokhanFormState
import com.yokhan.gates.shared.ui.form.YokhanFormTextMode
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean

class ShadowTLSSettingsActivity : ComposeProfileSettingsActivity<ShadowTLSBean>() {

    override fun createEntity() = ShadowTLSBean()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, FieldName))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, FieldServerAddress))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, FieldServerPort))
    private val password = pbm.add(PreferenceBinding(Type.Text, FieldPassword))
    private val version = pbm.add(PreferenceBinding(Type.TextToInt, FieldVersion))
    private val sni = pbm.add(PreferenceBinding(Type.Text, FieldSni))
    private val alpn = pbm.add(PreferenceBinding(Type.Text, FieldAlpn))
    private val certificates = pbm.add(PreferenceBinding(Type.Text, FieldCertificates))
    private val allowInsecure = pbm.add(PreferenceBinding(Type.Bool, FieldAllowInsecure))
    private val utlsFingerprint = pbm.add(PreferenceBinding(Type.Text, FieldUtlsFingerprint))

    override fun ShadowTLSBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun ShadowTLSBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun buildFormState(): YokhanFormState {
        return YokhanFormState(
            title = getString(R.string.profile_config),
            subtitle = "ShadowTLS",
            sections = listOf(
                YokhanFormSection(
                    id = "profile",
                    title = getString(R.string.profile_name).uppercase(),
                    fields = listOf(
                        YokhanFormField.Text(
                            id = FieldName,
                            label = getString(R.string.profile_name),
                            value = cacheString(FieldName),
                            required = true,
                        ),
                    ),
                ),
                YokhanFormSection(
                    id = "proxy",
                    title = getString(R.string.proxy_cat).uppercase(),
                    fields = listOf(
                        YokhanFormField.Text(
                            id = FieldServerAddress,
                            label = getString(R.string.server_address),
                            value = cacheString(FieldServerAddress),
                            required = true,
                        ),
                        YokhanFormField.Port(
                            id = FieldServerPort,
                            label = getString(R.string.server_port),
                            value = cacheString(FieldServerPort),
                        ),
                        YokhanFormField.Select(
                            id = FieldVersion,
                            label = getString(R.string.protocol_version),
                            options = arrayOptions(R.array.shadowtls_version_value),
                            selectedOptionId = cacheString(FieldVersion),
                        ),
                        YokhanFormField.Password(
                            id = FieldPassword,
                            label = getString(R.string.password),
                            value = cacheString(FieldPassword),
                        ),
                    ),
                ),
                YokhanFormSection(
                    id = "security",
                    title = getString(R.string.security_settings).uppercase(),
                    fields = listOf(
                        YokhanFormField.Text(
                            id = FieldSni,
                            label = getString(R.string.sni),
                            value = cacheString(FieldSni),
                        ),
                        YokhanFormField.Text(
                            id = FieldAlpn,
                            label = getString(R.string.alpn),
                            value = cacheString(FieldAlpn),
                        ),
                        YokhanFormField.Text(
                            id = FieldCertificates,
                            label = getString(R.string.certificates),
                            value = cacheString(FieldCertificates),
                            mode = YokhanFormTextMode.Multiline,
                        ),
                        YokhanFormField.Switch(
                            id = FieldAllowInsecure,
                            label = getString(R.string.allow_insecure),
                            helper = getString(R.string.allow_insecure_sum),
                            checked = cacheBoolean(FieldAllowInsecure),
                        ),
                        YokhanFormField.Select(
                            id = FieldUtlsFingerprint,
                            label = getString(R.string.utls_fingerprint),
                            options = arrayOptions(R.array.utls_fingerprint_entry),
                            selectedOptionId = cacheString(FieldUtlsFingerprint),
                        ),
                    ),
                ),
            ),
        )
    }

    override fun writeFormStateToCache(state: YokhanFormState) {
        DataStore.profileCacheStore.putString(FieldName, state.textValue(FieldName))
        DataStore.profileCacheStore.putString(FieldServerAddress, state.textValue(FieldServerAddress))
        DataStore.profileCacheStore.putString(FieldServerPort, state.textValue(FieldServerPort))
        DataStore.profileCacheStore.putString(FieldVersion, state.selectValue(FieldVersion))
        DataStore.profileCacheStore.putString(FieldPassword, state.passwordValue(FieldPassword))
        DataStore.profileCacheStore.putString(FieldSni, state.textValue(FieldSni))
        DataStore.profileCacheStore.putString(FieldAlpn, state.textValue(FieldAlpn))
        DataStore.profileCacheStore.putString(FieldCertificates, state.textValue(FieldCertificates))
        DataStore.profileCacheStore.putBoolean(FieldAllowInsecure, state.switchValue(FieldAllowInsecure))
        DataStore.profileCacheStore.putString(FieldUtlsFingerprint, state.selectValue(FieldUtlsFingerprint))
        DataStore.dirty = state.dirty
    }

    private companion object {
        const val FieldName = "name"
        const val FieldServerAddress = "serverAddress"
        const val FieldServerPort = "serverPort"
        const val FieldVersion = "version"
        const val FieldPassword = "password"
        const val FieldSni = "sni"
        const val FieldAlpn = "alpn"
        const val FieldCertificates = "certificates"
        const val FieldAllowInsecure = "allowInsecure"
        const val FieldUtlsFingerprint = "utlsFingerprint"
    }
}
