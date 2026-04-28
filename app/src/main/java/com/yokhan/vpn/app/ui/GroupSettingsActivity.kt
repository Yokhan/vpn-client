package com.yokhan.vpn.app.ui

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.yokhan.vpn.app.ui.form.arrayOptions
import com.yokhan.vpn.app.ui.form.selectValue
import com.yokhan.vpn.app.ui.form.switchValue
import com.yokhan.vpn.app.ui.form.textValue
import com.yokhan.vpn.app.ui.form.withVisible
import com.yokhan.vpn.shared.ui.form.YokhanFormEvent
import com.yokhan.vpn.shared.ui.form.YokhanFormField
import com.yokhan.vpn.shared.ui.form.YokhanFormOption
import com.yokhan.vpn.shared.ui.form.YokhanFormScreen
import com.yokhan.vpn.shared.ui.form.YokhanFormSection
import com.yokhan.vpn.shared.ui.form.YokhanFormState
import com.yokhan.vpn.shared.ui.form.YokhanFormValidation
import com.yokhan.vpn.shared.ui.form.YokhanFormValidationState
import com.yokhan.vpn.shared.ui.form.hasBlockingValidationErrors
import com.yokhan.vpn.shared.ui.form.withFieldEvent
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.parcelize.Parcelize

class GroupSettingsActivity : ThemedActivity() {

    private var groupEntity: ProxyGroup? = null
    private val formState = mutableStateOf<YokhanFormState?>(null)

    private fun cacheOutboundName(key: String, profile: ProxyEntity?) {
        DataStore.profileCacheStore.putString(key + "Name", profile?.displayName())
    }

    private fun ProxyGroup.initCache() {
        DataStore.groupName = name ?: ""
        DataStore.groupType = type
        DataStore.groupOrder = order
        DataStore.groupIsSelector = isSelector
        DataStore.frontProxy = frontProxy
        DataStore.landingProxy = landingProxy
        DataStore.frontProxyTmp = if (frontProxy >= 0) 3 else 0
        DataStore.landingProxyTmp = if (landingProxy >= 0) 3 else 0
        cacheOutboundName(Key.GROUP_FRONT_PROXY, frontProxy.takeIf { it >= 0 }?.let(ProfileManager::getProfile))
        cacheOutboundName(Key.GROUP_LANDING_PROXY, landingProxy.takeIf { it >= 0 }?.let(ProfileManager::getProfile))

        val subscription = subscription ?: SubscriptionBean().applyDefaultValues()
        DataStore.subscriptionLink = subscription.link
        DataStore.subscriptionForceResolve = subscription.forceResolve
        DataStore.subscriptionDeduplication = subscription.deduplication
        DataStore.subscriptionUpdateWhenConnectedOnly = subscription.updateWhenConnectedOnly
        DataStore.subscriptionUserAgent = subscription.customUserAgent
        DataStore.subscriptionAutoUpdate = subscription.autoUpdate
        DataStore.subscriptionAutoUpdateDelay = subscription.autoUpdateDelay
    }

    private fun ProxyGroup.serializeFromCache() {
        name = DataStore.groupName.takeIf { it.isNotBlank() } ?: "My group"
        type = DataStore.groupType
        order = DataStore.groupOrder
        isSelector = DataStore.groupIsSelector
        frontProxy = if (DataStore.frontProxyTmp == 3) DataStore.frontProxy else -1
        landingProxy = if (DataStore.landingProxyTmp == 3) DataStore.landingProxy else -1

        if (type == GroupType.SUBSCRIPTION) {
            subscription = (subscription ?: SubscriptionBean().applyDefaultValues()).apply {
                link = DataStore.subscriptionLink
                forceResolve = DataStore.subscriptionForceResolve
                deduplication = DataStore.subscriptionDeduplication
                updateWhenConnectedOnly = DataStore.subscriptionUpdateWhenConnectedOnly
                customUserAgent = DataStore.subscriptionUserAgent
                autoUpdate = DataStore.subscriptionAutoUpdate
                autoUpdateDelay = DataStore.subscriptionAutoUpdateDelay
            }
        }
    }

