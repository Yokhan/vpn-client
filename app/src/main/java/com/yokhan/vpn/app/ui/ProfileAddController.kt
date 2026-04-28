package com.yokhan.vpn.app.ui

import android.content.Intent
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import com.yokhan.vpn.app.ui.profile.ChainSettingsActivity
import com.yokhan.vpn.app.ui.profile.AnyTLSSettingsActivity
import com.yokhan.vpn.app.ui.profile.ConfigSettingActivity
import com.yokhan.vpn.app.ui.profile.HttpSettingsActivity
import com.yokhan.vpn.app.ui.profile.HysteriaSettingsActivity
import com.yokhan.vpn.app.ui.profile.MieruSettingsActivity
import com.yokhan.vpn.app.ui.profile.NaiveSettingsActivity
import com.yokhan.vpn.app.ui.profile.SSHSettingsActivity
import com.yokhan.vpn.app.ui.profile.ShadowTLSSettingsActivity
import com.yokhan.vpn.app.ui.profile.ShadowsocksSettingsActivity
import com.yokhan.vpn.app.ui.profile.SocksSettingsActivity
import com.yokhan.vpn.app.ui.profile.TrojanGoSettingsActivity
import com.yokhan.vpn.app.ui.profile.TrojanSettingsActivity
import com.yokhan.vpn.app.ui.profile.TuicSettingsActivity
import com.yokhan.vpn.app.ui.profile.VMessSettingsActivity
import com.yokhan.vpn.app.ui.profile.WireGuardSettingsActivity

object ProfileAddController {

    private data class ManualEntry(
        val title: (Fragment) -> String,
        val intent: (Fragment) -> Intent,
    )

    private val manualEntries = listOf(
        ManualEntry({ it.getString(R.string.action_socks) }) {
            Intent(it.requireActivity(), SocksSettingsActivity::class.java)
        },
        ManualEntry({ it.getString(R.string.action_http) }) {
            Intent(it.requireActivity(), HttpSettingsActivity::class.java)
        },
        ManualEntry({ it.getString(R.string.action_shadowsocks) }) {
            Intent(it.requireActivity(), ShadowsocksSettingsActivity::class.java)
        },
        ManualEntry({ it.getString(R.string.action_vmess) }) {
            Intent(it.requireActivity(), VMessSettingsActivity::class.java)
        },
        ManualEntry({ "VLESS" }) {
            Intent(it.requireActivity(), VMessSettingsActivity::class.java).apply {
                putExtra("vless", true)
            }
        },
        ManualEntry({ it.getString(R.string.action_trojan) }) {
            Intent(it.requireActivity(), TrojanSettingsActivity::class.java)
        },
        ManualEntry({ it.getString(R.string.action_trojan_go) }) {
            Intent(it.requireActivity(), TrojanGoSettingsActivity::class.java)
        },
        ManualEntry({ it.getString(R.string.action_mieru) }) {
            Intent(it.requireActivity(), MieruSettingsActivity::class.java)
        },
        ManualEntry({ it.getString(R.string.action_naive) }) {
            Intent(it.requireActivity(), NaiveSettingsActivity::class.java)
        },
        ManualEntry({ it.getString(R.string.action_hysteria) }) {
            Intent(it.requireActivity(), HysteriaSettingsActivity::class.java)
        },
        ManualEntry({ it.getString(R.string.action_tuic) }) {
            Intent(it.requireActivity(), TuicSettingsActivity::class.java)
        },
        ManualEntry({ it.getString(R.string.action_shadowtls) }) {
            Intent(it.requireActivity(), ShadowTLSSettingsActivity::class.java)
        },
        ManualEntry({ it.getString(R.string.action_anytls) }) {
            Intent(it.requireActivity(), AnyTLSSettingsActivity::class.java)
        },
        ManualEntry({ it.getString(R.string.action_ssh) }) {
            Intent(it.requireActivity(), SSHSettingsActivity::class.java)
        },
        ManualEntry({ it.getString(R.string.action_wireguard) }) {
            Intent(it.requireActivity(), WireGuardSettingsActivity::class.java)
        },
        ManualEntry({ it.getString(R.string.custom_config) }) {
            Intent(it.requireActivity(), ConfigSettingActivity::class.java)
        },
        ManualEntry({ it.getString(R.string.proxy_chain) }) {
            Intent(it.requireActivity(), ChainSettingsActivity::class.java)
        },
    )

    fun showManualProfileChooser(fragment: Fragment) {
        val items = manualEntries.map { it.title(fragment) }.toTypedArray()
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.add_profile_methods_manual_settings)
            .setItems(items) { _, which ->
                fragment.startActivity(manualEntries[which].intent(fragment))
            }
            .show()
    }
}
