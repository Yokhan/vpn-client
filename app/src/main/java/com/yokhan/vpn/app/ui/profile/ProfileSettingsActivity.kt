package com.yokhan.vpn.app.ui.profile

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.text.TextUtils
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yokhan.vpn.app.ui.YokhanEditTextPreferenceDialogFragment
import com.yokhan.vpn.app.ui.createYokhanSettingsHost
import com.yokhan.vpn.app.widget.PasswordPreference
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.*
import com.yokhan.vpn.app.ui.ThemedActivity
import io.nekohasekai.sagernet.widget.LinkOrContentPreference
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.parcelize.Parcelize
import kotlin.properties.Delegates

@Suppress("UNCHECKED_CAST")
abstract class ProfileSettingsActivity<T : AbstractBean>(
    private val includeConfigurationList: Boolean = false,
) : ThemedActivity(), OnPreferenceDataStoreChangeListener {

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    (requireActivity() as ProfileSettingsActivity<*>).saveAndExit()
                }
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    @Parcelize
    data class ProfileIdArg(val profileId: Long, val groupId: Long) : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<ProfileIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_confirm_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    ProfileManager.deleteProfile(arg.groupId, arg.profileId)
                }
                requireActivity().finish()
            }
            setNegativeButton(R.string.no, null)
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "id"
        const val EXTRA_IS_SUBSCRIPTION = "sub"
    }

    abstract fun createEntity(): T
    abstract fun T.init()
    abstract fun T.serialize()
    protected open fun useComposeProfileForm(): Boolean = false

    @Composable
    protected open fun ComposeProfileContent() {
    }

    protected var proxyEntity: ProxyEntity? = null
    private var editingGroupType: Int? = null
    private var basicGroupCount = 0
    private var movableGroups: List<ProxyGroup> = emptyList()
    protected var isSubscription by Delegates.notNull<Boolean>()
    private var composeCustomCallback: ((String) -> Unit)? = null
    private var composeCustomOutboundCallback: ((String) -> Unit)? = null
    private val composeCustomResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        composeCustomCallback?.let { callback ->
            callback(DataStore.serverCustom)
            DataStore.dirty = true
        }
    }
    private val composeCustomOutboundResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        composeCustomOutboundCallback?.let { callback ->
            callback(DataStore.serverCustomOutbound)
            DataStore.dirty = true
        }
    }

    private suspend fun reloadMenuState() {
        val groups = SagerDatabase.groupDao.allGroups()
        basicGroupCount = groups.count { it.type == GroupType.BASIC }
        editingGroupType = groups.find { it.id == DataStore.editingGroup }?.type
        movableGroups = proxyEntity?.let { entity ->
            groups.filter { it.type == GroupType.BASIC && it.id != entity.groupId }
        } ?: emptyList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createYokhanSettingsHost(includeConfigurationList, useComposeProfileForm()))
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.profile_config)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        onBackPressedDispatcher.addCallback(this) {
            if (DataStore.dirty) {
                UnsavedChangesDialogFragment().apply { key() }
                    .show(supportFragmentManager, null)
            } else {
                finish()
            }
        }

        val editingId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
        isSubscription = intent.getBooleanExtra(EXTRA_IS_SUBSCRIPTION, false)
        DataStore.editingId = editingId

        runOnDefaultDispatcher {
            if (savedInstanceState == null) {
                if (editingId == 0L) {
                    DataStore.editingGroup = DataStore.ensureSelectedGroupForImport()
                    createEntity().applyDefaultValues().init()
                } else {
                    proxyEntity = SagerDatabase.proxyDao.getById(editingId)
                    if (proxyEntity == null) {
                        onMainDispatcher {
                            finish()
                        }
                        return@runOnDefaultDispatcher
                    }
                    DataStore.editingGroup = proxyEntity!!.groupId
                    (proxyEntity!!.requireBean() as T).init()
                }
            } else if (editingId != 0L) {
                proxyEntity = SagerDatabase.proxyDao.getById(editingId)
            }

            reloadMenuState()

            onMainDispatcher {
                if (useComposeProfileForm()) {
                    mountComposeProfileForm()
                } else if (savedInstanceState == null) {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.settings, MyPreferenceFragmentCompat())
                        .commit()
                }
                invalidateOptionsMenu()
            }
        }

    }

    open suspend fun saveAndExit() {

        val editingId = DataStore.editingId
        if (editingId == 0L) {
            val editingGroup = DataStore.editingGroup
            ProfileManager.createProfile(editingGroup, createEntity().apply { serialize() })
        } else {
            if (proxyEntity == null) {
                finish()
                return
            }
            if (proxyEntity!!.id == DataStore.selectedProxy) {
                SagerNet.stopService()
            }
            ProfileManager.updateProfile(proxyEntity!!.apply { (requireBean() as T).serialize() })
        }
        finish()

    }

    val child by lazy { supportFragmentManager.findFragmentById(R.id.settings) as MyPreferenceFragmentCompat }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        menu.findItem(R.id.action_move)?.apply {
            if (DataStore.editingId != 0L // not new profile
                && editingGroupType == GroupType.BASIC // not in subscription group
                && basicGroupCount > 1 // have other basic group
            ) isVisible = true
        }
        menu.findItem(R.id.action_create_shortcut)?.apply {
            if (Build.VERSION.SDK_INT >= 26 && DataStore.editingId != 0L) {
                isVisible = true // not new profile
            }
        }
        // shared menu item
        menu.findItem(R.id.action_custom_outbound_json)?.isVisible = true
        menu.findItem(R.id.action_custom_config_json)?.isVisible = true
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (useComposeProfileForm()) {
            onComposeOptionsItemSelected(item)
        } else {
            child.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
        }
    }

    open fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
    }

    open fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
    }

    open fun PreferenceFragmentCompat.displayPreferenceDialog(preference: Preference): Boolean {
        return false
    }

    protected open fun onComposeOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_delete -> {
                if (DataStore.editingId == 0L) {
                    finish()
                } else {
                    DeleteConfirmationDialogFragment().apply {
                        arg(ProfileIdArg(DataStore.editingId, DataStore.editingGroup))
                        key()
                    }.show(supportFragmentManager, null)
                }
                true
            }

            R.id.action_apply -> {
                runOnDefaultDispatcher {
                    saveAndExit()
                }
                true
            }

            R.id.action_custom_outbound_json -> {
                proxyEntity?.apply {
                    val bean = requireBean()
                    DataStore.serverCustomOutbound = bean.customOutboundJson
                    composeCustomOutboundCallback = { bean.customOutboundJson = it }
                    composeCustomOutboundResult.launch(
                        Intent(this@ProfileSettingsActivity, ConfigEditActivity::class.java).apply {
                            putExtra("key", Key.SERVER_CUSTOM_OUTBOUND)
                        },
                    )
                }
                true
            }

            R.id.action_custom_config_json -> {
                proxyEntity?.apply {
                    val bean = requireBean()
                    DataStore.serverCustom = bean.customConfigJson
                    composeCustomCallback = { bean.customConfigJson = it }
                    composeCustomResult.launch(
                        Intent(this@ProfileSettingsActivity, ConfigEditActivity::class.java).apply {
                            putExtra("key", Key.SERVER_CUSTOM)
                        },
                    )
                }
                true
            }

            R.id.action_create_shortcut -> {
                val ent = proxyEntity ?: return true
                val shortcut = ShortcutInfoCompat.Builder(this, "shortcut-profile-${ent.id}")
                    .setShortLabel(ent.displayName())
                    .setLongLabel(ent.displayName())
                    .setIcon(
                        IconCompat.createWithResource(
                            this, R.drawable.ic_qu_shadowsocks_launcher
                        )
                    ).setIntent(Intent(
                        this, QuickToggleShortcut::class.java
                    ).apply {
                        action = Intent.ACTION_MAIN
                        putExtra("profile", ent.id)
                    }).build()
                ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
                true
            }

            R.id.action_move -> {
                showMoveProfileDialog()
                true
            }

            else -> false
        }
    }

    private fun mountComposeProfileForm() {
        findViewById<ViewGroup>(R.id.settings).apply {
            removeAllViews()
            addView(
                ComposeView(this@ProfileSettingsActivity).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setContent { ComposeProfileContent() }
                },
            )
        }
        DataStore.dirty = false
        DataStore.profileCacheStore.registerChangeListener(this)
    }

    private fun showMoveProfileDialog() {
        val ent = proxyEntity ?: return
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            movableGroups.forEach { group ->
                addView(createMoveGroupRow(group, ent))
            }
        }
        MaterialAlertDialogBuilder(this).setView(ScrollView(this).apply { addView(view) }).show()
    }

    private fun createMoveGroupRow(
        group: ProxyGroup,
        ent: ProxyEntity,
    ): View {
        return MaterialCardView(this).apply {
            setCardBackgroundColor(Color.TRANSPARENT)
            radius = settingsDp(10).toFloat()
            cardElevation = 0f
            strokeWidth = settingsDp(1)
            setStrokeColor(ContextCompat.getColor(context, R.color.dashboard_card_border_off))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(settingsDp(10), settingsDp(4), settingsDp(10), settingsDp(4))
            }
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                setPadding(settingsDp(12), settingsDp(12), settingsDp(12), settingsDp(12))
                addView(TextView(context).apply {
                    ellipsize = TextUtils.TruncateAt.END
                    maxLines = 1
                    text = group.displayName()
                    typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(context, R.color.dashboard_space_text_primary))
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                })
                addView(MaterialButton(context).apply {
                    backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    minWidth = 0
                    text = getString(R.string.move)
                    setTextColor(ContextCompat.getColor(context, R.color.dashboard_space_text_primary))
                    strokeColor = ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.dashboard_card_border_off)
                    )
                    strokeWidth = settingsDp(1)
                    setOnClickListener {
                        runOnDefaultDispatcher {
                            val oldGroupId = ent.groupId
                            val newGroupId = group.id
                            ent.groupId = newGroupId
                            ProfileManager.updateProfile(ent)
                            GroupManager.postUpdate(oldGroupId)
                            GroupManager.postUpdate(newGroupId)
                            DataStore.editingGroup = newGroupId
                            runOnMainDispatcher {
                                finish()
                            }
                        }
                    }
                })
            })
        }
    }

    private fun settingsDp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        companion object {
            private const val DIALOG_FRAGMENT_TAG = "androidx.preference.PreferenceFragment.DIALOG"
        }

        var activity: ProfileSettingsActivity<*>? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity = (requireActivity() as ProfileSettingsActivity<*>).apply {
                    createPreferences(savedInstanceState, rootKey)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    SagerNet.application,
                    "Error on createPreferences, please try again.",
                    Toast.LENGTH_SHORT
                ).show()
                Logs.e(e)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)

            activity?.apply {
                viewCreated(view, savedInstanceState)
                DataStore.dirty = false
                DataStore.profileCacheStore.registerChangeListener(this)
            }
        }

        var callbackCustom: ((String) -> Unit)? = null
        var callbackCustomOutbound: ((String) -> Unit)? = null

        val resultCallbackCustom = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (_, _) ->
            callbackCustom?.let { it(DataStore.serverCustom) }
        }

        val resultCallbackCustomOutbound = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (_, _) ->
            callbackCustomOutbound?.let { it(DataStore.serverCustomOutbound) }
        }

        @SuppressLint("CheckResult")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
            R.id.action_delete -> {
                if (DataStore.editingId == 0L) {
                    requireActivity().finish()
                } else {
                    DeleteConfirmationDialogFragment().apply {
                        arg(
                            ProfileIdArg(
                                DataStore.editingId, DataStore.editingGroup
                            )
                        )
                        key()
                    }.show(parentFragmentManager, null)
                }
                true
            }

            R.id.action_apply -> {
                runOnDefaultDispatcher {
                    activity?.saveAndExit()
                }
                true
            }

            R.id.action_custom_outbound_json -> {
                activity?.proxyEntity?.apply {
                    val bean = requireBean()
                    DataStore.serverCustomOutbound = bean.customOutboundJson
                    callbackCustomOutbound = { bean.customOutboundJson = it }
                    resultCallbackCustomOutbound.launch(
                        Intent(
                            requireContext(),
                            ConfigEditActivity::class.java
                        ).apply {
                            putExtra("key", Key.SERVER_CUSTOM_OUTBOUND)
                        })
                }
                true
            }

            R.id.action_custom_config_json -> {
                activity?.proxyEntity?.apply {
                    val bean = requireBean()
                    DataStore.serverCustom = bean.customConfigJson
                    callbackCustom = { bean.customConfigJson = it }
                    resultCallbackCustom.launch(
                        Intent(
                            requireContext(),
                            ConfigEditActivity::class.java
                        ).apply {
                            putExtra("key", Key.SERVER_CUSTOM)
                        })
                }
                true
            }

            R.id.action_create_shortcut -> {
                val activity = requireActivity() as ProfileSettingsActivity<*>
                val ent = activity.proxyEntity!!
                val shortcut = ShortcutInfoCompat.Builder(activity, "shortcut-profile-${ent.id}")
                    .setShortLabel(ent.displayName())
                    .setLongLabel(ent.displayName())
                    .setIcon(
                        IconCompat.createWithResource(
                            activity, R.drawable.ic_qu_shadowsocks_launcher
                        )
                    ).setIntent(Intent(
                        context, QuickToggleShortcut::class.java
                    ).apply {
                        action = Intent.ACTION_MAIN
                        putExtra("profile", ent.id)
                    }).build()
                ShortcutManagerCompat.requestPinShortcut(activity, shortcut, null)
            }

            R.id.action_move -> {
                val activity = requireActivity() as ProfileSettingsActivity<*>
                val ent = activity.proxyEntity!!
                val view = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL

                    activity.movableGroups.forEach { group ->
                        addView(createMoveGroupRow(activity, group, ent))
                    }
                }
                val scrollView = ScrollView(context).apply {
                    addView(view)
                }
                MaterialAlertDialogBuilder(activity).setView(scrollView).show()
                true
            }

            else -> false
        }

        private fun createMoveGroupRow(
            activity: ProfileSettingsActivity<*>,
            group: ProxyGroup,
            ent: ProxyEntity,
        ): View {
            val context = requireContext()
            return MaterialCardView(context).apply {
                setCardBackgroundColor(Color.TRANSPARENT)
                radius = dp(10).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                setStrokeColor(ContextCompat.getColor(context, R.color.dashboard_card_border_off))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    setMargins(dp(10), dp(4), dp(10), dp(4))
                }
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    addView(TextView(context).apply {
                        ellipsize = TextUtils.TruncateAt.END
                        maxLines = 1
                        text = group.displayName()
                        typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(ContextCompat.getColor(context, R.color.dashboard_space_text_primary))
                        textSize = 16f
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        )
                    })
                    addView(MaterialButton(context).apply {
                        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                        minWidth = 0
                        text = getString(R.string.move)
                        setTextColor(ContextCompat.getColor(context, R.color.dashboard_space_text_primary))
                        strokeColor = ColorStateList.valueOf(
                            ContextCompat.getColor(context, R.color.dashboard_card_border_off)
                        )
                        strokeWidth = dp(1)
                        setOnClickListener {
                            runOnDefaultDispatcher {
                                val oldGroupId = ent.groupId
                                val newGroupId = group.id
                                ent.groupId = newGroupId
                                ProfileManager.updateProfile(ent)
                                GroupManager.postUpdate(oldGroupId)
                                GroupManager.postUpdate(newGroupId)
                                DataStore.editingGroup = newGroupId
                                runOnMainDispatcher {
                                    activity.finish()
                                }
                            }
                        }
                    })
                })
            }
        }

        private fun dp(value: Int): Int {
            return (value * resources.displayMetrics.density).toInt()
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            activity?.apply {
                if (displayPreferenceDialog(preference)) return
            }
            if (preference is LinkOrContentPreference || preference is PasswordPreference) {
                if (parentFragmentManager.findFragmentByTag(DIALOG_FRAGMENT_TAG) != null) return
                val dialog = if (preference is PasswordPreference) {
                    YokhanEditTextPreferenceDialogFragment.newPasswordInstance(preference.key)
                } else {
                    YokhanEditTextPreferenceDialogFragment.newInstance(preference.key)
                }
                dialog.apply {
                    setTargetFragment(this@MyPreferenceFragmentCompat, 0)
                }.show(parentFragmentManager, DIALOG_FRAGMENT_TAG)
                return
            }
            super.onDisplayPreferenceDialog(preference)
        }

    }

    object PasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrBlank()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                "\u2022".repeat(text.length)
            }
        }

    }

}
