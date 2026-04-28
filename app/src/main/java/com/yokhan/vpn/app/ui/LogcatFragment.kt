package com.yokhan.vpn.app.ui

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yokhan.vpn.shared.ui.design.YokhanSpacing
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.snackbar
import com.yokhan.vpn.app.ui.compose.YokhanButtonGrid
import com.yokhan.vpn.app.ui.compose.YokhanComposeRouteFragment
import com.yokhan.vpn.app.ui.compose.YokhanRouteAction
import com.yokhan.vpn.app.ui.compose.YokhanRouteCard
import com.yokhan.vpn.app.ui.compose.YokhanRouteFrame
import libcore.Libcore
import moe.matsuri.nb4a.utils.SendLog

class LogcatFragment : YokhanComposeRouteFragment() {

    private val logText = mutableStateOf("")

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        reloadSession()
    }

    @Composable
    override fun Content(spaceActive: Boolean) {
        val text = logText.value
        YokhanRouteFrame(
            title = stringResource(R.string.menu_log),
            systemLabel = stringResource(R.string.dashboard_system_label),
            spaceActive = spaceActive,
            onMenuClick = { (activity as? MainActivity)?.openNavigationMenu() },
        ) { colors, typography ->
            YokhanRouteCard(
                title = stringResource(R.string.menu_log),
                colors = colors,
                typography = typography,
            ) {
                YokhanButtonGrid(
                    actions = listOf(
                        YokhanRouteAction(
                            title = stringResource(R.string.clear_logcat),
                            onClick = ::clearLogcat,
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.logcat),
                            onClick = ::sendLogcat,
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.group_update),
                            primary = true,
                            onClick = ::reloadSession,
                        ),
                    ),
                    colors = colors,
                    typography = typography,
                )
                Text(
                    text = text.ifBlank { "--" },
                    color = colors.secondary,
                    fontFamily = typography.mono,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = YokhanSpacing.ActionTopGap),
                )
            }
        }
    }

    private fun reloadSession() {
        logText.value = String(SendLog.getNekoLog(50 * 1024))
    }

    private fun clearLogcat() {
        runOnDefaultDispatcher {
            try {
                Libcore.nekoLogClear()
                Runtime.getRuntime().exec("/system/bin/logcat -c")
            } catch (e: Exception) {
                onMainDispatcher {
                    snackbar(e.readableMessage).show()
                }
                return@runOnDefaultDispatcher
            }
            onMainDispatcher {
                logText.value = ""
            }
        }
    }

    private fun sendLogcat() {
        val context = requireContext()
        runOnDefaultDispatcher {
            SendLog.sendLog(context, "YokhanVPN")
        }
    }
}
