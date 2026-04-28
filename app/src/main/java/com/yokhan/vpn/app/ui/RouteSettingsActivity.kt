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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yokhan.vpn.app.ui.form.arrayOptions
import com.yokhan.vpn.app.ui.form.selectValue
import com.yokhan.vpn.app.ui.form.textValue
import com.yokhan.vpn.app.ui.profile.ConfigEditActivity
import com.yokhan.vpn.shared.ui.form.YokhanFormEvent
import com.yokhan.vpn.shared.ui.form.YokhanFormField
import com.yokhan.vpn.shared.ui.form.YokhanFormOption
import com.yokhan.vpn.shared.ui.form.YokhanFormScreen
import com.yokhan.vpn.shared.ui.form.YokhanFormSection
import com.yokhan.vpn.shared.ui.form.YokhanFormState
import com.yokhan.vpn.shared.ui.form.YokhanFormTextMode
import com.yokhan.vpn.shared.ui.form.withFieldEvent
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import kotlinx.parcelize.Parcelize

class RouteSettingsActivity : ThemedActivity() {

    private var ruleEntity: RuleEntity? = null
    private val formState = mutableStateOf<YokhanFormState?>(null)
    private var routePackagesBeforePicker: String = ""
    private var serverConfigBeforeEditor: String = ""

    private fun cacheOutboundName(profileId: Long) {
        DataStore.profileCacheStore.putString(
            Key.ROUTE_OUTBOUND + "Name",
            profileId.takeIf { it > 0 }?.let(ProfileManager::getProfile)?.displayName(),
        )
    }

    private fun initRouteCache(packageName: String?) {
        RuleEntity().apply {
            if (!packageName.isNullOrBlank()) {
                packages = setOf(packageName)
                name = app.getString(R.string.route_for, PackageCache.loadLabel(packageName))
            }
        }.initCache()
    }

    private fun RuleEntity.initCache() {
        DataStore.routeName = name
        DataStore.serverConfig = config
        DataStore.routeDomain = domains
        DataStore.routeIP = ip
        DataStore.routePort = port
        DataStore.routeSourcePort = sourcePort
        DataStore.routeNetwork = network
        DataStore.routeSource = source
        DataStore.routeProtocol = protocol
        DataStore.routeOutboundRule = outbound
        DataStore.routeOutbound = when (outbound) {
            0L -> 0
            -1L -> 1
            -2L -> 2
            else -> 3
        }
        cacheOutboundName(outbound)
        DataStore.routePackages = packages.joinToString("\n")
    }

    private fun RuleEntity.serializeFromCache() {
        name = DataStore.routeName
        config = DataStore.serverConfig
        domains = DataStore.routeDomain
        ip = DataStore.routeIP
        port = DataStore.routePort
        sourcePort = DataStore.routeSourcePort
        network = DataStore.routeNetwork
        source = DataStore.routeSource
        protocol = DataStore.routeProtocol
        outbound = when (DataStore.routeOutbound) {
            0 -> 0L
            1 -> -1L
            2 -> -2L
            else -> DataStore.routeOutboundRule
        }
        packages = DataStore.routePackages.split("\n").filter { it.isNotBlank() }.toSet()
        if (DataStore.editingId == 0L) {
            enabled = true
        }
    }

