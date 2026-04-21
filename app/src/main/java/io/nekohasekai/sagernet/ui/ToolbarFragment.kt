package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R

open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    lateinit var toolbar: Toolbar

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.toolbar)
        val mainActivity = activity as MainActivity
        if (parentFragmentManager.backStackEntryCount > 0) {
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationOnClickListener {
                mainActivity.onBackPressedDispatcher.onBackPressed()
            }
        } else {
            toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
            toolbar.setNavigationOnClickListener {
                mainActivity.binding.drawerLayout.openDrawer(GravityCompat.START)
            }
        }
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
    open fun onBackPressed(): Boolean = false
}
