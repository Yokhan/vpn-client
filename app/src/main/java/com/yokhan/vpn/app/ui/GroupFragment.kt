package com.yokhan.vpn.app.ui

import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yokhan.vpn.shared.ui.design.YokhanPalette
import com.yokhan.vpn.shared.ui.design.YokhanTypography
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import com.yokhan.vpn.app.ui.compose.YokhanButtonGrid
import com.yokhan.vpn.app.ui.compose.YokhanCaption
import com.yokhan.vpn.app.ui.compose.YokhanComposeRouteFragment
import com.yokhan.vpn.app.ui.compose.YokhanDivider
import com.yokhan.vpn.app.ui.compose.YokhanMonoValue
import com.yokhan.vpn.app.ui.compose.YokhanRouteAction
import com.yokhan.vpn.app.ui.compose.YokhanRouteCard
import com.yokhan.vpn.app.ui.compose.YokhanRouteLazyFrame
import com.yokhan.vpn.app.ui.compose.YokhanRouteProgressBar
import com.yokhan.vpn.app.ui.compose.YokhanRouteStatusDot
import com.yokhan.vpn.app.ui.compose.yokhanOutlinedSurface
import com.yokhan.vpn.app.ui.shell.YokhanShellActivityRoute
import com.yokhan.vpn.shared.ui.design.YokhanSpacing
import io.nekohasekai.sagernet.widget.QRCodeDialog
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.toBytesString
import java.lang.NumberFormatException
import java.text.SimpleDateFormat
import java.util.Locale

class GroupFragment : YokhanComposeRouteFragment(), GroupManager.Listener {

    private val uiState = mutableStateOf(GroupUiState())
    private lateinit var selectedGroup: ProxyGroup

    private val exportProfiles =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { data ->
            if (data == null || !::selectedGroup.isInitialized) return@registerForActivityResult
            exportGroupProfilesToFile(selectedGroup, data)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        GroupManager.addListener(this)
        reloadGroups()
    }

    override fun onResume() {
        super.onResume()
        reloadGroups()
    }

    override fun onDestroyView() {
        GroupManager.removeListener(this)
        super.onDestroyView()
    }