    private fun needSave(): Boolean = DataStore.dirty

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                (requireActivity() as GroupSettingsActivity).saveCurrentForm()
            }
            setNegativeButton(R.string.no) { _, _ -> requireActivity().finish() }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    @Parcelize
    data class GroupIdArg(val groupId: Long) : Parcelable

    class DeleteConfirmationDialogFragment : AlertDialogFragment<GroupIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_group_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher { GroupManager.deleteGroup(arg.groupId) }
                requireActivity().finish()
            }
            setNegativeButton(R.string.no, null)
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createYokhanSettingsHost(useComposeContainer = true))
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.group_settings)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        onBackPressedDispatcher.addCallback(this) {
            if (needSave()) {
                UnsavedChangesDialogFragment().apply { key() }.show(supportFragmentManager, null)
            } else {
                finish()
            }
        }

        val editingId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
        DataStore.editingId = editingId
        runOnDefaultDispatcher {
            if (savedInstanceState == null) {
                if (editingId == 0L) {
                    ProxyGroup().initCache()
                } else {
                    groupEntity = SagerDatabase.groupDao.getById(editingId)
                    if (groupEntity == null) {
                        onMainDispatcher { finish() }
                        return@runOnDefaultDispatcher
                    }
                    groupEntity!!.initCache()
                }
            } else if (editingId != 0L) {
                groupEntity = SagerDatabase.groupDao.getById(editingId)
            }

            onMainDispatcher {
                val restoredDirty = if (savedInstanceState == null) false else DataStore.dirty
                DataStore.dirty = restoredDirty
                formState.value = buildGroupForm(dirty = restoredDirty)
                mountComposeForm()
            }
        }
    }

    private fun mountComposeForm() {
        findViewById<ViewGroup>(R.id.settings).apply {
            removeAllViews()
        }.addView(
            ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setContent {
                    formState.value?.let { state ->
                        YokhanFormScreen(state = state, onEvent = ::handleFormEvent)
                    }
                }
            },
        )
    }

    private fun handleFormEvent(event: YokhanFormEvent) {
        when (event) {
            YokhanFormEvent.Save -> saveCurrentForm()
            YokhanFormEvent.Cancel,
            YokhanFormEvent.Reset,
            -> {
                DataStore.dirty = false
                finish()
            }

            is YokhanFormEvent.OptionSelected -> {
                if (event.optionId == "3" && event.id == Key.GROUP_FRONT_PROXY) {
                    selectProfileForAddFront.launch(Intent(this, ProfileSelectActivity::class.java))
                    return
                }
                if (event.optionId == "3" && event.id == Key.GROUP_LANDING_PROXY) {
                    selectProfileForAddLanding.launch(Intent(this, ProfileSelectActivity::class.java))
                    return
                }
                updateForm(event)
            }

            else -> updateForm(event)
        }
    }

    private fun updateForm(event: YokhanFormEvent) {
        val updated = formState.value?.withFieldEvent(event)?.let(::validateGroupForm) ?: return
        writeGroupForm(updated)
        formState.value = updated
    }

    private fun buildGroupForm(dirty: Boolean = DataStore.dirty): YokhanFormState {
        return validateGroupForm(
            YokhanFormState(
                title = getString(R.string.group_settings),
                subtitle = if (DataStore.editingId == 0L) {
                    getString(R.string.add_profile)
                } else {
                    getString(R.string.edit)
                },
                dirty = dirty,
                sections = listOf(
                    YokhanFormSection(
                        id = "group",
                        title = getString(R.string.group_settings).uppercase(),
                        fields = listOf(
                            YokhanFormField.Text(
                                id = Key.GROUP_NAME,
                                label = getString(R.string.group_name),
                                value = DataStore.groupName,
                                required = true,
                            ),
                            YokhanFormField.Select(
                                id = Key.GROUP_TYPE,
                                label = getString(R.string.group_type),
                                options = arrayOptions(R.array.group_types, R.array.int_array_2),
                                selectedOptionId = DataStore.groupType.toString(),
                            ),
                            YokhanFormField.Select(
                                id = Key.GROUP_ORDER,
                                label = getString(R.string.group_order),
                                options = arrayOptions(R.array.group_orders, R.array.int_array_3),
                                selectedOptionId = DataStore.groupOrder.toString(),
                            ),
                            YokhanFormField.Switch(
                                id = Key.GROUP_IS_SELECTOR,
                                label = getString(R.string.use_selector),
                                checked = DataStore.groupIsSelector,
                            ),
                            YokhanFormField.Select(
                                id = Key.GROUP_FRONT_PROXY,
                                label = getString(R.string.front_proxy),
                                options = outboundOptions(Key.GROUP_FRONT_PROXY),
                                selectedOptionId = DataStore.frontProxyTmp.toString(),
                            ),
                            YokhanFormField.Select(
                                id = Key.GROUP_LANDING_PROXY,
                                label = getString(R.string.landing_proxy),
                                options = outboundOptions(Key.GROUP_LANDING_PROXY),
                                selectedOptionId = DataStore.landingProxyTmp.toString(),
                            ),
                        ),
                    ),
                    YokhanFormSection(
                        id = "subscription",
                        title = getString(R.string.subscription_settings).uppercase(),
                        fields = listOf(
                            YokhanFormField.Text(
                                id = Key.SUBSCRIPTION_LINK,
                                label = getString(R.string.group_subscription_link),
                                value = DataStore.subscriptionLink,
                                required = true,
                            ),
                            YokhanFormField.Switch(
                                id = Key.SUBSCRIPTION_FORCE_RESOLVE,
                                label = getString(R.string.force_resolve),
                                helper = getString(R.string.force_resolve_sum),
                                checked = DataStore.subscriptionForceResolve,
                            ),
                            YokhanFormField.Switch(
                                id = Key.SUBSCRIPTION_DEDUPLICATION,
                                label = getString(R.string.deduplication),
                                helper = getString(R.string.deduplication_sum),
                                checked = DataStore.subscriptionDeduplication,
                            ),
                        ),
                    ),
                    YokhanFormSection(
                        id = "subscription-update",
                        title = getString(R.string.update_settings).uppercase(),
                        fields = listOf(
                            YokhanFormField.Switch(
                                id = Key.SUBSCRIPTION_UPDATE_WHEN_CONNECTED_ONLY,
                                label = getString(R.string.update_when_connected_only),
                                helper = getString(R.string.update_when_connected_only_sum),
                                checked = DataStore.subscriptionUpdateWhenConnectedOnly,
                            ),
                            YokhanFormField.Text(
                                id = Key.SUBSCRIPTION_USER_AGENT,
                                label = getString(R.string.subscription_user_agent),
                                value = DataStore.subscriptionUserAgent,
                            ),
                            YokhanFormField.Switch(
                                id = Key.SUBSCRIPTION_AUTO_UPDATE,
                                label = getString(R.string.auto_update),
                                checked = DataStore.subscriptionAutoUpdate,
                            ),
                            YokhanFormField.Number(
                                id = Key.SUBSCRIPTION_AUTO_UPDATE_DELAY,
                                label = getString(R.string.auto_update_delay),
                                value = DataStore.subscriptionAutoUpdateDelay.toString(),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun validateGroupForm(state: YokhanFormState): YokhanFormState {
        val subscription = state.selectValue(Key.GROUP_TYPE).toIntOrNull() == GroupType.SUBSCRIPTION
        val autoUpdate = state.switchValue(Key.SUBSCRIPTION_AUTO_UPDATE)
        return state.copy(
            sections = state.sections.map { section ->
                section.copy(
                    fields = section.fields.map { field ->
                        val visibleField = when (section.id) {
                            "subscription",
                            "subscription-update",
                            -> field.withVisible(subscription)

                            else -> field
                        }
                        if (visibleField.id == Key.SUBSCRIPTION_AUTO_UPDATE_DELAY && visibleField is YokhanFormField.Number) {
                            val delay = visibleField.value.toIntOrNull()
                            visibleField.copy(
                                enabled = autoUpdate,
                                validation = if (autoUpdate && (delay == null || delay < 15)) {
                                    YokhanFormValidation(
                                        state = YokhanFormValidationState.Error,
                                        message = "Minimum value is 15 minutes",
                                    )
                                } else {
                                    YokhanFormValidation()
                                },
                            )
                        } else {
                            visibleField
                        }
                    },
                )
            },
        )
    }

    private fun writeGroupForm(state: YokhanFormState) {
        DataStore.groupName = state.textValue(Key.GROUP_NAME)
        DataStore.groupType = state.selectValue(Key.GROUP_TYPE).toIntOrNull() ?: GroupType.BASIC
        DataStore.groupOrder = state.selectValue(Key.GROUP_ORDER).toIntOrNull() ?: 0
        DataStore.groupIsSelector = state.switchValue(Key.GROUP_IS_SELECTOR)
        DataStore.frontProxyTmp = state.selectValue(Key.GROUP_FRONT_PROXY).toIntOrNull() ?: 0
        DataStore.landingProxyTmp = state.selectValue(Key.GROUP_LANDING_PROXY).toIntOrNull() ?: 0
        DataStore.subscriptionLink = state.textValue(Key.SUBSCRIPTION_LINK)
        DataStore.subscriptionForceResolve = state.switchValue(Key.SUBSCRIPTION_FORCE_RESOLVE)
        DataStore.subscriptionDeduplication = state.switchValue(Key.SUBSCRIPTION_DEDUPLICATION)
        DataStore.subscriptionUpdateWhenConnectedOnly = state.switchValue(Key.SUBSCRIPTION_UPDATE_WHEN_CONNECTED_ONLY)
        DataStore.subscriptionUserAgent = state.textValue(Key.SUBSCRIPTION_USER_AGENT)
        DataStore.subscriptionAutoUpdate = state.switchValue(Key.SUBSCRIPTION_AUTO_UPDATE)
        DataStore.subscriptionAutoUpdateDelay = state.textValue(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY).toIntOrNull() ?: 1440
        DataStore.dirty = state.dirty
    }

    fun saveCurrentForm() {
        val state = formState.value?.let(::validateGroupForm) ?: buildGroupForm()
        formState.value = state
        if (state.hasBlockingValidationErrors()) {
            return
        }
        writeGroupForm(state)
        runOnDefaultDispatcher { saveAndExit() }
    }

    suspend fun saveAndExit() {
        val editingId = DataStore.editingId
        if (editingId == 0L) {
            GroupManager.createGroup(ProxyGroup().apply { serializeFromCache() })
        } else if (needSave()) {
            val entity = groupEntity ?: SagerDatabase.groupDao.getById(DataStore.editingId)
            if (entity == null) {
                onMainDispatcher { finish() }
                return
            }
            val keepUserInfo = (entity.type == GroupType.SUBSCRIPTION &&
                DataStore.groupType == GroupType.SUBSCRIPTION &&
                entity.subscription?.link == DataStore.subscriptionLink)
            if (!keepUserInfo) {
                entity.subscription?.subscriptionUserinfo = ""
            }
            groupEntity = entity.apply { serializeFromCache() }
            GroupManager.updateGroup(groupEntity!!)
        }
        onMainDispatcher { finish() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_delete -> {
                if (DataStore.editingId == 0L) {
                    finish()
                } else {
                    DeleteConfirmationDialogFragment().apply {
                        arg(GroupIdArg(DataStore.editingId))
                        key()
                    }.show(supportFragmentManager, null)
                }
                true
            }

            R.id.action_apply -> {
                saveCurrentForm()
                true
            }

            else -> false
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    val selectProfileForAddFront = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
            ) ?: return@runOnDefaultDispatcher
            DataStore.frontProxy = profile.id
            DataStore.frontProxyTmp = 3
            cacheOutboundName(Key.GROUP_FRONT_PROXY, profile)
            DataStore.dirty = true
            onMainDispatcher { formState.value = buildGroupForm(dirty = true) }
        }
    }

    val selectProfileForAddLanding = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
            ) ?: return@runOnDefaultDispatcher
            DataStore.landingProxy = profile.id
            DataStore.landingProxyTmp = 3
            cacheOutboundName(Key.GROUP_LANDING_PROXY, profile)
            DataStore.dirty = true
            onMainDispatcher { formState.value = buildGroupForm(dirty = true) }
        }
    }

    private fun outboundOptions(key: String): List<YokhanFormOption> {
        val name = DataStore.profileCacheStore.getString(key + "Name")
        return arrayOptions(R.array.front_proxy_entry, R.array.front_proxy_value).map { option ->
            if (option.id == "3") option.copy(description = name) else option
        }
    }
}
