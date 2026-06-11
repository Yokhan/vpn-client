package com.yokhan.gates.app.widget

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.res.TypedArrayUtils
import androidx.preference.EditTextPreference
import io.nekohasekai.sagernet.R

class PasswordPreference
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = TypedArrayUtils.getAttr(
        context,
        R.attr.editTextPreferenceStyle,
        android.R.attr.editTextPreferenceStyle,
    ),
    defStyleRes: Int = 0,
) : EditTextPreference(context, attrs, defStyleAttr, defStyleRes) {

    init {
        setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            editText.setSingleLine(true)
            editText.typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono)
        }
    }
}
