package com.yokhan.vpn.app.ui.profile

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.DialogInterface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.blacksquircle.ui.editorkit.insert
import com.blacksquircle.ui.language.json.JsonLanguage
import com.blacksquircle.ui.editorkit.widget.TextProcessor
import com.blacksquircle.ui.editorkit.widget.TextScroller
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yokhan.vpn.app.ui.createYokhanAppBar
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.toStringPretty
import com.yokhan.vpn.app.ui.ThemedActivity
import io.nekohasekai.sagernet.widget.ListListener
import com.yokhan.vpn.app.ui.preference.ExtendedKeyboard
import org.json.JSONObject

class ConfigEditActivity : ThemedActivity() {

    var dirty = false
    var key = Key.SERVER_CONFIG
    var useConfigStore = false

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                (requireActivity() as ConfigEditActivity).saveAndExit()
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    private lateinit var editor: TextProcessor
    private lateinit var keyboardContainer: LinearLayout
    private lateinit var actionTab: ImageView
    private lateinit var actionUndo: ImageView
    private lateinit var actionRedo: ImageView
    private lateinit var actionFormat: ImageView

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent?.extras?.apply {
            getString("key")?.let { key = it }
            getString("useConfigStore")?.let { useConfigStore = true }
        }

        val root = createEditorHost()
        setContentView(root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.config_settings)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        onBackPressedDispatcher.addCallback(this) {
            if (dirty) {
                UnsavedChangesDialogFragment().apply { key() }
                    .show(supportFragmentManager, null)
            } else {
                finish()
            }
        }

        editor.apply {
            language = JsonLanguage()
            setHorizontallyScrolling(true)
            if (useConfigStore) {
                setTextContent(DataStore.configurationStore.getString(key) ?: "")
            } else {
                setTextContent(DataStore.profileCacheStore.getString(key) ?: "")
            }
            addTextChangedListener {
                if (!dirty) {
                    dirty = true
                    DataStore.dirty = true
                }
            }
        }

        actionTab.setOnClickListener {
            try {
                editor.insert(editor.tab())
            } catch (e: Exception) {
            }
        }
        actionUndo.setOnClickListener {
            try {
                editor.undo()
            } catch (_: Exception) {
            }
        }
        actionRedo.setOnClickListener {
            try {
                editor.redo()
            } catch (_: Exception) {
            }
        }
        actionFormat.setOnClickListener {
            formatText()?.let {
                editor.setTextContent(it)
            }
        }

        val extendedKeyboard = findViewById<ExtendedKeyboard>(R.id.extended_keyboard)
        extendedKeyboard.setKeyListener { char ->
            try {
                editor.insert(char)
            } catch (e: Exception) {
            }
        }
        extendedKeyboard.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        extendedKeyboard.setHasFixedSize(true)
        extendedKeyboard.submitList("{},:_\"".map { it.toString() })
        extendedKeyboard.setBackgroundColor(getColorAttr(R.attr.primaryOrTextPrimary))

        ViewCompat.setOnApplyWindowInsetsListener(keyboardContainer) { v, windowInsets ->
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())
            v.updateLayoutParams<MarginLayoutParams> {
                // systemBar insets are applied to the bottom of the keyboard
                if (imeVisible) {
                    bottomMargin = imeInsets.bottom - systemBarInsets.bottom
                } else {
                    bottomMargin = 0
                }
            }

            WindowInsetsCompat.CONSUMED
        }

        ViewCompat.setOnApplyWindowInsetsListener(root, ListListener)
    }

    private fun createEditorHost(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.color.dashboard_bg_off)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            addView(createYokhanAppBar())
            addView(View(context).apply {
                setBackgroundResource(R.color.dashboard_divider_light)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1,
                )
            })
            addView(createEditorFrame())
            addView(createKeyboardContainer())
        }
    }

    private fun createEditorFrame(): FrameLayout {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            editor = TextProcessor(context).apply {
                id = R.id.editor
                background = null
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            addView(editor)
            addView(TextScroller(context).apply {
                id = R.id.scroller
                layoutParams = FrameLayout.LayoutParams(
                    dp(30),
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.END,
                )
            })
        }
    }

    private fun createKeyboardContainer(): LinearLayout {
        return LinearLayout(this).apply {
            id = R.id.keyboard_container
            keyboardContainer = this
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.color.dashboard_bg_off)
            elevation = 2f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            actionTab = addEditorAction(R.id.action_tab, R.drawable.baseline_keyboard_tab_24)
            addView(ExtendedKeyboard(context).apply {
                id = R.id.extended_keyboard
                setBackgroundResource(R.color.dashboard_bg_off)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            })
            actionUndo = addEditorAction(R.id.action_undo, R.drawable.baseline_undo_24)
            actionRedo = addEditorAction(R.id.action_redo, R.drawable.baseline_redo_24)
            actionFormat = addEditorAction(R.id.action_format, R.drawable.ic_baseline_format_align_left_24)
            addView(View(context).apply {
                setBackgroundResource(R.color.dashboard_divider_light)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f,
                )
            })
        }
    }

    private fun LinearLayout.addEditorAction(idValue: Int, iconRes: Int): ImageView {
        return ImageView(context).apply {
            id = idValue
            setBackgroundResource(R.color.dashboard_divider_light)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.dashboard_text_primary)
            )
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            addView(this)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    fun formatText(): String? {
        try {
            val txt = editor.text.toString()
            if (txt.isBlank()) {
                return ""
            }
            return JSONObject(txt).toStringPretty()
        } catch (e: Exception) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.error_title)
                .setMessage(e.readableMessage).show()
            return null
        }
    }

    fun saveAndExit() {
        formatText()?.let {
            if (useConfigStore) {
                DataStore.configurationStore.putString(key, it)
            } else {
                DataStore.profileCacheStore.putString(key, it)
            }
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_apply_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_apply -> {
                saveAndExit()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
