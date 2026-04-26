package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.compose.YokhanBody
import io.nekohasekai.sagernet.ui.compose.YokhanButtonGrid
import io.nekohasekai.sagernet.ui.compose.YokhanCaption
import io.nekohasekai.sagernet.ui.compose.YokhanComposeRouteFragment
import io.nekohasekai.sagernet.ui.compose.YokhanHeadline
import io.nekohasekai.sagernet.ui.compose.YokhanMonoValue
import io.nekohasekai.sagernet.ui.compose.YokhanRouteAction
import io.nekohasekai.sagernet.ui.compose.YokhanRouteCard
import io.nekohasekai.sagernet.ui.compose.YokhanRouteFrame
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject

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
                            onClick = { checkUpdate(checkPreview = false) },
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.check_update_preview),
                            onClick = { checkUpdate(checkPreview = true) },
                        ),
                    ),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 14.dp),
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
                    text = "github.com/Yokhan/YokhanVPN",
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 5.dp),
                )
                YokhanButtonGrid(
                    actions = listOf(
                        YokhanRouteAction(
                            title = stringResource(R.string.project),
                            primary = true,
                            onClick = { requireContext().launchCustomTab(YOKHAN_REPO_URL) },
                        ),
                    ),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 14.dp),
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
                        modifier = Modifier.padding(top = 14.dp),
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

    private fun checkUpdate(checkPreview: Boolean) {
        runOnIoDispatcher {
            try {
                val client = Libcore.newHttpClient().apply {
                    modernTLS()
                    trySocks5(DataStore.mixedPort)
                }
                val response = client.newRequest().apply {
                    setURL(
                        if (checkPreview) YOKHAN_PREVIEW_RELEASE_API_URL else YOKHAN_LATEST_RELEASE_API_URL
                    )
                }.execute()
                val release = JSONObject(Util.getStringBox(response.contentString))
                val releaseName = release.getString("name")
                val releaseUrl = release.getString("html_url")
                var haveUpdate = releaseName.isNotBlank()
                haveUpdate = if (isPreview) {
                    if (checkPreview) {
                        haveUpdate && releaseName != BuildConfig.PRE_VERSION_NAME
                    } else {
                        haveUpdate && releaseName != BuildConfig.VERSION_NAME
                    }
                } else {
                    haveUpdate && !releaseName.contains(BuildConfig.VERSION_NAME)
                }
                runOnMainDispatcher {
                    if (haveUpdate) {
                        val context = requireContext()
                        MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.update_dialog_title)
                            .setMessage(
                                context.getString(
                                    R.string.update_dialog_message,
                                    SagerNet.appVersionNameForDisplay,
                                    releaseName,
                                )
                            )
                            .setPositiveButton(R.string.yes) { _, _ ->
                                context.startActivity(Intent(Intent.ACTION_VIEW, releaseUrl.toUri()))
                            }
                            .setNegativeButton(R.string.no, null)
                            .show()
                    } else {
                        Toast.makeText(requireContext(), R.string.check_update_no, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
                runOnMainDispatcher {
                    Toast.makeText(requireContext(), e.readableMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private data class AboutUiState(
        val appVersion: String,
        val singBoxVersion: String,
        val license: String,
        val showBatteryCard: Boolean,
    )

    private companion object {
        const val YOKHAN_REPO_URL = "https://github.com/Yokhan/YokhanVPN"
        const val YOKHAN_LATEST_RELEASE_API_URL = "https://api.github.com/repos/Yokhan/YokhanVPN/releases/latest"
        const val YOKHAN_PREVIEW_RELEASE_API_URL = "https://api.github.com/repos/Yokhan/YokhanVPN/releases/tags/preview"
    }
}
