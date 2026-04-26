package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.ViewGroup
import androidx.fragment.app.FragmentContainerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher

class SwitchActivity : ThemedActivity(),
    ConfigurationFragment.SelectCallback {

    override val isDialog = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createFragmentHost())

        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_holder,
                ConfigurationFragment(true, null, R.string.action_switch)
            )
            .commitAllowingStateLoss()
    }

    override fun returnProfile(profileId: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = profileId
        runOnMainDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(profileId, true)
        }
        SagerNet.reloadServiceAsync()
        finish()
    }

    private fun createFragmentHost(): FragmentContainerView {
        return FragmentContainerView(this).apply {
            id = R.id.fragment_holder
            setBackgroundResource(R.color.dashboard_bg_off)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }
}
