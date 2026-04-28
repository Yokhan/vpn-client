package com.yokhan.vpn.app.ui.profile

import com.yokhan.vpn.app.ui.profile.binding.PreferenceBinding
import com.yokhan.vpn.app.ui.profile.binding.PreferenceBindingManager
import com.yokhan.vpn.app.ui.profile.binding.Type
import com.yokhan.vpn.shared.ui.form.YokhanFormField
import com.yokhan.vpn.shared.ui.form.YokhanFormSection
import com.yokhan.vpn.shared.ui.form.YokhanFormState
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean

class ShadowsocksSettingsActivity : ComposeProfileSettingsActivity<ShadowsocksBean>() {

    override fun createEntity() = ShadowsocksBean()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, FieldName))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, FieldServerAddress))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, FieldServerPort))
    private val password = pbm.add(PreferenceBinding(Type.Text, FieldPassword))
    private val method = pbm.add(PreferenceBinding(Type.Text, FieldMethod))
    private val pluginName =
        pbm.add(PreferenceBinding(Type.Text, FieldPluginName).apply { disable = true })
    private val pluginConfig =
        pbm.add(PreferenceBinding(Type.Text, FieldPluginConfig).apply { disable = true })
    private val sUoT = pbm.add(PreferenceBinding(Type.Bool, FieldUdpOverTcp))

    override fun ShadowsocksBean.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putString(FieldPluginName, plugin.substringBefore(";"))
        DataStore.profileCacheStore.putString(FieldPluginConfig, plugin.substringAfter(";"))
    }

    override fun ShadowsocksBean.serialize() {
        pbm.fromCacheAll(this)
        val pn = pluginName.readStringFromCache()
        val pc = pluginConfig.readStringFromCache()
        plugin = if (pn.isNotBlank()) "$pn;$pc" else ""
    }

    override fun buildFormState(): YokhanFormState {
        return YokhanFormState(
            title = getString(R.string.profile_config),
            subtitle = "Shadowsocks",
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
                            id = FieldMethod,
                            label = getString(R.string.enc_method),
                            options = arrayOptions(R.array.ss_enc_method_value),
                            selectedOptionId = cacheString(FieldMethod),
                        ),
                        YokhanFormField.Password(
                            id = FieldPassword,
                            label = getString(R.string.password),
                            value = cacheString(FieldPassword),
                        ),
                    ),
                ),
                YokhanFormSection(
                    id = "plugin",
                    title = getString(R.string.plugin).uppercase(),
                    fields = listOf(
                        YokhanFormField.Select(
                            id = FieldPluginName,
                            label = getString(R.string.plugin),
                            options = arrayOptions(R.array.box_shadowsocks_plugins),
                            selectedOptionId = cacheString(FieldPluginName),
                        ),
                        YokhanFormField.Text(
                            id = FieldPluginConfig,
                            label = getString(R.string.plugin_configure),
                            value = cacheString(FieldPluginConfig),
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
        DataStore.profileCacheStore.putString(FieldName, state.textValue(FieldName))
        DataStore.profileCacheStore.putString(FieldServerAddress, state.textValue(FieldServerAddress))
        DataStore.profileCacheStore.putString(FieldServerPort, state.textValue(FieldServerPort))
        DataStore.profileCacheStore.putString(FieldMethod, state.selectValue(FieldMethod))
        DataStore.profileCacheStore.putString(FieldPassword, state.passwordValue(FieldPassword))
        DataStore.profileCacheStore.putString(FieldPluginName, state.selectValue(FieldPluginName))
        DataStore.profileCacheStore.putString(FieldPluginConfig, state.textValue(FieldPluginConfig))
        DataStore.profileCacheStore.putBoolean(FieldUdpOverTcp, state.switchValue(FieldUdpOverTcp))
        DataStore.dirty = state.dirty
    }

    private companion object {
        const val FieldName = "name"
        const val FieldServerAddress = "serverAddress"
        const val FieldServerPort = "serverPort"
        const val FieldPassword = "password"
        const val FieldMethod = "method"
        const val FieldPluginName = "pluginName"
        const val FieldPluginConfig = "pluginConfig"
        const val FieldUdpOverTcp = "sUoT"
    }
}
