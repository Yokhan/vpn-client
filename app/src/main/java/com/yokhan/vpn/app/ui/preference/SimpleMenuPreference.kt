/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yokhan.vpn.app.ui.preference

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.Spinner
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.preference.DropDownPreference
import androidx.preference.PreferenceViewHolder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr

/**
 * Bend [DropDownPreference] to support
 * [Simple Menus](https://material.google.com/components/menus.html#menus-behavior).
 */


open class SimpleMenuPreference
@JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.dropdownPreferenceStyle,
    defStyleRes: Int = 0
) : DropDownPreference(context!!, attrs, defStyleAttr, defStyleRes) {

    private lateinit var mAdapter: SimpleMenuAdapter

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val mSpinner = holder.itemView.findViewById<Spinner>(R.id.spinner)
        mSpinner.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    override fun createAdapter(): ArrayAdapter<CharSequence?> {
        mAdapter = SimpleMenuAdapter(getContext())
        return mAdapter
    }

    override fun setValue(value: String?) {
        super.setValue(value)
        if (::mAdapter.isInitialized) {
            mAdapter.currentPosition = entryValues.indexOf(value)
            mAdapter.notifyDataSetChanged()
        }
    }

    private class SimpleMenuAdapter(context: Context) :
        ArrayAdapter<CharSequence?>(context, 0) {

        var currentPosition = -1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return bindView(position, convertView)
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = bindView(position, convertView)
            if (position == currentPosition) {
                view.setBackgroundColor(context.getColorAttr(R.attr.colorMaterial100))
            } else {
                view.setBackgroundColor(
                    ContextCompat.getColor(
                        context,
                        R.color.preference_simple_menu_background
                    )
                )
            }
            return view
        }

        private fun bindView(position: Int, convertView: View?): CheckedTextView {
            return ((convertView as? CheckedTextView) ?: CheckedTextView(context).apply {
                ellipsize = TextUtils.TruncateAt.MARQUEE
                gravity = Gravity.CENTER
                minWidth = dp(112)
                setSingleLine(true)
                setTextColor(ContextCompat.getColor(context, R.color.dashboard_space_text_primary))
                textSize = 13f
                typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono)
                setPadding(dp(16), 0, dp(16), 0)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(48),
                )
            }).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.preference_simple_menu_background))
                isChecked = position == currentPosition
                text = getItem(position)
            }
        }

        private fun dp(value: Int): Int {
            return (value * context.resources.displayMetrics.density).toInt()
        }
    }
}
