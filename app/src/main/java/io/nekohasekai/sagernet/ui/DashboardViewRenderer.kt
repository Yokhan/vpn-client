package io.nekohasekai.sagernet.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.yokhan.vpn.shared.domain.ConnectionStatus
import com.yokhan.vpn.shared.domain.DashboardState
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.databinding.LayoutDashboardBinding

internal class DashboardViewRenderer(
    private val context: Context,
    private val binding: LayoutDashboardBinding,
    private val toolbar: Toolbar,
) {

    fun renderConnectionState(
        state: BaseService.State,
        dashboardState: DashboardState,
    ): RenderResult {
        val isConnected = dashboardState.connectionStatus == ConnectionStatus.Connected
        val isStarted = dashboardState.shouldAnimateSpace
        val theme = dashboardTheme(isConnected)

        applySpaceTheme(isStarted, theme)
        applyToolbarTheme(theme)
        applyCardsTheme(theme)
        applyTrafficTheme(theme)
        applyOrbTheme(isConnected, isStarted, theme)
        applyStatusTheme(state, dashboardState, isConnected, theme)
        applyTestButtonTheme(dashboardState.canRunConnectionTest, theme)

        return RenderResult(
            isConnected = isConnected,
            isStarted = isStarted,
            dividerColor = theme.divider,
        )
    }

    private fun dashboardTheme(isConnected: Boolean): DashboardTheme {
        return DashboardTheme(
            primary = colorOf(
                if (isConnected) R.color.dashboard_space_text_primary
                else R.color.dashboard_text_primary
            ),
            secondary = colorOf(
                if (isConnected) R.color.dashboard_space_text_secondary
                else R.color.dashboard_text_secondary
            ),
            tertiary = colorOf(
                if (isConnected) R.color.dashboard_space_text_secondary
                else R.color.dashboard_text_tertiary
            ),
            border = colorOf(
                if (isConnected) R.color.dashboard_card_border_on
                else R.color.dashboard_card_border_off
            ),
            divider = colorOf(
                if (isConnected) R.color.dashboard_divider_dark
                else R.color.dashboard_divider_light
            ),
            accent = colorOf(
                if (isConnected) R.color.dashboard_green
                else R.color.dashboard_accent
            ),
            background = colorOf(
                if (isConnected) R.color.dashboard_space_bg
                else R.color.dashboard_bg_off
            ),
        )
    }

    private fun applySpaceTheme(
        isStarted: Boolean,
        theme: DashboardTheme,
    ) {
        binding.dashboardRoot.setBackgroundColor(theme.background)
        binding.dashboardSpaceBackground.animate().alpha(if (isStarted) 1f else 0f)
            .setDuration(600L).start()
        binding.dashboardSpaceGlow.animate().alpha(if (isStarted) 1f else 0f)
            .setDuration(600L).start()
        binding.dashboardStars.setConnected(isStarted)
        binding.dashboardStars.animate().alpha(if (isStarted) 1f else 0f)
            .setDuration(800L).start()
    }

    private fun applyToolbarTheme(theme: DashboardTheme) {
        toolbar.setTitleTextColor(theme.primary)
        toolbar.navigationIcon?.setTint(theme.primary)
        binding.toolbarSysLabel.setTextColor(theme.secondary)
        binding.toolbarSysDot.background.setTint(theme.accent)
    }

    private fun applyCardsTheme(theme: DashboardTheme) {
        themeCard(binding.currentConfigCard, theme.border)
        themeCard(binding.profilesCard, theme.border)

        binding.currentConfigLabel.setTextColor(theme.tertiary)
        binding.currentProfileName.setTextColor(theme.primary)
        binding.currentProfileInfo.setTextColor(theme.secondary)

        binding.signalBars.setColorFilter(theme.primary)
        binding.signalStatusDot.background.setTint(theme.accent)
    }

    private fun applyTrafficTheme(theme: DashboardTheme) {
        binding.uploadSpeed.setTextColor(theme.primary)
        binding.downloadSpeed.setTextColor(theme.primary)
        binding.uploadArrow.setTextColor(theme.accent)
        binding.downloadArrow.setTextColor(theme.accent)
        binding.uploadWave.setColorFilter(theme.accent)
        binding.downloadWave.setColorFilter(theme.accent)
    }

    private fun applyOrbTheme(
        isConnected: Boolean,
        isStarted: Boolean,
        theme: DashboardTheme,
    ) {
        binding.signalBox.setBackgroundResource(
            if (isConnected) R.drawable.bg_dashboard_signal_box_on
            else R.drawable.bg_dashboard_signal_box_off
        )
        binding.orbBackground.setBackgroundResource(
            if (isConnected) R.drawable.bg_dashboard_orb_on else R.drawable.bg_dashboard_orb_off
        )
        binding.connectRing.setBackgroundResource(
            if (isConnected) R.drawable.bg_dashboard_orb_ring_on
            else R.drawable.bg_dashboard_orb_ring_off
        )
        binding.orbMarks.setConnected(isStarted)
        binding.connectCenterLabel.isVisible = !isStarted
        binding.connectCenterLabel.setTextColor(theme.secondary)
    }

    private fun applyStatusTheme(
        state: BaseService.State,
        dashboardState: DashboardState,
        isConnected: Boolean,
        theme: DashboardTheme,
    ) {
        binding.connectLabel.text = context.getString(
            when (state) {
                BaseService.State.Connected -> R.string.dashboard_connected_status
                BaseService.State.Connecting -> R.string.dashboard_state_connecting
                BaseService.State.Stopping -> R.string.dashboard_state_stopping
                else -> R.string.dashboard_tap_connect
            }
        )
        binding.connectLabel.setTextColor(if (isConnected) theme.primary else theme.tertiary)
        binding.connectLabel.gravity = if (isConnected) {
            Gravity.START or Gravity.CENTER_VERTICAL
        } else {
            Gravity.CENTER
        }

        binding.connectionTimer.isVisible = isConnected
        binding.connectionTimer.setTextColor(if (isConnected) theme.primary else theme.tertiary)
        binding.connectionTimer.text = dashboardState.timerLabel
    }

    private fun applyTestButtonTheme(
        canRunConnectionTest: Boolean,
        theme: DashboardTheme,
    ) {
        binding.testConnectionButton.isEnabled = canRunConnectionTest
        binding.testConnectionButton.alpha = if (canRunConnectionTest) 1f else 0.55f
        binding.testConnectionButton.background = ContextCompat.getDrawable(
            context,
            if (canRunConnectionTest) R.drawable.bg_dashboard_test_button_on
            else R.drawable.bg_dashboard_test_button_off
        )
        binding.testConnectionButton.setTextColor(theme.secondary)
        binding.testConnectionButton.compoundDrawablesRelative.forEach { drawable ->
            drawable?.setTint(theme.secondary)
        }
    }

    private fun themeCard(card: MaterialCardView, strokeColor: Int) {
        card.setCardBackgroundColor(Color.TRANSPARENT)
        card.strokeColor = strokeColor
    }

    private fun colorOf(colorRes: Int): Int {
        return ContextCompat.getColor(context, colorRes)
    }

    data class RenderResult(
        val isConnected: Boolean,
        val isStarted: Boolean,
        val dividerColor: Int,
    )

    private data class DashboardTheme(
        val primary: Int,
        val secondary: Int,
        val tertiary: Int,
        val border: Int,
        val divider: Int,
        val accent: Int,
        val background: Int,
    )
}
