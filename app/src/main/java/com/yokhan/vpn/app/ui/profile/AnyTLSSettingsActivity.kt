package com.yokhan.vpn.app.ui.profile

import com.yokhan.vpn.app.ui.profile.binding.PreferenceBinding
import com.yokhan.vpn.app.ui.profile.binding.PreferenceBindingManager
import com.yokhan.vpn.app.ui.profile.binding.Type
import com.yokhan.vpn.shared.ui.form.YokhanFormField
import com.yokhan.vpn.shared.ui.form.YokhanFormSection
import com.yokhan.vpn.shared.ui.form.YokhanFormState
import com.yokhan.vpn.shared.ui.form.YokhanFormTextMode
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean

class AnyTLSSettingsActivity : ComposeProfileSettingsActivity<AnyTLSBean>() {
    override fun createEntity() = AnyTLSBean().applyDefaultValues()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, FieldName))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, FieldServerAddress))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, FieldServerPort))
    private val password = pbm.add(PreferenceBinding(Type.Text, FieldPassword))
    private val sni = pbm.add(PreferenceBinding(Type.Text, FieldSni))
    private val alpn = pbm.add(PreferenceBinding(Type.Text, FieldAlpn))
    private val certificates = pbm.add(PreferenceBinding(Type.Text, FieldCertificates))
    private val allowInsecure = pbm.add(PreferenceBinding(Type.Bool, FieldAllowInsecure))
    private val utlsFingerprint = pbm.add(PreferenceBinding(Type.Text, FieldUtlsFingerprint))

    override fun AnyTLSBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun AnyTLSBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun buildFormState(): YokhanFormState {
        return YokhanFormState(
            title = getString(R.string.profile_config),
            subtitle = "AnyTLS",
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
                        YokhanFormField.Switch(
                            id = FieldAllowInsecure,
                            label = getString(R.string.allow_insecure),
                            checked = cacheBoolean(FieldAllowInsecure),
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
        const val FieldPassword = "password"
        const val FieldSni = "sni"
        const val FieldAlpn = "alpn"
        const val FieldCertificates = "certificates"
        const val FieldAllowInsecure = "allowInsecure"
        const val FieldUtlsFingerprint = "utlsFingerprint"
    }
}
