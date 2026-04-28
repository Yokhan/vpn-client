package com.yokhan.vpn.app.ui.profile

import com.yokhan.vpn.app.ui.profile.binding.PreferenceBinding
import com.yokhan.vpn.app.ui.profile.binding.PreferenceBindingManager
import com.yokhan.vpn.app.ui.profile.binding.Type
import com.yokhan.vpn.shared.ui.form.YokhanFormField
import com.yokhan.vpn.shared.ui.form.YokhanFormSection
import com.yokhan.vpn.shared.ui.form.YokhanFormState
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean

class WireGuardSettingsActivity : ComposeProfileSettingsActivity<WireGuardBean>() {

    override fun createEntity() = WireGuardBean()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, FieldName))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, FieldServerAddress))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, FieldServerPort))
    private val localAddress = pbm.add(PreferenceBinding(Type.Text, FieldLocalAddress))
    private val privateKey = pbm.add(PreferenceBinding(Type.Text, FieldPrivateKey))
    private val peerPublicKey = pbm.add(PreferenceBinding(Type.Text, FieldPeerPublicKey))
    private val peerPreSharedKey = pbm.add(PreferenceBinding(Type.Text, FieldPeerPreSharedKey))
    private val mtu = pbm.add(PreferenceBinding(Type.TextToInt, FieldMtu))
    private val reserved = pbm.add(PreferenceBinding(Type.Text, FieldReserved))

    override fun WireGuardBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun WireGuardBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun buildFormState(): YokhanFormState {
        return YokhanFormState(
            title = getString(R.string.profile_config),
            subtitle = "WireGuard",
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
                        YokhanFormField.Text(
                            id = FieldLocalAddress,
                            label = getString(R.string.wireguard_local_address),
                            value = cacheString(FieldLocalAddress),
                        ),
                        YokhanFormField.Password(
                            id = FieldPrivateKey,
                            label = getString(R.string.ssh_private_key),
                            value = cacheString(FieldPrivateKey),
                            required = true,
                        ),
                        YokhanFormField.Text(
                            id = FieldPeerPublicKey,
                            label = getString(R.string.wireguard_public_key),
                            value = cacheString(FieldPeerPublicKey),
                            required = true,
                        ),
                        YokhanFormField.Password(
                            id = FieldPeerPreSharedKey,
                            label = getString(R.string.wireguard_psk),
                            value = cacheString(FieldPeerPreSharedKey),
                        ),
                        YokhanFormField.Number(
                            id = FieldMtu,
                            label = getString(R.string.mtu),
                            value = cacheString(FieldMtu).ifBlank { "1420" },
                        ),
                        YokhanFormField.Text(
                            id = FieldReserved,
                            label = "Reserved",
                            value = cacheString(FieldReserved),
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
        DataStore.profileCacheStore.putString(FieldLocalAddress, state.textValue(FieldLocalAddress))
        DataStore.profileCacheStore.putString(FieldPrivateKey, state.passwordValue(FieldPrivateKey))
        DataStore.profileCacheStore.putString(FieldPeerPublicKey, state.textValue(FieldPeerPublicKey))
        DataStore.profileCacheStore.putString(FieldPeerPreSharedKey, state.passwordValue(FieldPeerPreSharedKey))
        DataStore.profileCacheStore.putString(FieldMtu, state.textValue(FieldMtu))
        DataStore.profileCacheStore.putString(FieldReserved, state.textValue(FieldReserved))
        DataStore.dirty = state.dirty
    }

    private companion object {
        const val FieldName = "name"
        const val FieldServerAddress = "serverAddress"
        const val FieldServerPort = "serverPort"
        const val FieldLocalAddress = "localAddress"
        const val FieldPrivateKey = "privateKey"
        const val FieldPeerPublicKey = "peerPublicKey"
        const val FieldPeerPreSharedKey = "peerPreSharedKey"
        const val FieldMtu = "mtu"
        const val FieldReserved = "reserved"
    }
}
