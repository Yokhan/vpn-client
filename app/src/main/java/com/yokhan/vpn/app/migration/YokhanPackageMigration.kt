package com.yokhan.gates.app.migration

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R

object YokhanPackageMigration {
    fun showIntroIfNeeded(context: Context) {
        val legacyPackage = installedLegacyPackage(context) ?: return
        val preferences = preferences(context)
        if (preferences.getBoolean(KEY_INTRO_SHOWN, false)) return
        preferences.edit().putBoolean(KEY_INTRO_SHOWN, true).apply()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.gates_migration_title)
            .setMessage(R.string.gates_migration_intro)
            .setPositiveButton(R.string.gates_migration_continue, null)
            .setNeutralButton(R.string.gates_migration_open_legacy) { _, _ ->
                context.packageManager.getLaunchIntentForPackage(legacyPackage)?.let(context::startActivity)
            }
            .show()
    }

    private fun installedLegacyPackage(context: Context): String? =
        LEGACY_PACKAGE_IDS.firstOrNull { packageId ->
            runCatching { context.packageManager.getPackageInfo(packageId, 0) }.isSuccess
        }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val LEGACY_PACKAGE_IDS = listOf(
        "com.buffit.gates",
        "com.buffitgates.vpnservice",
    )
    private const val PREFERENCES_NAME = "gates_package_migration"
    private const val KEY_INTRO_SHOWN = "intro_shown"
}
