package com.yokhan.gates.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R

open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    lateinit var toolbar: Toolbar

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.toolbar) ?: return
        val mainActivity = activity as MainActivity
        if (parentFragmentManager.backStackEntryCount > 0) {
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationOnClickListener {
                mainActivity.onBackPressedDispatcher.onBackPressed()
            }
        } else {
            toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
            toolbar.setNavigationOnClickListener {
                mainActivity.openNavigationMenu()
            }
        }
        applyYokhanToolbarStyle(view)
    }

    private fun applyYokhanToolbarStyle(view: View) {
        val context = toolbar.context
        val titleColor = ContextCompat.getColor(context, R.color.dashboard_space_text_primary)
        toolbar.setBackgroundColor(Color.TRANSPARENT)
        toolbar.setTitleTextColor(titleColor)
        toolbar.navigationIcon?.setTint(titleColor)
        toolbar.elevation = 0f
        ViewCompat.setElevation(toolbar, 0f)

        view.findViewById<View>(R.id.appbar)?.let { appBar ->
            appBar.setBackgroundColor(Color.TRANSPARENT)
            appBar.elevation = 0f
            ViewCompat.setElevation(appBar, 0f)
        }

        ResourcesCompat.getFont(context, R.font.golos_text)?.let { typeface ->
            toolbar.post { toolbar.applyTitleTypeface(typeface) }
        }
    }

    private fun Toolbar.applyTitleTypeface(typeface: Typeface) {
        for (index in 0 until childCount) {
            (getChildAt(index) as? TextView)?.typeface = typeface
        }
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
    open fun onBackPressed(): Boolean = false
}
