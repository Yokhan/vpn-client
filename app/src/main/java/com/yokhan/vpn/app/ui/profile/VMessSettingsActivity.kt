package com.yokhan.vpn.app.ui.profile

import io.nekohasekai.sagernet.fmt.v2ray.VMessBean

class VMessSettingsActivity : StandardV2RaySettingsActivity() {

    override fun createEntity() = VMessBean().apply {
        if (intent?.getBooleanExtra("vless", false) == true) {
            alterId = -1
        }
    }

}