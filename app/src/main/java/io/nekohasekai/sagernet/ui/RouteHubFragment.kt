package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutRouteHubBinding
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RouteHubFragment : ToolbarFragment(R.layout.layout_route_hub) {

    private var _binding: LayoutRouteHubBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = LayoutRouteHubBinding.bind(view)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.route_hub_title)

        binding.openRouteListButton.setOnClickListener {
            (activity as? MainActivity)?.openSubFragment(RouteFragment())
        }
        binding.newRouteButton.setOnClickListener {
            startActivity(Intent(requireActivity(), RouteSettingsActivity::class.java))
        }
        binding.manageAssetsButton.setOnClickListener {
            startActivity(Intent(requireActivity(), AssetsActivity::class.java))
        }
        binding.resetRoutesButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.confirm)
                .setMessage(R.string.clear_profiles_message)
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        SagerDatabase.rulesDao.reset()
                        DataStore.rulesFirstCreate = false
                    }
                    refreshSummary()
                }
                .setNegativeButton(R.string.no, null)
                .show()
        }

        refreshSummary()
    }

    override fun onResume() {
        super.onResume()
        refreshSummary()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun refreshSummary() {
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                val rules = ProfileManager.getRules()
                val enabledRules = rules.count { it.enabled }
                val dbAssets = countRouteAssets()
                RouteSummary(
                    totalRules = rules.size,
                    enabledRules = enabledRules,
                    assetCount = dbAssets
                )
            }

            val binding = _binding ?: return@launch
            binding.ruleTotalsValue.text = resources.getQuantityString(
                R.plurals.route_hub_rule_totals,
                summary.totalRules,
                summary.totalRules,
                summary.enabledRules
            )
            binding.assetTotalsValue.text = resources.getQuantityString(
                R.plurals.route_hub_asset_totals,
                summary.assetCount,
                summary.assetCount
            )
        }
    }

    private fun countRouteAssets(): Int {
        val filesDir = requireContext().filesDir
        var count = 0
        if (File(filesDir, "geoip.db").isFile) count++
        if (File(filesDir, "geosite.db").isFile) count++
        return count
    }

    private data class RouteSummary(
        val totalRules: Int,
        val enabledRules: Int,
        val assetCount: Int,
    )
}
