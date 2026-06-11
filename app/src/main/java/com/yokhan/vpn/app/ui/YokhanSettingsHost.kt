package com.yokhan.gates.app.ui

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentContainerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import io.nekohasekai.sagernet.R

fun Context.createYokhanSettingsHost(
    includeConfigurationList: Boolean = false,
    useComposeContainer: Boolean = false,
): LinearLayout {
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.color.dashboard_space_bg)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        addView(createYokhanAppBar())
        addView(createSettingsContainer(includeConfigurationList, useComposeContainer))

        if (includeConfigurationList) {
            addView(createSettingsDivider())
            addView(createConfigurationList())
        }
    }
}

fun Context.createYokhanAppBar(): AppBarLayout {
    return AppBarLayout(this).apply {
        id = R.id.appbar
        setBackgroundColor(Color.TRANSPARENT)
        elevation = 0f
        ViewCompat.setElevation(this, 0f)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            actionBarSize(),
        )
        addView(MaterialToolbar(context).apply {
            id = R.id.toolbar
            setBackgroundColor(Color.TRANSPARENT)
            setTitleTextColor(ContextCompat.getColor(context, R.color.dashboard_space_text_primary))
            elevation = 0f
            ViewCompat.setElevation(this, 0f)
            layoutParams = AppBarLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        })
    }
}

private fun Context.createSettingsContainer(
    wrapContent: Boolean,
    useComposeContainer: Boolean,
): View {
    val height = if (wrapContent) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
    val layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        height,
    )
    return if (useComposeContainer) {
        FrameLayout(this).apply {
            id = R.id.settings
            setBackgroundResource(R.color.dashboard_space_bg)
            this.layoutParams = layoutParams
        }
    } else {
        FragmentContainerView(this).apply {
            id = R.id.settings
            setBackgroundResource(R.color.dashboard_space_bg)
            this.layoutParams = layoutParams
        }
    }
}

private fun Context.createSettingsDivider(): View {
    return View(this).apply {
        id = R.id.list_cell
        setBackgroundResource(R.color.dashboard_divider_dark)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            resources.displayMetrics.density.coerceAtLeast(1f).toInt(),
        )
    }
}

private fun Context.createConfigurationList(): FastScrollRecyclerView {
    return FastScrollRecyclerView(this).apply {
        id = R.id.configuration_list
        setBackgroundResource(R.color.dashboard_space_bg)
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        setPadding(dp(2), dp(8), dp(2), dp(28))
        scrollBarSize = 0
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        )
    }
}

private fun Context.actionBarSize(): Int {
    val typedArray = obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize))
    return try {
        typedArray.getDimensionPixelSize(0, dp(56))
    } finally {
        typedArray.recycle()
    }
}

private fun Context.dp(value: Int): Int {
    return (value * resources.displayMetrics.density).toInt()
}
