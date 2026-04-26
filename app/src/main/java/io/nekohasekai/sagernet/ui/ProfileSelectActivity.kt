package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.fragment.app.FragmentContainerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity

class ProfileSelectActivity : ThemedActivity(),
    ConfigurationFragment.SelectCallback {

    companion object {
        const val EXTRA_SELECTED = "selected"
        const val EXTRA_PROFILE_ID = "id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createFragmentHost())

        val selected = intent.getParcelableExtra<ProxyEntity>(EXTRA_SELECTED)

        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_holder,
                ConfigurationFragment(true, selected, R.string.select_profile)
            )
            .commitAllowingStateLoss()
    }

    override fun returnProfile(profileId: Long) {
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_PROFILE_ID, profileId)
        })
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
