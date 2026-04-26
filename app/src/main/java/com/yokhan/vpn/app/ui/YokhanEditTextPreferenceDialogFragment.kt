package com.yokhan.vpn.app.ui

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R

class YokhanEditTextPreferenceDialogFragment : EditTextPreferenceDialogFragmentCompat() {

    companion object {
        private const val ARG_KEY = "key"
        private const val ARG_PASSWORD = "password"

        fun newInstance(key: String): YokhanEditTextPreferenceDialogFragment {
            return YokhanEditTextPreferenceDialogFragment().apply {
                arguments = Bundle(1).apply {
                    putString(ARG_KEY, key)
                }
            }
        }

        fun newPasswordInstance(key: String): YokhanEditTextPreferenceDialogFragment {
            return YokhanEditTextPreferenceDialogFragment().apply {
                arguments = Bundle(2).apply {
                    putString(ARG_KEY, key)
                    putBoolean(ARG_PASSWORD, true)
                }
            }
        }
    }

    override fun onCreateDialogView(context: Context): View {
        if (requireArguments().getBoolean(ARG_PASSWORD)) {
            return createPasswordDialogView(context)
        }
        return createUrlDialogView(context)
    }

    private fun createUrlDialogView(context: Context): View {
        return ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundColor(ContextCompat.getColor(context, R.color.dashboard_bg_off))
            setPadding(0, dp(16), 0, 0)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextInputLayout(context).apply {
                    id = R.id.input_layout
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        setMargins(dp(20), 0, dp(20), 0)
                    }
                    addView(TextInputEditText(context).apply {
                        id = android.R.id.edit
                        inputType = InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_VARIATION_URI or
                            InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        minHeight = dp(48)
                        setPadding(paddingLeft, dp(12), paddingRight, paddingBottom)
                        typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono)
                        setTextColor(ContextCompat.getColor(context, R.color.dashboard_text_primary))
                        setHintTextColor(ContextCompat.getColor(context, R.color.dashboard_text_secondary))
                    })
                })
                addView(LinearLayout(context).apply {
                    id = R.id.concurrent_layout
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        setMargins(dp(20), dp(12), dp(20), 0)
                    }
                    addView(TextView(context).apply {
                        text = getString(R.string.test_concurrency)
                        typeface = ResourcesCompat.getFont(context, R.font.golos_text)
                        setTextColor(ContextCompat.getColor(context, R.color.dashboard_text_secondary))
                        textSize = 13f
                    })
                    addView(TextInputEditText(context).apply {
                        id = R.id.edit_concurrent
                        inputType = InputType.TYPE_CLASS_NUMBER
                        minHeight = dp(48)
                        setPadding(paddingLeft, dp(12), paddingRight, paddingBottom)
                        setSingleLine(true)
                        typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono)
                        setTextColor(ContextCompat.getColor(context, R.color.dashboard_text_primary))
                        setHintTextColor(ContextCompat.getColor(context, R.color.dashboard_text_secondary))
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        ).apply {
                            setMargins(dp(12), 0, 0, 0)
                        }
                    })
                })
            })
        }
    }

    private fun createPasswordDialogView(context: Context): View {
        return ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundColor(ContextCompat.getColor(context, R.color.dashboard_bg_off))
            setPadding(0, dp(48), 0, dp(48))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    id = android.R.id.message
                    typeface = ResourcesCompat.getFont(context, R.font.golos_text)
                    setTextColor(ContextCompat.getColor(context, R.color.dashboard_text_secondary))
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        setMargins(dp(24), 0, dp(24), dp(48))
                    }
                })
                addView(TextInputLayout(context).apply {
                    endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        setMargins(dp(20), 0, dp(20), 0)
                    }
                    addView(TextInputEditText(context).apply {
                        id = android.R.id.edit
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        minHeight = dp(48)
                        setPadding(paddingLeft, dp(12), paddingRight, paddingBottom)
                        setSingleLine(true)
                        typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono)
                        setTextColor(ContextCompat.getColor(context, R.color.dashboard_text_primary))
                        setHintTextColor(ContextCompat.getColor(context, R.color.dashboard_text_secondary))
                    })
                })
            })
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
