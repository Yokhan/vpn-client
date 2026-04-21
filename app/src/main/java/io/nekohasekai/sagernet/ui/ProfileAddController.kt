package io.nekohasekai.sagernet.ui

import android.content.Intent
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HttpSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HysteriaSettingsActivity
import io.nekohasekai.sagernet.ui.profile.MieruSettingsActivity
import io.nekohasekai.sagernet.ui.profile.NaiveSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SSHSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanGoSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TuicSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import io.nekohasekai.sagernet.ui.profile.WireGuardSettingsActivity
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity

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
