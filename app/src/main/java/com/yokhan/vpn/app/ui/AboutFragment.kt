package com.yokhan.gates.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.yokhan.gates.app.update.YokhanAppUpdater
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import com.yokhan.gates.shared.ui.route.YokhanBody
import com.yokhan.gates.shared.ui.route.YokhanButtonGrid
import com.yokhan.gates.shared.ui.route.YokhanCaption
import com.yokhan.gates.app.ui.compose.YokhanComposeRouteFragment
import com.yokhan.gates.shared.ui.route.YokhanHeadline
import com.yokhan.gates.shared.ui.route.YokhanMonoValue
import com.yokhan.gates.shared.ui.route.YokhanRouteAction
import com.yokhan.gates.shared.ui.route.YokhanRouteCard
import com.yokhan.gates.shared.ui.route.YokhanRouteFrame
import libcore.Libcore

class AboutFragment : YokhanComposeRouteFragment() {

    private val aboutState = mutableStateOf(
        AboutUiState(
            appVersion = SagerNet.appVersionNameForDisplay,
            singBoxVersion = "",
            license = "",
            showBatteryCard = false,
        )
    )

    private val requestIgnoreBatteryOptimizations = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            bindBatteryOptimizationCard()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        aboutState.value = aboutState.value.copy(singBoxVersion = Libcore.versionBox())
        bindBatteryOptimizationCard()
        loadLicense()
    }

    @Composable
    override fun Content(spaceActive: Boolean) {
        val state = aboutState.value
        YokhanRouteFrame(
            title = stringResource(R.string.menu_about),
            systemLabel = stringResource(R.string.dashboard_system_label),
            spaceActive = spaceActive,
            onMenuClick = { (activity as? MainActivity)?.openNavigationMenu() },
        ) { colors, typography ->
            YokhanRouteCard(
                title = stringResource(R.string.app_name_long),
                colors = colors,
                typography = typography,
            ) {
                YokhanHeadline(
                    text = stringResource(R.string.app_name_long),
                    colors = colors,
                    typography = typography,
                )
                YokhanBody(
                    text = stringResource(R.string.yokhan_app_desc),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 8.dp),
                    maxLines = 5,
                )
            }

            YokhanRouteCard(
                title = stringResource(R.string.app_version),
                colors = colors,
                typography = typography,
            ) {
                YokhanMonoValue(state.appVersion, colors, typography, sizeSp = 15)
                YokhanCaption(
                    text = state.singBoxVersion,
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 5.dp),
                )
                YokhanButtonGrid(
                    actions = listOf(
                        YokhanRouteAction(
                            title = stringResource(R.string.check_update_release),
                            onClick = {
                                YokhanAppUpdater.checkManually(requireContext(), checkPreview = false)
                            },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.check_update_preview),
                            onClick = {
                                YokhanAppUpdater.checkManually(requireContext(), checkPreview = true)
                            },
                        ),
                    ),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = YokhanSpacing.ActionTopGap),
                )
            }

            YokhanRouteCard(
                title = stringResource(R.string.project),
                colors = colors,
                typography = typography,
            ) {
                YokhanHeadline(
                    text = stringResource(R.string.github),
                    colors = colors,
                    typography = typography,
                )
                YokhanBody(
                    text = BuildConfig.YOKHAN_APP_DOWNLOAD_URL.removePrefix("https://"),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 5.dp),
                )
                YokhanButtonGrid(
                    actions = listOf(
                        YokhanRouteAction(
                            title = stringResource(R.string.project),
                            primary = true,
                            onClick = { requireContext().launchCustomTab(BuildConfig.YOKHAN_APP_DOWNLOAD_URL) },
                        ),
                    ),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = YokhanSpacing.ActionTopGap),
                )
            }

            if (state.showBatteryCard) {
                YokhanRouteCard(
                    title = stringResource(R.string.ignore_battery_optimizations),
                    colors = colors,
                    typography = typography,
                ) {
                    YokhanBody(
                        text = stringResource(R.string.ignore_battery_optimizations_sum),
                        colors = colors,
                        typography = typography,
                        maxLines = 4,
                    )
                    YokhanButtonGrid(
                        actions = listOf(
                            YokhanRouteAction(
                                title = stringResource(R.string.ignore_battery_optimizations),
                                onClick = ::requestBatteryOptimizationExemption,
                            ),
                        ),
                        colors = colors,
                        typography = typography,
                    modifier = Modifier.padding(top = YokhanSpacing.ActionTopGap),
                    )
                }
            }

            YokhanRouteCard(
                title = stringResource(R.string.license),
                colors = colors,
                typography = typography,
            ) {
                Text(
                    text = state.license.ifBlank { "--" },
                    color = colors.secondary,
                    fontFamily = typography.mono,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        requestIgnoreBatteryOptimizations.launch(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "package:${requireContext().packageName}".toUri(),
            )
        )
    }

    private fun bindBatteryOptimizationCard() {
        val shouldShowBatteryCard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(requireContext().packageName).not()
        } else {
            false
        }
        aboutState.value = aboutState.value.copy(showBatteryCard = shouldShowBatteryCard)
    }

    private fun loadLicense() {
        runOnDefaultDispatcher {
            val license = requireContext().assets.open("LICENSE").bufferedReader().readText()
            onMainDispatcher {
                aboutState.value = aboutState.value.copy(license = license)
            }
        }
    }

    private data class AboutUiState(
        val appVersion: String,
        val singBoxVersion: String,
        val license: String,
        val showBatteryCard: Boolean,
    )
}
