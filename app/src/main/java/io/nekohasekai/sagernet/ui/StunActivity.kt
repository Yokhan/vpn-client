package io.nekohasekai.sagernet.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yokhan.vpn.shared.ui.design.YokhanPalette
import com.yokhan.vpn.shared.ui.design.YokhanTypography
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.compose.YokhanBody
import io.nekohasekai.sagernet.ui.compose.YokhanButtonGrid
import io.nekohasekai.sagernet.ui.compose.YokhanCaption
import io.nekohasekai.sagernet.ui.compose.YokhanMonoValue
import io.nekohasekai.sagernet.ui.compose.YokhanRouteAction
import io.nekohasekai.sagernet.ui.compose.YokhanRouteCard
import io.nekohasekai.sagernet.ui.compose.YokhanRouteFrame
import libcore.Libcore

class StunActivity : ThemedActivity() {

    private val server = mutableStateOf("stun.voipgate.com:3478")
    private val result = mutableStateOf("")
    private val loading = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Content()
        }
    }

    @Composable
    private fun Content() {
        YokhanRouteFrame(
            title = stringResource(R.string.stun_test),
            systemLabel = stringResource(R.string.dashboard_system_label),
            spaceActive = DataStore.serviceState.isSpaceActive(),
            onMenuClick = ::finish,
        ) { colors, typography ->
            YokhanRouteCard(
                title = stringResource(R.string.nat_stun_server_hint),
                colors = colors,
                typography = typography,
            ) {
                YokhanBody(
                    text = stringResource(R.string.stun_test_summary),
                    colors = colors,
                    typography = typography,
                    maxLines = 4,
                )
                ServerInput(colors, typography, Modifier.padding(top = 14.dp))
            }

            YokhanRouteCard(
                title = stringResource(R.string.nat_result_hint),
                colors = colors,
                typography = typography,
            ) {
                if (loading.value) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            color = colors.accent,
                            strokeWidth = 2.dp,
                        )
                        YokhanMonoValue(
                            text = stringResource(R.string.stun_attest_loading),
                            colors = colors,
                            typography = typography,
                            modifier = Modifier.padding(start = 14.dp),
                            sizeSp = 12,
                            maxLines = 2,
                        )
                    }
                } else if (result.value.isBlank()) {
                    YokhanCaption(
                        text = stringResource(R.string.nat_result_hint),
                        colors = colors,
                        typography = typography,
                    )
                } else {
                    YokhanMonoValue(
                        text = result.value,
                        colors = colors,
                        typography = typography,
                        sizeSp = 12,
                        maxLines = 12,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            YokhanButtonGrid(
                actions = listOf(
                    YokhanRouteAction(
                        title = stringResource(R.string.start),
                        primary = true,
                        enabled = !loading.value && server.value.isNotBlank(),
                        onClick = { doTest() },
                    ),
                ),
                colors = colors,
                typography = typography,
            )
        }
    }

    @Composable
    private fun ServerInput(
        colors: YokhanPalette,
        typography: YokhanTypography,
        modifier: Modifier = Modifier,
    ) {
        BasicTextField(
            value = server.value,
            onValueChange = { server.value = it },
            singleLine = true,
            cursorBrush = SolidColor(colors.accent),
            textStyle = TextStyle(
                color = colors.primary,
                fontFamily = typography.mono,
                fontSize = 14.sp,
            ),
            modifier = modifier.fillMaxWidth(),
            decorationBox = { input ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.primary.copy(alpha = 0.035f))
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                ) {
                    if (server.value.isBlank()) {
                        YokhanCaption(
                            text = "host:port",
                            colors = colors,
                            typography = typography,
                        )
                    }
                    input()
                }
            },
        )
    }

    private fun doTest() {
        if (loading.value) return
        loading.value = true
        val stunServer = server.value.trim()
        runOnDefaultDispatcher {
            val stunResult = try {
                val _result = Libcore.stunTest(stunServer)
                if (_result!!.success) {
                    _result.text
                } else {
                    throw Exception(_result.text)
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    loading.value = false
                    MaterialAlertDialogBuilder(this@StunActivity)
                        .setTitle(R.string.error_title)
                        .setMessage(e.readableMessage)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            finish()
                        }
                        .setOnCancelListener {
                            finish()
                        }
                        .runCatching { show() }
                }
                return@runOnDefaultDispatcher
            }
            onMainDispatcher {
                loading.value = false
                result.value = stunResult
            }
        }
    }

    private fun BaseService.State.isSpaceActive(): Boolean {
        return this == BaseService.State.Connecting ||
            this == BaseService.State.Connected ||
            this == BaseService.State.Stopping
    }

}
