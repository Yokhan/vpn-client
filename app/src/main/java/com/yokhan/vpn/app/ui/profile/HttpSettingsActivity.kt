package com.yokhan.gates.app.ui.profile

import io.nekohasekai.sagernet.fmt.http.HttpBean

class HttpSettingsActivity : StandardV2RaySettingsActivity() {

    override fun createEntity() = HttpBean()

}