    private fun needSave(): Boolean = DataStore.dirty

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    (requireActivity() as RouteSettingsActivity).saveAndExit()
                }
            }
            setNegativeButton(R.string.no) { _, _ -> requireActivity().finish() }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    @Parcelize
    data class ProfileIdArg(val ruleId: Long) : Parcelable

    class DeleteConfirmationDialogFragment : AlertDialogFragment<ProfileIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_route_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher { ProfileManager.deleteRule(arg.ruleId) }
                requireActivity().finish()
            }
            setNegativeButton(R.string.no, null)
        }
    }

    companion object {
        const val EXTRA_ROUTE_ID = "id"
        const val EXTRA_PACKAGE_NAME = "pkg"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createYokhanSettingsHost(useComposeContainer = true))
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.cag_route)
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

        val editingId = intent.getLongExtra(EXTRA_ROUTE_ID, 0L)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        DataStore.editingId = editingId
        runOnDefaultDispatcher {
            if (savedInstanceState == null) {
                if (editingId == 0L) {
                    initRouteCache(packageName)
                } else {
                    ruleEntity = SagerDatabase.rulesDao.getById(editingId)
                    if (ruleEntity == null) {
                        onMainDispatcher { finish() }
                        return@runOnDefaultDispatcher
                    }
                    ruleEntity!!.initCache()
                }
            } else if (editingId != 0L) {
                ruleEntity = SagerDatabase.rulesDao.getById(editingId)
            }

            onMainDispatcher {
                val initialDirty = if (savedInstanceState == null) {
                    editingId == 0L && !packageName.isNullOrBlank()
                } else {
                    DataStore.dirty
                }
                DataStore.dirty = initialDirty
                formState.value = buildRouteForm(dirty = initialDirty)
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
            YokhanFormEvent.Save -> runOnDefaultDispatcher { saveAndExit() }
            YokhanFormEvent.Cancel,
            YokhanFormEvent.Reset,
            -> {
                DataStore.dirty = false
                finish()
            }

            is YokhanFormEvent.ActionInvoked -> when (event.id) {
                Key.SERVER_CONFIG -> openConfigEditor()
                Key.ROUTE_PACKAGES -> openAppList()
                else -> Unit
            }

            is YokhanFormEvent.OptionSelected -> {
                if (event.id == Key.ROUTE_OUTBOUND && event.optionId == "3") {
                    selectProfileForAdd.launch(Intent(this, ProfileSelectActivity::class.java))
                    return
                }
                updateForm(event)
            }

            else -> updateForm(event)
        }
    }

    private fun updateForm(event: YokhanFormEvent) {
        val updated = formState.value?.withFieldEvent(event) ?: return
        writeRouteForm(updated)
        formState.value = updated
    }

    private fun openConfigEditor() {
        serverConfigBeforeEditor = DataStore.serverConfig
        editConfig.launch(
            Intent(this, ConfigEditActivity::class.java).apply {
                putExtra("key", Key.SERVER_CONFIG)
            },
        )
    }

    private fun openAppList() {
        routePackagesBeforePicker = DataStore.routePackages
        selectAppList.launch(Intent(this, AppListActivity::class.java))
    }

    private fun buildRouteForm(dirty: Boolean = DataStore.dirty): YokhanFormState {
        return YokhanFormState(
            title = getString(R.string.cag_route),
            subtitle = if (DataStore.editingId == 0L) {
                getString(R.string.route_add)
            } else {
                getString(R.string.edit)
            },
            dirty = dirty,
            sections = listOf(
                YokhanFormSection(
                    id = "route-main",
                    title = getString(R.string.cag_route).uppercase(),
                    fields = listOf(
                        YokhanFormField.Text(
                            id = Key.ROUTE_NAME,
                            label = getString(R.string.route_name),
                            value = DataStore.routeName,
                            required = true,
                        ),
                        YokhanFormField.Action(
                            id = Key.SERVER_CONFIG,
                            label = getString(R.string.custom_config),
                            value = configSummary(),
                            actionLabel = "EDIT",
                        ),
                    ),
                ),
                YokhanFormSection(
                    id = "route-rules",
                    title = getString(R.string.cag_route).uppercase(),
                    fields = listOf(
                        YokhanFormField.Action(
                            id = Key.ROUTE_PACKAGES,
                            label = getString(R.string.apps),
                            value = appsSummary(),
                            actionLabel = "SELECT",
                        ),
                        YokhanFormField.Text(
                            id = Key.ROUTE_DOMAIN,
                            label = "domain",
                            value = DataStore.routeDomain,
                            mode = YokhanFormTextMode.Multiline,
                        ),
                        YokhanFormField.Text(
                            id = Key.ROUTE_IP,
                            label = "dst ip",
                            value = DataStore.routeIP,
                            mode = YokhanFormTextMode.Multiline,
                        ),
                        YokhanFormField.Text(
                            id = Key.ROUTE_PORT,
                            label = "dst port",
                            value = DataStore.routePort,
                        ),
                        YokhanFormField.Text(
                            id = Key.ROUTE_SOURCE,
                            label = "src ip",
                            value = DataStore.routeSource,
                            mode = YokhanFormTextMode.Multiline,
                        ),
                        YokhanFormField.Text(
                            id = Key.ROUTE_SOURCE_PORT,
                            label = "src port",
                            value = DataStore.routeSourcePort,
                        ),
                        YokhanFormField.Select(
                            id = Key.ROUTE_NETWORK,
                            label = "network",
                            options = arrayOptions(R.array.route_protocol_entry, R.array.route_protocol_value),
                            selectedOptionId = DataStore.routeNetwork,
                        ),
                        YokhanFormField.Text(
                            id = Key.ROUTE_PROTOCOL,
                            label = "protocol",
                            value = DataStore.routeProtocol,
                        ),
                        YokhanFormField.Select(
                            id = Key.ROUTE_OUTBOUND,
                            label = "outbound",
                            options = outboundOptions(),
                            selectedOptionId = DataStore.routeOutbound.toString(),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun writeRouteForm(state: YokhanFormState) {
        DataStore.routeName = state.textValue(Key.ROUTE_NAME)
        DataStore.routeDomain = state.textValue(Key.ROUTE_DOMAIN)
        DataStore.routeIP = state.textValue(Key.ROUTE_IP)
        DataStore.routePort = state.textValue(Key.ROUTE_PORT)
        DataStore.routeSource = state.textValue(Key.ROUTE_SOURCE)
        DataStore.routeSourcePort = state.textValue(Key.ROUTE_SOURCE_PORT)
        DataStore.routeNetwork = state.selectValue(Key.ROUTE_NETWORK)
        DataStore.routeProtocol = state.textValue(Key.ROUTE_PROTOCOL)
        DataStore.routeOutbound = state.selectValue(Key.ROUTE_OUTBOUND).toIntOrNull() ?: 0
        DataStore.dirty = state.dirty
    }

    suspend fun saveAndExit() {
        val editingId = DataStore.editingId
        if (editingId != 0L && !needSave()) {
            onMainDispatcher { finish() }
            return
        }
        if (!hasRouteRules()) {
            onMainDispatcher {
                MaterialAlertDialogBuilder(this@RouteSettingsActivity)
                    .setTitle(R.string.empty_route)
                    .setMessage(R.string.empty_route_notice)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            return
        }

        if (editingId == 0L) {
            val shouldReturnResult = intent.hasExtra(EXTRA_PACKAGE_NAME)
            ProfileManager.createRule(RuleEntity().apply { serializeFromCache() })
            onMainDispatcher {
                if (shouldReturnResult) {
                    setResult(RESULT_OK, Intent())
                }
                finish()
            }
        } else {
            val entity = ruleEntity ?: SagerDatabase.rulesDao.getById(DataStore.editingId)
            if (entity == null) {
                onMainDispatcher { finish() }
                return
            }
            ProfileManager.updateRule(entity.apply { serializeFromCache() })
            onMainDispatcher { finish() }
        }
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
                        arg(ProfileIdArg(DataStore.editingId))
                        key()
                    }.show(supportFragmentManager, null)
                }
                true
            }

            R.id.action_apply -> {
                formState.value?.let(::writeRouteForm)
                runOnDefaultDispatcher { saveAndExit() }
                true
            }

            else -> false
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private val selectProfileForAdd = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0),
            ) ?: return@runOnDefaultDispatcher
            DataStore.routeOutboundRule = profile.id
            DataStore.routeOutbound = 3
            DataStore.profileCacheStore.putString(Key.ROUTE_OUTBOUND + "Name", profile.displayName())
            DataStore.dirty = true
            onMainDispatcher { formState.value = buildRouteForm(dirty = true) }
        }
    }

    private val editConfig = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val changed = serverConfigBeforeEditor != DataStore.serverConfig
        if (changed) {
            DataStore.dirty = true
        }
        formState.value = buildRouteForm(dirty = DataStore.dirty)
    }

    private val selectAppList = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val changed = routePackagesBeforePicker != DataStore.routePackages
        if (changed) {
            DataStore.dirty = true
        }
        formState.value = buildRouteForm(dirty = DataStore.dirty)
    }

    private fun hasRouteRules(): Boolean {
        return listOf(
            DataStore.serverConfig,
            DataStore.routePackages,
            DataStore.routeDomain,
            DataStore.routeIP,
            DataStore.routePort,
            DataStore.routeSource,
            DataStore.routeSourcePort,
            DataStore.routeNetwork,
            DataStore.routeProtocol,
        ).any { it.isNotBlank() }
    }

    private fun configSummary(): String {
        val config = DataStore.serverConfig
        return if (config.isBlank()) {
            getString(androidx.preference.R.string.not_set)
        } else {
            getString(R.string.lines, config.split('\n').size)
        }
    }

    private fun appsSummary(): String {
        val packages = DataStore.routePackages
            .split("\n")
            .filter { it.isNotBlank() }
            .map {
                PackageCache.installedPackages[it]?.applicationInfo?.loadLabel(app.packageManager)
                    ?: PackageCache.installedPluginPackages[it]?.applicationInfo?.loadLabel(app.packageManager)
                    ?: it
            }
        if (packages.isEmpty()) {
            return getString(androidx.preference.R.string.not_set)
        }
        return if (packages.size <= 5) {
            packages.joinToString("\n")
        } else {
            getString(R.string.apps_message, packages.size)
        }
    }

    private fun outboundOptions(): List<YokhanFormOption> {
        val name = DataStore.profileCacheStore.getString(Key.ROUTE_OUTBOUND + "Name")
        return arrayOptions(R.array.outbound_entry, R.array.outbound_value).map { option ->
            if (option.id == "3") option.copy(description = name) else option
        }
    }
}
