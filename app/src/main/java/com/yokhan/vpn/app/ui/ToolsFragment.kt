package com.yokhan.vpn.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yokhan.vpn.shared.ui.design.YokhanPalette
import com.yokhan.vpn.shared.ui.design.YokhanSpacing
import com.yokhan.vpn.shared.ui.design.YokhanTypography
import com.yokhan.vpn.shared.ui.design.yokhanPalette
import com.yokhan.vpn.shared.ui.design.yokhanTypography
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ParcelizeBridge
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ktx.toStringPretty
import io.nekohasekai.sagernet.ktx.triggerFullRestart
import io.nekohasekai.sagernet.ktx.tryToShow
import com.yokhan.vpn.app.ui.compose.YokhanBody
import com.yokhan.vpn.app.ui.compose.YokhanButtonGrid
import com.yokhan.vpn.app.ui.compose.YokhanCheckboxRow
import com.yokhan.vpn.app.ui.compose.YokhanComposeRouteFragment
import com.yokhan.vpn.app.ui.compose.YokhanDivider
import com.yokhan.vpn.app.ui.compose.YokhanHeadline
import com.yokhan.vpn.app.ui.compose.YokhanMonoValue
import com.yokhan.vpn.app.ui.compose.YokhanRouteAction
import com.yokhan.vpn.app.ui.compose.YokhanRouteCard
import com.yokhan.vpn.app.ui.compose.YokhanRouteFrame
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ToolsFragment : YokhanComposeRouteFragment() {

    private var content = ""
    private val backupSelection = mutableStateOf(BackupSelection())

    private val exportSettings =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    try {
                        requireActivity().contentResolver.openOutputStream(data)!!
                            .bufferedWriter().use {
                                it.write(content)
                            }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }
        }

    private val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            runOnDefaultDispatcher {
                startImport(file)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    @Composable
    override fun Content(spaceActive: Boolean) {
        val selection = backupSelection.value
        YokhanRouteFrame(
            title = stringResource(R.string.menu_tools),
            systemLabel = stringResource(R.string.dashboard_system_label),
            spaceActive = spaceActive,
            onMenuClick = { (activity as? MainActivity)?.openNavigationMenu() },
        ) { colors, typography ->
            YokhanRouteCard(
                title = stringResource(R.string.tools_network),
                colors = colors,
                typography = typography,
            ) {
                YokhanHeadline(
                    text = stringResource(R.string.stun_test),
                    colors = colors,
                    typography = typography,
                )
                YokhanBody(
                    text = stringResource(R.string.stun_test_summary),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 8.dp),
                    maxLines = 5,
                )
                YokhanButtonGrid(
                    actions = listOf(
                        YokhanRouteAction(
                            title = stringResource(R.string.start),
                            primary = true,
                            onClick = { startActivity(Intent(requireContext(), StunActivity::class.java)) },
                        ),
                    ),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = YokhanSpacing.ActionTopGap),
                )
            }

            YokhanRouteCard(
                title = stringResource(R.string.backup),
                colors = colors,
                typography = typography,
            ) {
                YokhanCheckboxRow(
                    title = stringResource(R.string.backup_groups_and_configurations),
                    checked = selection.profile,
                    colors = colors,
                    typography = typography,
                    onCheckedChange = { backupSelection.value = selection.copy(profile = it) },
                )
                YokhanCheckboxRow(
                    title = stringResource(R.string.backup_rules),
                    checked = selection.rule,
                    colors = colors,
                    typography = typography,
                    onCheckedChange = { backupSelection.value = selection.copy(rule = it) },
                )
                YokhanCheckboxRow(
                    title = stringResource(R.string.backup_settings),
                    checked = selection.setting,
                    colors = colors,
                    typography = typography,
                    onCheckedChange = { backupSelection.value = selection.copy(setting = it) },
                )
                YokhanBody(
                    text = stringResource(R.string.backup_summary),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 8.dp),
                    maxLines = 5,
                )
                YokhanDivider(colors, Modifier.padding(vertical = YokhanSpacing.CompactDividerGap))
                YokhanButtonGrid(
                    actions = listOf(
                        YokhanRouteAction(
                            title = stringResource(R.string.action_export),
                            primary = true,
                            onClick = ::exportBackup,
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.share),
                            onClick = ::shareBackup,
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.action_import_file),
                            onClick = { startFilesForResult(importFile, "*/*") },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.reset_settings),
                            onClick = ::confirmResetSettings,
                        ),
                    ),
                    colors = colors,
                    typography = typography,
                )
            }
        }
    }

    private fun confirmResetSettings() {
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
            .setMessage(R.string.reset_settings_message)
            .setNegativeButton(R.string.no, null)
            .setPositiveButton(R.string.yes) { _, _ ->
                DataStore.configurationStore.reset()
                triggerFullRestart(requireContext())
            }
            .show()
    }

    private fun exportBackup() {
        val selection = backupSelection.value
        runOnDefaultDispatcher {
            content = doBackup(selection.profile, selection.rule, selection.setting)
            onMainDispatcher {
                startFilesForResult(
                    exportSettings,
                    backupFileName()
                )
            }
        }
    }

    private fun shareBackup() {
        val selection = backupSelection.value
        runOnDefaultDispatcher {
            content = doBackup(selection.profile, selection.rule, selection.setting)
            val cacheFile = File(
                requireContext().cacheDir.apply { mkdirs() },
                backupFileName()
            )
            cacheFile.writeText(content)
            onMainDispatcher {
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("application/json")
                            .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .putExtra(
                                Intent.EXTRA_STREAM,
                                FileProvider.getUriForFile(
                                    requireContext(),
                                    BuildConfig.APPLICATION_ID + ".cache",
                                    cacheFile
                                )
                            ),
                        getString(R.string.abc_shareactionprovider_share_with)
                    )
                )
            }
        }
    }

    private fun Parcelable.toBase64Str(): String {
        val parcel = Parcel.obtain()
        writeToParcel(parcel, 0)
        try {
            return Util.b64EncodeUrlSafe(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    private fun backupFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "yokhanvpn_backup_$stamp.json"
    }

    private fun doBackup(profile: Boolean, rule: Boolean, setting: Boolean): String {
        val out = JSONObject().apply {
            put("version", 1)
            if (profile) {
                put("profiles", JSONArray().apply {
                    SagerDatabase.proxyDao.getAll().forEach {
                        put(it.toBase64Str())
                    }
                })

                put("groups", JSONArray().apply {
                    SagerDatabase.groupDao.allGroups().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (rule) {
                put("rules", JSONArray().apply {
                    SagerDatabase.rulesDao.allRules().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (setting) {
                put("settings", JSONArray().apply {
                    PublicDatabase.kvPairDao.all().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
        }
        return out.toStringPretty()
    }

    private suspend fun startImport(file: Uri) {
        val fileName = requireContext().contentResolver.query(file, null, null, null, null)
            ?.use { cursor ->
                cursor.moveToFirst()
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            }
            ?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
            .substringAfterLast('/')
            .substringAfter(':')

        if (!fileName.endsWith(".json")) {
            onMainDispatcher {
                snackbar(getString(R.string.backup_not_file, fileName)).show()
            }
            return
        }

        suspend fun invalid() = onMainDispatcher {
            snackbar(getString(R.string.invalid_backup_file)).show()
        }

        val imported = try {
            JSONObject((requireContext().contentResolver.openInputStream(file) ?: return).use {
                it.bufferedReader().readText()
            })
        } catch (e: Exception) {
            Logs.w(e)
            invalid()
            return
        }
        val version = imported.optInt("version", 0)
        if (version < 1 || version > 1) {
            invalid()
            return
        }

        onMainDispatcher {
            val available = BackupSelection(
                profile = imported.has("profiles"),
                rule = imported.has("rules"),
                setting = imported.has("settings"),
            )
            val importSelection = mutableStateOf(available)
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                .setView(createImportDialogView(available, importSelection))
                .setPositiveButton(R.string.backup_import) { _, _ ->
                    SagerNet.stopService()

                    val selection = importSelection.value
                    val dialog = MaterialAlertDialogBuilder(requireContext())
                        .setView(createProgressDialogView(getString(R.string.backup_importing)))
                        .setCancelable(false)
                        .show()
                    runOnDefaultDispatcher {
                        runCatching {
                            finishImport(
                                imported,
                                selection.profile,
                                selection.rule,
                                selection.setting
                            )
                            delay(500L)
                            triggerFullRestart(requireContext())
                        }.onFailure {
                            Logs.w(it)
                            onMainDispatcher {
                                alert(it.readableMessage).tryToShow()
                            }
                        }

                        onMainDispatcher {
                            dialog.dismiss()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun createImportDialogView(
        available: BackupSelection,
        selection: MutableState<BackupSelection>,
    ): ComposeView {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val colors = yokhanPalette(DataStore.serviceState.isRouteSpaceActive())
                val typography = yokhanTypography()
                BackupImportDialogContent(available, selection, colors, typography)
            }
        }
    }

    @Composable
    private fun BackupImportDialogContent(
        available: BackupSelection,
        selection: MutableState<BackupSelection>,
        colors: YokhanPalette,
        typography: YokhanTypography,
    ) {
        Column(Modifier.padding(4.dp)) {
            if (available.profile) {
                YokhanCheckboxRow(
                    title = stringResource(R.string.backup_groups_and_configurations),
                    checked = selection.value.profile,
                    colors = colors,
                    typography = typography,
                    onCheckedChange = { selection.value = selection.value.copy(profile = it) },
                )
            }
            if (available.rule) {
                YokhanCheckboxRow(
                    title = stringResource(R.string.backup_rules),
                    checked = selection.value.rule,
                    colors = colors,
                    typography = typography,
                    onCheckedChange = { selection.value = selection.value.copy(rule = it) },
                )
            }
            if (available.setting) {
                YokhanCheckboxRow(
                    title = stringResource(R.string.backup_settings),
                    checked = selection.value.setting,
                    colors = colors,
                    typography = typography,
                    onCheckedChange = { selection.value = selection.value.copy(setting = it) },
                )
            }
            YokhanBody(
                text = stringResource(R.string.backup_import_summary),
                colors = colors,
                typography = typography,
                modifier = Modifier.padding(top = 8.dp),
                maxLines = 5,
            )
        }
    }

    private fun createProgressDialogView(message: String): ComposeView {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val colors = yokhanPalette(DataStore.serviceState.isRouteSpaceActive())
                val typography = yokhanTypography()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp)
                    YokhanMonoValue(
                        text = message,
                        colors = colors,
                        typography = typography,
                        sizeSp = 13,
                    )
                }
            }
        }
    }

    private fun finishImport(
        content: JSONObject,
        profile: Boolean,
        rule: Boolean,
        setting: Boolean,
    ) {
        if (profile && content.has("profiles")) {
            val profiles = mutableListOf<ProxyEntity>()
            val jsonProfiles = content.getJSONArray("profiles")
            for (i in 0 until jsonProfiles.length()) {
                val data = Util.b64Decode(jsonProfiles[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                profiles.add(ProxyEntity.CREATOR.createFromParcel(parcel))
                parcel.recycle()
            }
            SagerDatabase.proxyDao.reset()
            SagerDatabase.proxyDao.insert(profiles)

            val groups = mutableListOf<ProxyGroup>()
            val jsonGroups = content.getJSONArray("groups")
            for (i in 0 until jsonGroups.length()) {
                val data = Util.b64Decode(jsonGroups[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                groups.add(ProxyGroup.CREATOR.createFromParcel(parcel))
                parcel.recycle()
            }
            SagerDatabase.groupDao.reset()
            SagerDatabase.groupDao.insert(groups)
            DataStore.cacheGroups(groups)
        }
        if (rule && content.has("rules")) {
            val rules = mutableListOf<RuleEntity>()
            val jsonRules = content.getJSONArray("rules")
            for (i in 0 until jsonRules.length()) {
                val data = Util.b64Decode(jsonRules[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                rules.add(ParcelizeBridge.createRule(parcel))
                parcel.recycle()
            }
            SagerDatabase.rulesDao.reset()
            SagerDatabase.rulesDao.insert(rules)
        }
        if (setting && content.has("settings")) {
            val settings = mutableListOf<KeyValuePair>()
            val jsonSettings = content.getJSONArray("settings")
            for (i in 0 until jsonSettings.length()) {
                val data = Util.b64Decode(jsonSettings[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                settings.add(KeyValuePair.CREATOR.createFromParcel(parcel))
                parcel.recycle()
            }
            PublicDatabase.kvPairDao.reset()
            PublicDatabase.kvPairDao.insert(settings)
        }
    }

    private data class BackupSelection(
        val profile: Boolean = true,
        val rule: Boolean = true,
        val setting: Boolean = true,
    )
}
