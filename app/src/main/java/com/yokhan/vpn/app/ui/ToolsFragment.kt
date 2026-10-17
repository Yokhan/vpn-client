package com.yokhan.gates.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.provider.OpenableColumns
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.yokhan.gates.app.diagnostics.YokhanDiagnosticsCollector
import com.yokhan.gates.app.diagnostics.YokhanBugReporter
import com.yokhan.gates.app.diagnostics.AndroidIncidentReports
import com.yokhan.gates.shared.ui.diagnostics.GatesIncidentHistoryItem
import com.yokhan.gates.shared.ui.diagnostics.GatesIncidentHistoryScreen
import com.yokhan.gates.shared.ui.diagnostics.gatesIncidentOutcome
import com.yokhan.gates.app.backup.BackupImportCoordinator
import com.yokhan.gates.app.backup.BackupImportFailure
import com.yokhan.gates.app.backup.BackupImportValidator
import com.yokhan.gates.app.backup.InvalidBackupException
import com.yokhan.gates.app.backup.ProfileMutations
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import com.yokhan.gates.shared.ui.design.YokhanTypography
import com.yokhan.gates.shared.ui.design.yokhanPalette
import com.yokhan.gates.shared.ui.design.yokhanTypography
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
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
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.Serializable as KryoSerializable
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
import com.yokhan.gates.shared.ui.route.YokhanBody
import com.yokhan.gates.shared.ui.route.YokhanButtonGrid
import com.yokhan.gates.shared.ui.route.YokhanCheckboxRow
import com.yokhan.gates.app.ui.compose.YokhanComposeRouteFragment
import com.yokhan.gates.shared.ui.route.YokhanDivider
import com.yokhan.gates.shared.ui.route.YokhanHeadline
import com.yokhan.gates.shared.ui.route.YokhanMonoValue
import com.yokhan.gates.shared.ui.route.YokhanRouteAction
import com.yokhan.gates.shared.ui.route.YokhanRouteCard
import com.yokhan.gates.shared.ui.route.YokhanRouteFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ToolsFragment : YokhanComposeRouteFragment() {

    companion object {
        private const val HISTORY_ROUTE = "incident-history"
        fun incidentHistory() = ToolsFragment().apply {
            arguments = Bundle().apply { putBoolean(HISTORY_ROUTE, true) }
        }
    }

    private var content = ""
    private var diagnosticsContent = ""
    private val diagnosticsState = mutableStateOf(DiagnosticsUiState())
    private val backupSelection = mutableStateOf(BackupSelection())
    private val showIncidentHistory = mutableStateOf(false)
    private val incidentLoading = mutableStateOf(false)
    private val incidentMessage = mutableStateOf("")
    private val incidentItems = mutableStateOf(emptyList<GatesIncidentHistoryItem>())
    private var pendingIncidentExport = ""

    private val exportIncident =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val payload = pendingIncidentExport
            pendingIncidentExport = ""
            if (uri != null && payload.isNotEmpty()) {
                val resolver = requireContext().applicationContext.contentResolver
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            checkNotNull(resolver.openOutputStream(uri)).bufferedWriter(Charsets.UTF_8).use {
                                it.write(payload)
                            }
                        }
                        incidentMessage.value = "Отчёт сохранён."
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        incidentMessage.value = "Не удалось сохранить отчёт."
                    }
                }
            }
        }

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
        showIncidentHistory.value = savedInstanceState?.getBoolean(HISTORY_ROUTE)
            ?: arguments?.getBoolean(HISTORY_ROUTE, false) ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(HISTORY_ROUTE, showIncidentHistory.value)
        super.onSaveInstanceState(outState)
    }

    private fun closeIncidentHistory() {
        if (arguments?.getBoolean(HISTORY_ROUTE, false) == true) {
            parentFragmentManager.popBackStack()
        } else {
            showIncidentHistory.value = false
        }
    }

    @Composable
    override fun Content(spaceActive: Boolean) {
        if (showIncidentHistory.value) {
            BackHandler { closeIncidentHistory() }
            LaunchedEffect(Unit) { loadIncidentHistory() }
            GatesIncidentHistoryScreen(
                items = incidentItems.value,
                loading = incidentLoading.value,
                message = incidentMessage.value,
                palette = yokhanPalette(spaceActive),
                spaceActive = spaceActive,
                onBack = ::closeIncidentHistory,
                onRefresh = { viewLifecycleOwner.lifecycleScope.launch { loadIncidentHistory() } },
                onExport = ::prepareIncidentExport,
            )
            return
        }
        val selection = backupSelection.value
        YokhanRouteFrame(
            title = stringResource(R.string.menu_tools),
            systemLabel = stringResource(R.string.dashboard_system_label),
            spaceActive = spaceActive,
            onMenuClick = { (activity as? MainActivity)?.openNavigationMenu() },
        ) { colors, typography ->
            YokhanButtonGrid(
                actions = listOf(YokhanRouteAction(
                    title = "История соединения",
                    onClick = { showIncidentHistory.value = true },
                )),
                colors = colors,
                typography = typography,
            )
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

            val diagnostics = diagnosticsState.value
            YokhanRouteCard(
                title = stringResource(R.string.diagnostics_title),
                colors = colors,
                typography = typography,
            ) {
                YokhanHeadline(
                    text = if (diagnostics.running) {
                        stringResource(R.string.diagnostics_summary_running)
                    } else if (diagnostics.summary.isNotBlank()) {
                        stringResource(R.string.diagnostics_summary_done, diagnostics.summary)
                    } else {
                        stringResource(R.string.diagnostics_title)
                    },
                    colors = colors,
                    typography = typography,
                )
                YokhanBody(
                    text = if (diagnostics.summary.isBlank()) {
                        stringResource(R.string.diagnostics_summary_idle)
                    } else {
                        diagnostics.details
                    },
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 8.dp),
                    maxLines = 6,
                )
                YokhanButtonGrid(
                    actions = listOf(
                        YokhanRouteAction(
                            title = stringResource(R.string.diagnostics_run),
                            primary = true,
                            enabled = !diagnostics.running,
                            onClick = { runDiagnostics() },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.diagnostics_share),
                            enabled = !diagnostics.running && diagnostics.hasReport,
                            onClick = ::shareDiagnostics,
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.bug_report_send),
                            enabled = !diagnostics.running,
                            onClick = ::showBugReportDialog,
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


    private suspend fun loadIncidentHistory() {
        if (incidentLoading.value) return
        incidentLoading.value = true
        val context = requireContext().applicationContext
        try {
            val result = withContext(Dispatchers.IO) {
                val summary = AndroidIncidentReports.summary(context)
                val rows = summary.optJSONArray("archives") ?: JSONArray()
                val dateFormat = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())
                val items = (0 until minOf(rows.length(), 8)).mapNotNull { index ->
                    runCatching {
                        val row = rows.getJSONObject(index)
                        val id = row.getString("id")
                        require(id.matches(Regex("[a-f0-9]{32,64}")))
                        val status = row.getString("status").uppercase(Locale.ROOT)
                        GatesIncidentHistoryItem(
                            id = id,
                            capturedAt = dateFormat.format(Date(row.getLong("triggered_at_millis"))),
                            outcome = gatesIncidentOutcome(status, row.optBoolean("recovered"),
                                row.optBoolean("recording")),
                            eventCount = row.optInt("event_count").coerceIn(0, 2048),
                            incomplete = row.optBoolean("truncated") || status == "INTERRUPTED",
                        )
                    }.getOrNull()
                }
                val message = when (summary.optString("availability")) {
                    "unavailable" -> "Не удалось прочитать историю."
                    "not-recorded" -> "Истории GATES пока нет."
                    else -> if (summary.optBoolean("truncated")) "Часть истории недоступна." else ""
                }
                items to message
            }
            incidentItems.value = result.first
            incidentMessage.value = result.second
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            incidentMessage.value = "Не удалось прочитать историю."
        } finally {
            incidentLoading.value = false
        }
    }

    private fun prepareIncidentExport(id: String) {
        if (incidentLoading.value) return
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            incidentLoading.value = true
            try {
                pendingIncidentExport = withContext(Dispatchers.IO) {
                    AndroidIncidentReports.export(context, id).also {
                        check(JSONObject(it).optString("availability") == "available")
                    }
                }
                exportIncident.launch("gates-incident-${id.take(12)}.json")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                incidentMessage.value = "Не удалось открыть отчёт."
            } finally {
                incidentLoading.value = false
            }
        }
    }

    private fun runDiagnostics(
        shareWhenReady: Boolean = false,
        bugReportMessage: String? = null,
    ) {
        if (diagnosticsState.value.running) return
        val appContext = requireContext().applicationContext
        diagnosticsState.value = DiagnosticsUiState(running = true)
        runOnDefaultDispatcher {
            runCatching {
                YokhanDiagnosticsCollector.collect(appContext)
            }.onSuccess { report ->
                val json = report.toJsonString()
                val failedProbes = report.probes.count { !it.ok }
                val unstableTargets = report.stability.count { it.failures > 0 }
                val slowestP95 = report.stability.maxOfOrNull { it.p95Ms } ?: 0L
                val sustainedFailures = report.sustained.count { it.failures > 0 }
                val sustainedMinKbps = report.sustained
                    .filter { it.successes > 0 }
                    .minOfOrNull { it.p50Kbps } ?: 0L
                val udpFailures = report.udpConnectivity.count { !it.ok }
                val uptimeHours = report.runtime.bootElapsedRealtimeMs / 3_600_000L
                val details = listOf(
                    "active_route=${report.routes.activeRouteFamily.ifBlank { report.routes.activeProtocol }}",
                    "network=${report.runtime.network.transports.joinToString("+").ifBlank { "unknown" }}",
                    "carrier=${report.runtime.network.carrierName.ifBlank { "unknown" }}",
                    "uptime_h=$uptimeHours",
                    "always_on=${report.runtime.alwaysOnVpnPackage.ifBlank { "none" }}",
                    "always_on_prod=${report.runtime.alwaysOnVpnMatchesProductionPackage}",
                    "lockdown=${report.runtime.alwaysOnVpnLockdown}",
                    "prod_battery_ignored=${report.runtime.productionBatteryOptimizationIgnored}",
                    "order=${report.routes.detectedRouteOrder.joinToString(">")}",
                    "failed_probes=$failedProbes",
                    "unstable_targets=$unstableTargets",
                    "stability_p95_max_ms=$slowestP95",
                    "sustained_failures=$sustainedFailures",
                    "sustained_min_p50_kbps=$sustainedMinKbps",
                    "udp_failures=$udpFailures",
                    "manifest=${report.manifest.cachedVersion}/${report.manifest.cachedChannel.ifBlank { "none" }}",
                ).joinToString(", ")
                if (bugReportMessage != null) {
                    val bugResult = YokhanBugReporter.send(appContext, bugReportMessage, json)
                    onMainDispatcher {
                        if (!isAdded) return@onMainDispatcher
                        diagnosticsContent = json
                        diagnosticsState.value = DiagnosticsUiState(
                            running = false,
                            summary = report.summaryText(),
                            details = details,
                            hasReport = true,
                        )
                        val message = if (bugResult.uploaded) {
                            getString(R.string.bug_report_sent, bugResult.reportId.ifBlank { "ok" })
                        } else {
                            getString(R.string.bug_report_saved, bugResult.localFile.name)
                        }
                        snackbar(message).show()
                    }
                } else {
                    onMainDispatcher {
                        if (!isAdded) return@onMainDispatcher
                        diagnosticsContent = json
                        diagnosticsState.value = DiagnosticsUiState(
                            running = false,
                            summary = report.summaryText(),
                            details = details,
                            hasReport = true,
                        )
                        snackbar(getString(R.string.diagnostics_report_ready)).show()
                        if (shareWhenReady) shareDiagnosticsContent()
                    }
                }
            }.onFailure { error ->
                onMainDispatcher {
                    if (!isAdded) return@onMainDispatcher
                    diagnosticsState.value = DiagnosticsUiState(
                        running = false,
                        summary = error.readableMessage,
                        details = error.readableMessage,
                        hasReport = diagnosticsContent.isNotBlank(),
                    )
                    snackbar(error.readableMessage).show()
                }
            }
        }
    }

    private fun showBugReportDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.bug_report_hint)
            minLines = 3
            maxLines = 5
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bug_report_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.bug_report_send) { _, _ ->
                val message = input.text?.toString().orEmpty()
                    .ifBlank { getString(R.string.bug_report_default_message) }
                runDiagnostics(bugReportMessage = message)
            }
            .show()
    }

    private fun shareDiagnostics() {
        if (diagnosticsContent.isBlank()) {
            snackbar(getString(R.string.diagnostics_report_empty)).show()
            return
        }
        shareDiagnosticsContent()
    }

    private fun shareDiagnosticsContent() {
        val cacheFile = File(
            requireContext().cacheDir.apply { mkdirs() },
            diagnosticsFileName(),
        )
        cacheFile.writeText(diagnosticsContent)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("application/json")
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(
                        Intent.EXTRA_STREAM,
                        FileProvider.getUriForFile(
                            requireContext(),
                            BuildConfig.APPLICATION_ID + ".cache",
                            cacheFile,
                        ),
                    ),
                getString(R.string.abc_shareactionprovider_share_with),
            ),
        )
    }

    private fun diagnosticsFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "gates_diagnostics_$stamp.json"
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
        return "gates_backup_$stamp.json"
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
                    val selection = importSelection.value
                    val dialog = MaterialAlertDialogBuilder(requireContext())
                        .setView(createProgressDialogView(getString(R.string.backup_importing)))
                        .setCancelable(false)
                        .showComposeDialog()
                    runOnDefaultDispatcher {
                        try {
                            runBackupImport(imported, selection)
                            delay(500L)
                            triggerFullRestart(requireContext())
                        } catch (failure: Exception) {
                            Logs.w(failure)
                            if (failure is BackupImportFailure && failure.rollbackComplete) {
                                triggerFullRestart(requireContext())
                            }
                            onMainDispatcher {
                                val message = when (failure) {
                                    is BackupImportFailure -> failure.message
                                    is InvalidBackupException -> getString(R.string.invalid_backup_file)
                                    else -> failure.readableMessage
                                }
                                alert(message ?: getString(R.string.invalid_backup_file)).tryToShow()
                            }
                        }

                        onMainDispatcher {
                            dialog.dismiss()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showComposeDialog()
        }
    }

    private fun createImportDialogView(
        available: BackupSelection,
        selection: MutableState<BackupSelection>,
    ): ComposeView {
        return createDialogComposeView().apply {
            setContent {
                val colors = yokhanPalette(spaceActive = true)
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
        return createDialogComposeView().apply {
            setContent {
                val colors = yokhanPalette(spaceActive = true)
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

    private fun createDialogComposeView(): ComposeView = ComposeView(requireContext()).apply {
        id = View.generateViewId()
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    private fun MaterialAlertDialogBuilder.showComposeDialog(): AlertDialog = create().apply {
        create()
        // Compose's window recomposer resolves owners from the dialog root, not its content.
        requireNotNull(window).decorView.apply {
            setViewTreeLifecycleOwner(viewLifecycleOwner)
            setViewTreeViewModelStoreOwner(this@ToolsFragment)
            setViewTreeSavedStateRegistryOwner(this@ToolsFragment)
        }
        show()
    }

    private fun runBackupImport(content: JSONObject, selection: BackupSelection) {
        var previousCacheGroups: List<ProxyGroup>? = null
        val coordinator = BackupImportCoordinator(
            prepare = { decodeBackup(content, selection) },
            capture = {
                DataStore.flushConfiguration()
                DataStore.refreshConfiguration()
                captureBackupSnapshot(selection).also { previousCacheGroups = it.cachedGroups }
            },
            stopService = {
                SagerNet.stopService()
                runBlocking {
                    withTimeout(10_000L) {
                        while (DataStore.serviceState != io.nekohasekai.sagernet.bg.BaseService.State.Idle &&
                            DataStore.serviceState != io.nekohasekai.sagernet.bg.BaseService.State.Stopped
                        ) delay(50L)
                    }
                }
            },
            persist = { persistBackup(it, selection) },
            rollback = { restoreBackupSnapshot(it, selection) },
            publish = { prepared ->
                DataStore.refreshConfiguration()
                prepared.groups?.let(DataStore::cacheGroups)
                DataStore.flushConfiguration()
            },
            restorePublished = {
                DataStore.refreshConfiguration()
                if (selection.profile) DataStore.cacheGroups(requireNotNull(previousCacheGroups))
                DataStore.flushConfiguration()
            },
        )
        runBlocking { ProfileMutations.withLock { coordinator.execute() } }
    }

    private fun decodeBackup(content: JSONObject, selection: BackupSelection): PreparedBackup {
        val version = content.opt("version") as? Number
        if (version == null || version.toDouble() != 1.0) throw InvalidBackupException()
        BackupImportValidator.requireSelectedSection(selection.profile, selection.rule, selection.setting)

        val profiles = if (selection.profile) {
            decodeRecords(content, "profiles", ::decodeProxyEntity)
        } else null
        val groups = if (selection.profile) {
            decodeRecords(content, "groups", ::decodeProxyGroup)
        } else null
        val rules = if (selection.rule) decodeRecords(content, "rules", ::decodeRule) else null
        val settings = if (selection.setting) {
            decodeRecords(content, "settings", ::decodeSetting)
        } else null

        profiles?.let { rows ->
            val importedGroups = requireNotNull(groups)
            BackupImportValidator.requireUniquePositiveIds(rows) { it.id }
            BackupImportValidator.requireUniquePositiveIds(importedGroups) { it.id }
            BackupImportValidator.requireGroupReferences(rows, importedGroups, { it.groupId }, { it.id })
            rows.forEach {
                try {
                    it.requireBean()
                } catch (_: Exception) {
                    throw InvalidBackupException()
                }
            }
            importedGroups.forEach {
                if (it.type != GroupType.BASIC && it.type != GroupType.SUBSCRIPTION) {
                    throw InvalidBackupException()
                }
                if (it.type == GroupType.SUBSCRIPTION && it.subscription == null) {
                    throw InvalidBackupException()
                }
            }
        }
        rules?.let { BackupImportValidator.requireUniquePositiveIds(it) { row -> row.id } }
        settings?.let { rows ->
            BackupImportValidator.requireUniqueNonBlankKeys(rows) { it.key }
            rows.forEach(::validateSetting)
        }

        val effectiveProfiles = profiles ?: SagerDatabase.proxyDao.getAll()
        val profileIds = effectiveProfiles.mapTo(HashSet(effectiveProfiles.size)) { it.id }
        groups?.forEach { group ->
            BackupImportValidator.requireProfileReferences(
                listOf(group.frontProxy, group.landingProxy).filter { it >= 0L }, profileIds,
            )
        }
        profiles?.forEach { profile ->
            profile.chainBean?.let { chain ->
                BackupImportValidator.requireProfileReferences(chain.proxies, profileIds)
            }
        }
        val effectiveRules = rules ?: if (profiles != null) SagerDatabase.rulesDao.allRules() else emptyList()
        BackupImportValidator.requireProfileReferences(
            effectiveRules.filter { it.enabled && it.outbound > 0L }.map { it.outbound }, profileIds,
        )

        return PreparedBackup(profiles, groups, rules, settings)
    }

    private fun <T> decodeRecords(
        content: JSONObject,
        key: String,
        decode: (String) -> T,
    ): List<T> {
        val records = content.optJSONArray(key) ?: throw InvalidBackupException()
        return buildList(records.length()) {
            for (index in 0 until records.length()) {
                val encoded = records.opt(index) as? String ?: throw InvalidBackupException()
                if (encoded.isBlank()) throw InvalidBackupException()
                add(decode(encoded))
            }
        }
    }

    private fun decodeProxyEntity(encoded: String): ProxyEntity = decodeKryoEntity(encoded, ::ProxyEntity)

    private fun decodeProxyGroup(encoded: String): ProxyGroup = decodeKryoEntity(encoded, ::ProxyGroup)

    private fun <T : KryoSerializable> decodeKryoEntity(encoded: String, create: () -> T): T {
        val parcel = decodeParcel(encoded)
        try {
            val bytes = parcel.createByteArray() ?: throw InvalidBackupException()
            if (parcel.dataAvail() != 0) throw InvalidBackupException()
            return try {
                KryoConverters.deserializeStrict(create(), bytes)
            } catch (_: Exception) {
                throw InvalidBackupException()
            }
        } finally {
            parcel.recycle()
        }
    }

    private fun decodeRule(encoded: String): RuleEntity {
        val parcel = decodeParcel(encoded)
        try {
            val rule = ParcelizeBridge.createRule(parcel)
            if (parcel.dataAvail() != 0) throw InvalidBackupException()
            return rule
        } catch (_: Exception) {
            throw InvalidBackupException()
        } finally {
            parcel.recycle()
        }
    }

    private fun decodeSetting(encoded: String): KeyValuePair {
        val parcel = decodeParcel(encoded)
        try {
            val setting = KeyValuePair.CREATOR.createFromParcel(parcel)
            if (parcel.dataAvail() != 0) throw InvalidBackupException()
            return setting
        } catch (_: Exception) {
            throw InvalidBackupException()
        } finally {
            parcel.recycle()
        }
    }

    private fun decodeParcel(encoded: String): Parcel {
        val bytes = try {
            Util.b64Decode(encoded)
        } catch (_: Exception) {
            throw InvalidBackupException()
        }
        if (bytes.isEmpty()) throw InvalidBackupException()
        return Parcel.obtain().apply {
            try {
                unmarshall(bytes, 0, bytes.size)
                setDataPosition(0)
            } catch (_: Exception) {
                recycle()
                throw InvalidBackupException()
            }
        }
    }

    private fun validateSetting(setting: KeyValuePair) {
        val value = setting.value
        val valid = when (setting.valueType) {
            KeyValuePair.TYPE_BOOLEAN -> value.size == 1 && value[0].toInt() in 0..1
            KeyValuePair.TYPE_FLOAT, KeyValuePair.TYPE_INT -> value.size == 4
            KeyValuePair.TYPE_LONG -> value.size == 8
            KeyValuePair.TYPE_STRING -> true
            KeyValuePair.TYPE_STRING_SET -> isValidStringSet(value)
            else -> false
        }
        if (!valid) throw InvalidBackupException()
    }

    private fun isValidStringSet(value: ByteArray): Boolean {
        val buffer = ByteBuffer.wrap(value)
        val seen = HashSet<String>()
        while (buffer.hasRemaining()) {
            if (buffer.remaining() < Int.SIZE_BYTES) return false
            val charCount = buffer.int
            if (charCount < 0) return false
            val start = buffer.position()
            val maxBytes = minOf(buffer.remaining().toLong(), charCount.toLong() * 4L).toInt()
            var decoded: String? = null
            for (length in charCount..maxBytes) {
                val candidate = ByteArray(length)
                val duplicate = buffer.duplicate()
                duplicate.get(candidate)
                val text = candidate.toString(Charsets.UTF_8)
                if (text.toByteArray(Charsets.UTF_8).contentEquals(candidate) && text.length == charCount) {
                    decoded = text
                    buffer.position(start + length)
                    break
                }
            }
            if (decoded == null || !seen.add(decoded)) return false
        }
        return true
    }

    private fun captureBackupSnapshot(selection: BackupSelection): BackupImportSnapshot {
        val sagerDatabase = SagerDatabase.instance
        return BackupImportSnapshot(
            profiles = if (selection.profile) sagerDatabase.proxyDao().getAll() else null,
            groups = if (selection.profile) sagerDatabase.groupDao().allGroups() else null,
            rules = if (selection.rule) sagerDatabase.rulesDao().allRules() else null,
            settings = if (selection.setting) PublicDatabase.kvPairDao.all() else null,
            cachedGroups = if (selection.profile) DataStore.cachedGroups().toList() else null,
            selectedGroupSetting = if (selection.profile) {
                PublicDatabase.kvPairDao[io.nekohasekai.sagernet.Key.PROFILE_GROUP]
            } else null,
        )
    }

    private fun persistBackup(prepared: PreparedBackup, selection: BackupSelection) {
        if (selection.profile || selection.rule) {
            SagerDatabase.instance.runInTransaction {
                if (selection.profile) {
                    SagerDatabase.proxyDao.reset()
                    SagerDatabase.groupDao.reset()
                    SagerDatabase.groupDao.insert(requireNotNull(prepared.groups))
                    SagerDatabase.proxyDao.insert(requireNotNull(prepared.profiles))
                }
                if (selection.rule) {
                    SagerDatabase.rulesDao.reset()
                    SagerDatabase.rulesDao.insert(requireNotNull(prepared.rules))
                }
            }
        }
        if (selection.setting) {
            PublicDatabase.instance.runInTransaction {
                PublicDatabase.kvPairDao.reset()
                PublicDatabase.kvPairDao.insert(requireNotNull(prepared.settings))
            }
        }
    }

    private fun restoreBackupSnapshot(snapshot: BackupImportSnapshot, selection: BackupSelection) {
        val failures = mutableListOf<Exception>()
        try {
            DataStore.flushConfiguration()
        } catch (failure: Exception) {
            failures += failure
        }
        if (selection.profile || selection.rule) {
            try {
                SagerDatabase.instance.runInTransaction {
                    if (selection.profile) {
                        SagerDatabase.proxyDao.reset()
                        SagerDatabase.groupDao.reset()
                        SagerDatabase.groupDao.insert(requireNotNull(snapshot.groups))
                        SagerDatabase.proxyDao.insert(requireNotNull(snapshot.profiles))
                    }
                    if (selection.rule) {
                        SagerDatabase.rulesDao.reset()
                        SagerDatabase.rulesDao.insert(requireNotNull(snapshot.rules))
                    }
                }
            } catch (failure: Exception) {
                failures += failure
            }
        }
        if (selection.setting || selection.profile) {
            try {
                PublicDatabase.instance.runInTransaction {
                    if (selection.setting) {
                        PublicDatabase.kvPairDao.reset()
                        PublicDatabase.kvPairDao.insert(requireNotNull(snapshot.settings))
                    } else {
                        PublicDatabase.kvPairDao.delete(io.nekohasekai.sagernet.Key.PROFILE_GROUP)
                        snapshot.selectedGroupSetting?.let(PublicDatabase.kvPairDao::put)
                    }
                }
            } catch (failure: Exception) {
                failures += failure
            }
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException("Backup restoration failed.").apply {
                failures.forEach(::addSuppressed)
            }
        }
    }

    private data class DiagnosticsUiState(
        val running: Boolean = false,
        val summary: String = "",
        val details: String = "",
        val hasReport: Boolean = false,
    )

    private data class BackupSelection(
        val profile: Boolean = true,
        val rule: Boolean = true,
        val setting: Boolean = true,
    )

    private data class PreparedBackup(
        val profiles: List<ProxyEntity>?,
        val groups: List<ProxyGroup>?,
        val rules: List<RuleEntity>?,
        val settings: List<KeyValuePair>?,
    )

    private data class BackupImportSnapshot(
        val profiles: List<ProxyEntity>?,
        val groups: List<ProxyGroup>?,
        val rules: List<RuleEntity>?,
        val settings: List<KeyValuePair>?,
        val cachedGroups: List<ProxyGroup>?,
        val selectedGroupSetting: KeyValuePair?,
    )
}