    @Composable
    override fun Content(spaceActive: Boolean) {
        val state = uiState.value
        YokhanRouteLazyFrame(
            title = stringResource(R.string.menu_group),
            systemLabel = stringResource(R.string.dashboard_system_label),
            spaceActive = spaceActive,
            onMenuClick = { (activity as? MainActivity)?.openNavigationMenu() },
        ) { colors, typography ->
            item(key = "group-actions") {
                YokhanRouteCard(
                    title = stringResource(R.string.group_hub_actions_title),
                    colors = colors,
                    typography = typography,
                ) {
                    YokhanButtonGrid(
                        actions = listOf(
                            YokhanRouteAction(
                                title = stringResource(R.string.group_create),
                                primary = true,
                                onClick = {
                                    (activity as? MainActivity)?.openActivityRoute(
                                        YokhanShellActivityRoute.GroupCreate,
                                    )
                                },
                            ),
                            YokhanRouteAction(
                                title = stringResource(R.string.update_all_subscription),
                                enabled = state.rows.any { it.group.type == GroupType.SUBSCRIPTION },
                                onClick = ::confirmUpdateAllSubscriptions,
                            ),
                        ),
                        colors = colors,
                        typography = typography,
                    )
                }
            }

            if (state.rows.isEmpty()) {
                item(key = "empty-groups") {
                    YokhanRouteCard(
                        title = stringResource(R.string.group_hub_overview_title),
                        colors = colors,
                        typography = typography,
                    ) {
                        YokhanMonoValue(
                            text = stringResource(R.string.group_status_empty),
                            colors = colors,
                            typography = typography,
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = state.rows,
                    key = { _, row -> row.group.id },
                ) { index, row ->
                    GroupCard(
                        row = row,
                        index = index,
                        total = state.rows.size,
                        colors = colors,
                        typography = typography,
                    )
                }
            }
        }
    }

    @Composable
    private fun GroupCard(
        row: GroupRow,
        index: Int,
        total: Int,
        colors: YokhanPalette,
        typography: YokhanTypography,
    ) {
        val group = row.group
        val updating = row.updating
        val canModify = !group.ungrouped && !updating
        val isSubscription = group.type == GroupType.SUBSCRIPTION

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .yokhanOutlinedSurface(colors),
        ) {
            if (updating) {
                GroupProgress(row, colors)
            }
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = group.displayName(),
                            color = colors.primary,
                            fontFamily = typography.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier.padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            YokhanRouteStatusDot(
                                active = isSubscription,
                                colors = colors,
                                inactiveColor = colors.secondary,
                            )
                            YokhanCaption(
                                text = row.typeLabel,
                                colors = colors,
                                typography = typography,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        if (row.user.isNotBlank()) {
                            YokhanCaption(
                                text = row.user,
                                colors = colors,
                                typography = typography,
                                modifier = Modifier.padding(top = 5.dp),
                            )
                        }
                    }
                    YokhanMonoValue(
                        text = row.profileCount.toString(),
                        colors = colors,
                        typography = typography,
                        sizeSp = 12,
                    )
                }

                YokhanDivider(colors, Modifier.padding(vertical = YokhanSpacing.DividerGap))

                YokhanMonoValue(
                    text = row.status,
                    colors = colors,
                    typography = typography,
                    sizeSp = 12,
                    maxLines = 2,
                )
                if (row.traffic.isNotBlank()) {
                    YokhanCaption(
                        text = row.traffic,
                        colors = colors,
                        typography = typography,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                YokhanButtonGrid(
                    actions = groupActions(group, index, total, canModify, isSubscription),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }

    @Composable
    private fun GroupProgress(row: GroupRow, colors: YokhanPalette) {
        YokhanRouteProgressBar(
            progressFraction = row.progressFraction ?: 1f,
            colors = colors,
        )
    }

    @Composable
    private fun groupActions(
        group: ProxyGroup,
        index: Int,
        total: Int,
        canModify: Boolean,
        isSubscription: Boolean,
    ): List<YokhanRouteAction> = buildList {
        add(
            YokhanRouteAction(
                title = stringResource(R.string.group_edit),
                enabled = canModify,
                onClick = { editGroup(group) },
            ),
        )
        if (isSubscription) {
            add(
                YokhanRouteAction(
                    title = stringResource(R.string.group_update),
                    primary = true,
                    enabled = canModify,
                    onClick = { GroupUpdater.startUpdate(group, true) },
                ),
            )
            add(
                YokhanRouteAction(
                    title = stringResource(R.string.action_export_clipboard),
                    onClick = { exportSubscriptionToClipboard(group) },
                ),
            )
            add(
                YokhanRouteAction(
                    title = "QR",
                    onClick = { showSubscriptionQr(group) },
                ),
            )
        }
        add(
            YokhanRouteAction(
                title = stringResource(R.string.action_export_file),
                onClick = { exportProfilesToFile(group) },
            ),
        )
        add(
            YokhanRouteAction(
                title = stringResource(R.string.action_export_clipboard),
                onClick = { exportProfilesToClipboard(group) },
            ),
        )
        add(
            YokhanRouteAction(
                title = stringResource(R.string.group_move_up),
                enabled = canModify && index > 0,
                onClick = { moveGroup(index, index - 1) },
            ),
        )
        add(
            YokhanRouteAction(
                title = stringResource(R.string.group_move_down),
                enabled = canModify && index < total - 1,
                onClick = { moveGroup(index, index + 1) },
            ),
        )
        add(
            YokhanRouteAction(
                title = stringResource(R.string.clear_profiles),
                enabled = !group.ungrouped,
                onClick = { confirmClearGroup(group) },
            ),
        )
        add(
            YokhanRouteAction(
                title = stringResource(R.string.delete),
                enabled = canModify,
                onClick = { confirmDeleteGroup(group) },
            ),
        )
    }

    private fun reloadGroups() {
        runOnDefaultDispatcher {
            val groups = SagerDatabase.groupDao.allGroups().toMutableList()
            groups.find { it.ungrouped }?.let { ungrouped ->
                if (groups.size > 1 && SagerDatabase.proxyDao.countByGroup(ungrouped.id) == 0L) {
                    groups.removeAll { it.ungrouped }
                }
            }
            DataStore.cacheGroups(groups)
            val rows = groups.map(::buildGroupRow)
            onMainDispatcher {
                uiState.value = GroupUiState(rows = rows)
            }
        }
    }

    private fun buildGroupRow(group: ProxyGroup): GroupRow {
        val count = SagerDatabase.proxyDao.countByGroup(group.id)
        val subscription = group.subscription
        val typeLabel = app.getString(
            if (group.type == GroupType.SUBSCRIPTION) {
                R.string.configuration_hub_group_type_subscription
            } else {
                R.string.configuration_hub_group_type_local
            },
        )
        val status = when (group.type) {
            GroupType.SUBSCRIPTION -> {
                if (count == 0L) {
                    app.getString(R.string.group_status_empty_subscription)
                } else {
                    val updatedAt = SimpleDateFormat("M - d", Locale.getDefault())
                        .format(group.subscription!!.lastUpdated * 1000L)
                    app.getString(R.string.group_status_proxies_subscription, count, updatedAt)
                }
            }
            else -> {
                if (count == 0L) {
                    app.getString(R.string.group_status_empty)
                } else {
                    app.getString(R.string.group_status_proxies, count)
                }
            }
        }
        val progress = GroupUpdater.progress[group.id]
        return GroupRow(
            group = group,
            profileCount = count,
            typeLabel = typeLabel,
            status = status,
            traffic = subscriptionTrafficText(group),
            user = subscription?.username.orEmpty(),
            updating = group.id in GroupUpdater.updating,
            progressFraction = progress?.let { value ->
                if (value.max <= 0) null else {
                    (value.progress.toFloat() / value.max.toFloat()).coerceIn(0f, 1f)
                }
            },
        )
    }

    private fun subscriptionTrafficText(group: ProxyGroup): String {
        val subscription = group.subscription ?: return ""
        if (subscription.bytesUsed > 0L) {
            return if (subscription.bytesRemaining > 0L) {
                app.getString(
                    R.string.subscription_traffic,
                    Formatter.formatFileSize(app, subscription.bytesUsed),
                    Formatter.formatFileSize(app, subscription.bytesRemaining),
                )
            } else {
                app.getString(
                    R.string.subscription_used,
                    Formatter.formatFileSize(app, subscription.bytesUsed),
                )
            }
        }
        val userInfo = subscription.subscriptionUserinfo
        if (userInfo.isNullOrBlank()) return ""

        fun get(regex: String): String? {
            return regex.toRegex().findAll(userInfo).mapNotNull {
                if (it.groupValues.size > 1) it.groupValues[1] else null
            }.firstOrNull()
        }

        return try {
            var text = ""
            var used = 0L
            get("upload=([0-9]+)")?.let { used += it.toLong() }
            get("download=([0-9]+)")?.let { used += it.toLong() }
            val total = get("total=([0-9]+)")?.toLong() ?: 0L
            val remain = total - used
            if (used > 0 || total > 0) {
                text += if (remain > 0) {
                    app.getString(
                        R.string.subscription_traffic,
                        used.toBytesString(),
                        remain.toBytesString(),
                    )
                } else {
                    app.getString(R.string.subscription_used, used.toBytesString())
                }
            }
            get("expire=([0-9]+)")?.let {
                if (text.isNotBlank()) text += "\n"
                text += app.getString(
                    R.string.subscription_expire,
                    Util.timeStamp2Text(it.toLong() * 1000L),
                )
            }
            text
        } catch (_: NumberFormatException) {
            ""
        }
    }

    private fun editGroup(group: ProxyGroup) {
        if (group.ungrouped) return
        (activity as? MainActivity)?.openActivityRoute(
            YokhanShellActivityRoute.GroupEdit(group.id),
        )
    }

    private fun confirmUpdateAllSubscriptions() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm)
            .setMessage(R.string.update_all_subscription)
            .setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    SagerDatabase.groupDao.allGroups()
                        .filter { it.type == GroupType.SUBSCRIPTION }
                        .forEach { GroupUpdater.startUpdate(it, true) }
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun confirmClearGroup(group: ProxyGroup) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm)
            .setMessage(R.string.clear_profiles_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    GroupManager.clearGroup(group.id)
                    reloadGroups()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteGroup(group: ProxyGroup) {
        if (group.ungrouped) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm)
            .setMessage(R.string.group_delete_confirm_prompt)
            .setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    GroupManager.deleteGroup(group.id)
                    reloadGroups()
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun moveGroup(from: Int, to: Int) {
        val rows = uiState.value.rows
        if (from !in rows.indices || to !in rows.indices) return
        val source = rows[from].group.copy()
        val target = rows[to].group.copy()
        if (source.ungrouped || target.ungrouped) return

        val sourceOrder = source.userOrder
        source.userOrder = target.userOrder
        target.userOrder = sourceOrder

        runOnDefaultDispatcher {
            SagerDatabase.groupDao.updateGroup(source)
            SagerDatabase.groupDao.updateGroup(target)
            GroupManager.postUpdate(source)
            GroupManager.postUpdate(target)
            reloadGroups()
        }
    }

    private fun exportSubscriptionToClipboard(group: ProxyGroup) {
        val success = SagerNet.trySetPrimaryClip(group.toUniversalLink())
        snackbar(if (success) R.string.action_export_msg else R.string.action_export_err).show()
    }

    private fun showSubscriptionQr(group: ProxyGroup) {
        QRCodeDialog(
            group.toUniversalLink(),
            group.displayName(),
        ).showAllowingStateLoss(parentFragmentManager)
    }

    private fun exportProfilesToClipboard(group: ProxyGroup) {
        runOnDefaultDispatcher {
            val profiles = SagerDatabase.proxyDao.getByGroup(group.id)
            val links = profiles.joinToString("\n") { it.toStdLink(compact = true) }
            onMainDispatcher {
                SagerNet.trySetPrimaryClip(links)
                snackbar(getString(R.string.copy_toast_msg)).show()
            }
        }
    }

    private fun exportProfilesToFile(group: ProxyGroup) {
        selectedGroup = group
        startFilesForResult(exportProfiles, "profiles_${group.displayName()}.txt")
    }

    private fun exportGroupProfilesToFile(group: ProxyGroup, data: Uri) {
        val resolver = requireActivity().contentResolver
        runOnDefaultDispatcher {
            val profiles = SagerDatabase.proxyDao.getByGroup(group.id)
            val links = profiles.joinToString("\n") { it.toStdLink(compact = true) }
            try {
                resolver.openOutputStream(data)!!
                    .bufferedWriter()
                    .use { it.write(links) }
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

    override suspend fun groupAdd(group: ProxyGroup) {
        if (group.type == GroupType.SUBSCRIPTION) {
            GroupUpdater.startUpdate(group, true)
        }
        reloadGroups()
    }

    override suspend fun groupUpdated(group: ProxyGroup) {
        reloadGroups()
    }

    override suspend fun groupRemoved(groupId: Long) {
        reloadGroups()
    }

    override suspend fun groupUpdated(groupId: Long) {
        reloadGroups()
    }

    private data class GroupUiState(
        val rows: List<GroupRow> = emptyList(),
    )

    private data class GroupRow(
        val group: ProxyGroup,
        val profileCount: Long,
        val typeLabel: String,
        val status: String,
        val traffic: String,
        val user: String,
        val updating: Boolean,
        val progressFraction: Float?,
    )
}
