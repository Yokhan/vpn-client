package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.util.Linkify
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutAboutBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.widget.ListListener
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject

class AboutFragment : ToolbarFragment(R.layout.layout_about) {

    private val requestIgnoreBatteryOptimizations = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            bindBatteryOptimizationCard()
        }
    }

    private lateinit var binding: LayoutAboutBinding

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding = LayoutAboutBinding.bind(view)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_about)

        binding.appVersionValue.text = SagerNet.appVersionNameForDisplay
        binding.singBoxVersionValue.text = Libcore.versionBox()
        binding.versionCard.setOnClickListener {
            requireContext().launchCustomTab(YOKHAN_RELEASES_URL)
        }
        binding.releaseUpdateButton.setOnClickListener { checkUpdate(checkPreview = false) }
        binding.previewUpdateButton.setOnClickListener { checkUpdate(checkPreview = true) }
        binding.githubCard.setOnClickListener {
            requireContext().launchCustomTab(YOKHAN_REPO_URL)
        }
        binding.batteryCard.setOnClickListener {
            requestIgnoreBatteryOptimizations.launch(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    "package:${requireContext().packageName}".toUri(),
                )
            )
        }

        bindBatteryOptimizationCard()
        loadLicense()
    }

    private fun bindBatteryOptimizationCard() {
        if (!::binding.isInitialized) return
        val shouldShowBatteryCard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(requireContext().packageName).not()
        } else {
            false
        }
        binding.batteryCard.isVisible = shouldShowBatteryCard
    }

    private fun loadLicense() {
        runOnDefaultDispatcher {
            val license = requireContext().assets.open("LICENSE").bufferedReader().readText()
            onMainDispatcher {
                binding.license.text = license
                Linkify.addLinks(binding.license, Linkify.EMAIL_ADDRESSES or Linkify.WEB_URLS)
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

    private companion object {
        const val YOKHAN_REPO_URL = "https://github.com/Yokhan/YokhanVPN"
        const val YOKHAN_RELEASES_URL = "$YOKHAN_REPO_URL/releases"
        const val YOKHAN_LATEST_RELEASE_API_URL = "https://api.github.com/repos/Yokhan/YokhanVPN/releases/latest"
        const val YOKHAN_PREVIEW_RELEASE_API_URL = "https://api.github.com/repos/Yokhan/YokhanVPN/releases/tags/preview"
    }
}
