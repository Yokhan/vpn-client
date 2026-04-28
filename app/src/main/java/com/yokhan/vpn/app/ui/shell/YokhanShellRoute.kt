package com.yokhan.vpn.app.ui.shell

import androidx.annotation.IdRes
import io.nekohasekai.sagernet.R

enum class YokhanShellRoute(@IdRes val legacyId: Int) {
    Home(R.id.nav_home),
    Configuration(R.id.nav_configuration),
    Group(R.id.nav_group),
    Route(R.id.nav_route),
    Settings(R.id.nav_settings),
    Traffic(R.id.nav_traffic),
    Tools(R.id.nav_tools),
    Logcat(R.id.nav_logcat),
    Faq(R.id.nav_faq),
    About(R.id.nav_about),
    Promotion(R.id.nav_tuiguang);

    companion object {
        fun fromLegacyId(@IdRes id: Int): YokhanShellRoute? {
            return values().firstOrNull { it.legacyId == id }
        }
    }
}
