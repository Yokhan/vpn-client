package com.yokhan.vpn.app.ui

import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yokhan.vpn.app.ui.shell.YokhanManualProfileProtocol
import io.nekohasekai.sagernet.R

object ProfileAddController {

    private data class ManualEntry(
        val title: (Fragment) -> String,
        val protocol: YokhanManualProfileProtocol,
    )

    private val manualEntries = listOf(
        ManualEntry({ it.getString(R.string.action_socks) }, YokhanManualProfileProtocol.Socks),
        ManualEntry({ it.getString(R.string.action_http) }, YokhanManualProfileProtocol.Http),
        ManualEntry({ it.getString(R.string.action_shadowsocks) }, YokhanManualProfileProtocol.Shadowsocks),
        ManualEntry({ it.getString(R.string.action_vmess) }, YokhanManualProfileProtocol.VMess),
        ManualEntry({ "VLESS" }, YokhanManualProfileProtocol.VLESS),
        ManualEntry({ it.getString(R.string.action_trojan) }, YokhanManualProfileProtocol.Trojan),
        ManualEntry({ it.getString(R.string.action_trojan_go) }, YokhanManualProfileProtocol.TrojanGo),
        ManualEntry({ it.getString(R.string.action_mieru) }, YokhanManualProfileProtocol.Mieru),
        ManualEntry({ it.getString(R.string.action_naive) }, YokhanManualProfileProtocol.Naive),
        ManualEntry({ it.getString(R.string.action_hysteria) }, YokhanManualProfileProtocol.Hysteria),
        ManualEntry({ it.getString(R.string.action_tuic) }, YokhanManualProfileProtocol.Tuic),
        ManualEntry({ it.getString(R.string.action_shadowtls) }, YokhanManualProfileProtocol.ShadowTLS),
        ManualEntry({ it.getString(R.string.action_anytls) }, YokhanManualProfileProtocol.AnyTLS),
        ManualEntry({ it.getString(R.string.action_ssh) }, YokhanManualProfileProtocol.SSH),
        ManualEntry({ it.getString(R.string.action_wireguard) }, YokhanManualProfileProtocol.WireGuard),
        ManualEntry({ it.getString(R.string.custom_config) }, YokhanManualProfileProtocol.Config),
        ManualEntry({ it.getString(R.string.proxy_chain) }, YokhanManualProfileProtocol.Chain),
    )

    fun showManualProfileChooser(
        fragment: Fragment,
        onProfileSelected: (YokhanManualProfileProtocol) -> Unit,
    ) {
        val items = manualEntries.map { it.title(fragment) }.toTypedArray()
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.add_profile_methods_manual_settings)
            .setItems(items) { _, which ->
                onProfileSelected(manualEntries[which].protocol)
            }
            .show()
    }
}
