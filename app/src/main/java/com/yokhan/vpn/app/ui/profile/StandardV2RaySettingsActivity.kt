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
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean

abstract class StandardV2RaySettingsActivity : ComposeProfileSettingsActivity<StandardV2RayBean>() {

    private var tmpBean: StandardV2RayBean? = null

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, FieldName))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, FieldServerAddress))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, FieldServerPort))
    private val uuid = pbm.add(PreferenceBinding(Type.Text, FieldUuid))
    private val username = pbm.add(PreferenceBinding(Type.Text, FieldUsername))
    private val password = pbm.add(PreferenceBinding(Type.Text, FieldPassword))
    private val alterId = pbm.add(PreferenceBinding(Type.TextToInt, FieldAlterId))
    private val encryption = pbm.add(PreferenceBinding(Type.Text, FieldEncryption))
    private val type = pbm.add(PreferenceBinding(Type.Text, FieldType))
    private val host = pbm.add(PreferenceBinding(Type.Text, FieldHost))
    private val path = pbm.add(PreferenceBinding(Type.Text, FieldPath))
    private val packetEncoding = pbm.add(PreferenceBinding(Type.TextToInt, FieldPacketEncoding))
    private val wsMaxEarlyData = pbm.add(PreferenceBinding(Type.TextToInt, FieldWsMaxEarlyData))
    private val earlyDataHeaderName = pbm.add(PreferenceBinding(Type.Text, FieldEarlyDataHeaderName))
    private val security = pbm.add(PreferenceBinding(Type.Text, FieldSecurity))
    private val sni = pbm.add(PreferenceBinding(Type.Text, FieldSni))
    private val alpn = pbm.add(PreferenceBinding(Type.Text, FieldAlpn))
    private val certificates = pbm.add(PreferenceBinding(Type.Text, FieldCertificates))
    private val allowInsecure = pbm.add(PreferenceBinding(Type.Bool, FieldAllowInsecure))
    private val utlsFingerprint = pbm.add(PreferenceBinding(Type.Text, FieldUtlsFingerprint))
    private val realityPubKey = pbm.add(PreferenceBinding(Type.Text, FieldRealityPubKey))
    private val realityShortId = pbm.add(PreferenceBinding(Type.Text, FieldRealityShortId))
    private val enableECH = pbm.add(PreferenceBinding(Type.Bool, FieldEnableEch))
    private val echConfig = pbm.add(PreferenceBinding(Type.Text, FieldEchConfig))
    private val enableMux = pbm.add(PreferenceBinding(Type.Bool, FieldEnableMux))
    private val muxPadding = pbm.add(PreferenceBinding(Type.Bool, FieldMuxPadding))
    private val muxType = pbm.add(PreferenceBinding(Type.TextToInt, FieldMuxType))
    private val muxConcurrency = pbm.add(PreferenceBinding(Type.TextToInt, FieldMuxConcurrency))

    override fun StandardV2RayBean.init() {
        if (this is TrojanBean) {
            this@StandardV2RaySettingsActivity.uuid.fieldName = FieldPassword
            this@StandardV2RaySettingsActivity.password.disable = true
        }
        tmpBean = this
        pbm.writeToCacheAll(this)
    }

    override fun StandardV2RayBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun buildFormState(): YokhanFormState {
        val bean = tmpBean
        val isHttp = bean is HttpBean
        val isVmess = bean is VMessBean && bean.isVLESS == false
        val isVless = bean?.isVLESS == true
        val isTrojan = bean is TrojanBean
        return YokhanFormState(
            title = getString(R.string.profile_config),
            subtitle = when {
                isHttp -> "HTTP"
                isTrojan -> "Trojan"
                isVless -> "VLESS"
                else -> "VMess"
            },
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
                            id = FieldUsername,
                            label = getString(R.string.username_opt),
                            value = cacheString(FieldUsername),
                            visible = isHttp,
                        ),
                        YokhanFormField.Password(
                            id = FieldPassword,
                            label = getString(R.string.password_opt),
                            value = cacheString(FieldPassword),
                            visible = isHttp,
                        ),
                        YokhanFormField.Password(
                            id = FieldUuid,
                            label = if (isTrojan) getString(R.string.password) else getString(R.string.uuid),
                            value = cacheString(FieldUuid),
                            visible = !isHttp,
                            required = true,
                        ),
                        YokhanFormField.Number(
                            id = FieldAlterId,
                            label = getString(R.string.alter_id),
                            value = cacheString(FieldAlterId),
                            visible = isVmess,
                        ),
                        YokhanFormField.Select(
                            id = FieldEncryption,
                            label = if (isVless) getString(R.string.xtls_flow) else getString(R.string.encryption),
                            options = if (isVless) {
                                arrayOptions(R.array.xtls_flow_value)
                            } else {
                                arrayOptions(R.array.vmess_encryption_value)
                            },
                            selectedOptionId = cacheString(FieldEncryption).ifBlank { if (isVless) "" else "auto" },
                            visible = isVmess || isVless,
                        ),
                        YokhanFormField.Select(
                            id = FieldPacketEncoding,
                            label = getString(R.string.packet_encoding),
                            options = arrayOptions(R.array.packet_encoding_entry, R.array.int_array_3),
                            selectedOptionId = cacheString(FieldPacketEncoding).ifBlank { "0" },
                            visible = isVmess || isVless,
                        ),
                        YokhanFormField.Select(
                            id = FieldType,
                            label = getString(R.string.network),
                            options = arrayOptions(R.array.networks_value),
                            selectedOptionId = cacheString(FieldType).ifBlank { "tcp" },
                            visible = !isHttp,
                        ),
                        YokhanFormField.Text(
                            id = FieldHost,
                            label = getString(R.string.http_host),
                            value = cacheString(FieldHost),
                        ),
                        YokhanFormField.Text(
                            id = FieldPath,
                            label = getString(R.string.http_path),
                            value = cacheString(FieldPath),
                        ),
                        YokhanFormField.Select(
                            id = FieldSecurity,
                            label = getString(R.string.security),
                            options = arrayOptions(R.array.transport_layer_encryption_value),
                            selectedOptionId = cacheString(FieldSecurity).ifBlank { if (isTrojan) "tls" else "none" },
                        ),
                    ),
                ),
                YokhanFormSection(
                    id = "websocket",
                    title = getString(R.string.cag_ws).uppercase(),
                    fields = listOf(
                        YokhanFormField.Number(
                            id = FieldWsMaxEarlyData,
                            label = getString(R.string.ws_max_early_data),
                            value = cacheString(FieldWsMaxEarlyData).ifBlank { "0" },
                        ),
                        YokhanFormField.Text(
                            id = FieldEarlyDataHeaderName,
                            label = getString(R.string.early_data_header_name),
                            value = cacheString(FieldEarlyDataHeaderName),
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
                    ),
                ),
                YokhanFormSection(
                    id = "tls-camouflage",
                    title = getString(R.string.tls_camouflage_settings).uppercase(),
                    fields = listOf(
                        YokhanFormField.Select(
                            id = FieldUtlsFingerprint,
                            label = getString(R.string.utls_fingerprint),
                            options = arrayOptions(R.array.utls_fingerprint_entry),
                            selectedOptionId = cacheString(FieldUtlsFingerprint),
                        ),
                        YokhanFormField.Text(
                            id = FieldRealityPubKey,
                            label = "Reality Public Key",
                            value = cacheString(FieldRealityPubKey),
                        ),
                        YokhanFormField.Text(
                            id = FieldRealityShortId,
                            label = "Reality ShortId",
                            value = cacheString(FieldRealityShortId),
                        ),
                    ),
                ),
                YokhanFormSection(
                    id = "mux",
                    title = getString(R.string.mux_preference).uppercase(),
                    fields = listOf(
                        YokhanFormField.Switch(
                            id = FieldEnableMux,
                            label = getString(R.string.enable_mux),
                            helper = getString(R.string.mux_sum),
                            checked = cacheBoolean(FieldEnableMux),
                        ),
                        YokhanFormField.Select(
                            id = FieldMuxType,
                            label = getString(R.string.mux_type),
                            options = arrayOptions(R.array.mux_type, R.array.int_array_3),
                            selectedOptionId = cacheString(FieldMuxType).ifBlank { "0" },
                        ),
                        YokhanFormField.Number(
                            id = FieldMuxConcurrency,
                            label = getString(R.string.mux_concurrency),
                            value = cacheString(FieldMuxConcurrency).ifBlank { "1" },
                        ),
                        YokhanFormField.Switch(
                            id = FieldMuxPadding,
                            label = getString(R.string.padding),
                            checked = cacheBoolean(FieldMuxPadding),
                        ),
                    ),
                ),
                YokhanFormSection(
                    id = "ech",
                    title = "ECH",
                    fields = listOf(
                        YokhanFormField.Switch(
                            id = FieldEnableEch,
                            label = getString(R.string.enable),
                            checked = cacheBoolean(FieldEnableEch),
                        ),
                        YokhanFormField.Text(
                            id = FieldEchConfig,
                            label = getString(R.string.ech_config),
                            value = cacheString(FieldEchConfig),
                            mode = YokhanFormTextMode.Multiline,
                        ),
                    ),
                ),
            ),
        )
    }

    override fun validateFormState(state: YokhanFormState): YokhanFormState {
        val network = state.selectValue(FieldType).ifBlank { "tcp" }
        val security = state.selectValue(FieldSecurity)
        val tls = "tls" in security
        val hostVisible = network == "http" || network == "ws" || network == "httpupgrade"
        val pathVisible = hostVisible || network == "grpc"
        val wsVisible = network == "ws"
        return state.copy(
            sections = state.sections.map { section ->
                section.copy(
                    fields = section.fields.map { field ->
                        when (field.id) {
                            FieldHost -> (field as YokhanFormField.Text).copy(
                                label = when (network) {
                                    "ws" -> getString(R.string.ws_host)
                                    "httpupgrade" -> getString(R.string.http_upgrade_host)
                                    else -> getString(R.string.http_host)
                                },
                                visible = hostVisible,
                            )

                            FieldPath -> (field as YokhanFormField.Text).copy(
                                label = when (network) {
                                    "ws" -> getString(R.string.ws_path)
                                    "grpc" -> getString(R.string.grpc_service_name)
                                    "httpupgrade" -> getString(R.string.http_upgrade_path)
                                    else -> getString(R.string.http_path)
                                },
                                visible = pathVisible,
                            )

                            FieldWsMaxEarlyData,
                            FieldEarlyDataHeaderName,
                            -> when (field) {
                                is YokhanFormField.Number -> field.copy(visible = wsVisible)
                                is YokhanFormField.Text -> field.copy(visible = wsVisible)
                                else -> field
                            }

                            FieldSni,
                            FieldAlpn,
                            FieldCertificates,
                            FieldUtlsFingerprint,
                            FieldRealityPubKey,
                            FieldRealityShortId,
                            FieldEnableEch,
                            FieldEchConfig,
                            -> setVisible(field, tls)

                            FieldAllowInsecure -> (field as YokhanFormField.Switch).copy(visible = tls)
                            else -> field
                        }
                    },
                )
            },
        )
    }

    override fun writeFormStateToCache(state: YokhanFormState) {
        DataStore.profileCacheStore.putString(FieldName, state.textValue(FieldName))
        DataStore.profileCacheStore.putString(FieldServerAddress, state.textValue(FieldServerAddress))
        DataStore.profileCacheStore.putString(FieldServerPort, state.textValue(FieldServerPort))
        DataStore.profileCacheStore.putString(FieldUuid, state.passwordValue(FieldUuid))
        DataStore.profileCacheStore.putString(FieldUsername, state.textValue(FieldUsername))
        DataStore.profileCacheStore.putString(FieldPassword, state.passwordValue(FieldPassword))
        DataStore.profileCacheStore.putString(FieldAlterId, state.textValue(FieldAlterId))
        DataStore.profileCacheStore.putString(FieldEncryption, state.selectValue(FieldEncryption))
        DataStore.profileCacheStore.putString(FieldPacketEncoding, state.selectValue(FieldPacketEncoding))
        DataStore.profileCacheStore.putString(FieldType, state.selectValue(FieldType).ifBlank { "tcp" })
        DataStore.profileCacheStore.putString(FieldHost, state.textValue(FieldHost))
        DataStore.profileCacheStore.putString(FieldPath, state.textValue(FieldPath))
        DataStore.profileCacheStore.putString(FieldWsMaxEarlyData, state.textValue(FieldWsMaxEarlyData))
        DataStore.profileCacheStore.putString(FieldEarlyDataHeaderName, state.textValue(FieldEarlyDataHeaderName))
        DataStore.profileCacheStore.putString(FieldSecurity, state.selectValue(FieldSecurity))
        DataStore.profileCacheStore.putString(FieldSni, state.textValue(FieldSni))
        DataStore.profileCacheStore.putString(FieldAlpn, state.textValue(FieldAlpn))
        DataStore.profileCacheStore.putString(FieldCertificates, state.textValue(FieldCertificates))
        DataStore.profileCacheStore.putBoolean(FieldAllowInsecure, state.switchValue(FieldAllowInsecure))
        DataStore.profileCacheStore.putString(FieldUtlsFingerprint, state.selectValue(FieldUtlsFingerprint))
        DataStore.profileCacheStore.putString(FieldRealityPubKey, state.textValue(FieldRealityPubKey))
        DataStore.profileCacheStore.putString(FieldRealityShortId, state.textValue(FieldRealityShortId))
        DataStore.profileCacheStore.putBoolean(FieldEnableEch, state.switchValue(FieldEnableEch))
        DataStore.profileCacheStore.putString(FieldEchConfig, state.textValue(FieldEchConfig))
        DataStore.profileCacheStore.putBoolean(FieldEnableMux, state.switchValue(FieldEnableMux))
        DataStore.profileCacheStore.putBoolean(FieldMuxPadding, state.switchValue(FieldMuxPadding))
        DataStore.profileCacheStore.putString(FieldMuxType, state.selectValue(FieldMuxType))
        DataStore.profileCacheStore.putString(FieldMuxConcurrency, state.textValue(FieldMuxConcurrency))
        DataStore.dirty = state.dirty
    }

    private fun setVisible(field: YokhanFormField, visible: Boolean): YokhanFormField {
        return when (field) {
            is YokhanFormField.Text -> field.copy(visible = visible)
            is YokhanFormField.Password -> field.copy(visible = visible)
            is YokhanFormField.Number -> field.copy(visible = visible)
            is YokhanFormField.Port -> field.copy(visible = visible)
            is YokhanFormField.Switch -> field.copy(visible = visible)
            is YokhanFormField.Select -> field.copy(visible = visible)
            is YokhanFormField.Info -> field.copy(visible = visible)
        }
    }

    private companion object {
        const val FieldName = "name"
        const val FieldServerAddress = "serverAddress"
        const val FieldServerPort = "serverPort"
        const val FieldUuid = "uuid"
        const val FieldUsername = "username"
        const val FieldPassword = "password"
        const val FieldAlterId = "alterId"
        const val FieldEncryption = "encryption"
        const val FieldType = "type"
        const val FieldHost = "host"
        const val FieldPath = "path"
        const val FieldPacketEncoding = "packetEncoding"
        const val FieldWsMaxEarlyData = "wsMaxEarlyData"
        const val FieldEarlyDataHeaderName = "earlyDataHeaderName"
        const val FieldSecurity = "security"
        const val FieldSni = "sni"
        const val FieldAlpn = "alpn"
        const val FieldCertificates = "certificates"
        const val FieldAllowInsecure = "allowInsecure"
        const val FieldUtlsFingerprint = "utlsFingerprint"
        const val FieldRealityPubKey = "realityPubKey"
        const val FieldRealityShortId = "realityShortId"
        const val FieldEnableEch = "enableECH"
        const val FieldEchConfig = "echConfig"
        const val FieldEnableMux = "enableMux"
        const val FieldMuxPadding = "muxPadding"
        const val FieldMuxType = "muxType"
        const val FieldMuxConcurrency = "muxConcurrency"
    }
}
