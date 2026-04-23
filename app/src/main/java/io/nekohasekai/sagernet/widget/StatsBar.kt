package io.nekohasekai.sagernet.widget

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomappbar.BottomAppBar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StatsBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {

    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var latencyValue: TextView
    private lateinit var protocolValue: TextView
    private lateinit var statsTopBorder: View
    private lateinit var statsDividerStart: View
    private lateinit var statsDividerEnd: View
    private lateinit var latencyIcon: TextView
    private lateinit var latencyHeading: TextView
    private lateinit var statusHeading: TextView
    private lateinit var protocolIcon: TextView
    private lateinit var protocolHeading: TextView
    private lateinit var behavior: YourBehavior

    var allowShow = true

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior { allowShow }
        return behavior
    }

    class YourBehavior(val getAllowShow: () -> Boolean) : Behavior() {

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout,
            child: BottomAppBar,
            target: View,
            dxConsumed: Int,
            dyConsumed: Int,
            dxUnconsumed: Int,
            dyUnconsumed: Int,
            type: Int,
            consumed: IntArray,
        ) {
            super.onNestedScroll(
                coordinatorLayout,
                child,
                target,
                dxConsumed,
                dyConsumed + dyUnconsumed,
                dxUnconsumed,
                0,
                type,
                consumed
            )
        }

        override fun slideUp(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideUp(child)
        }

        override fun slideDown(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideDown(child)
        }
    }

    override fun setOnClickListener(l: OnClickListener?) {
        statusDot = findViewById(R.id.status_dot)
        statusLabel = findViewById(R.id.status_label)
        latencyValue = findViewById(R.id.latency_value)
        protocolValue = findViewById(R.id.protocol_value)
        statsTopBorder = findViewById(R.id.stats_top_border)
        statsDividerStart = findViewById(R.id.stats_divider_start)
        statsDividerEnd = findViewById(R.id.stats_divider_end)
        latencyIcon = findViewById(R.id.latency_icon)
        latencyHeading = findViewById(R.id.latency_heading)
        statusHeading = findViewById(R.id.status_heading)
        protocolIcon = findViewById(R.id.protocol_icon)
        protocolHeading = findViewById(R.id.protocol_heading)
        super.setOnClickListener(l)
    }

    private fun setStatus(text: CharSequence) {
        statusLabel.text = text
        TooltipCompat.setTooltipText(this, text)
    }

    fun changeState(state: BaseService.State) {
        val activity = context as MainActivity
        fun postWhenStarted(what: () -> Unit) = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                what()
            }
        }

        val isConnected = state == BaseService.State.Connected
        val dotColor = if (isConnected) R.color.dashboard_green else R.color.dashboard_red
        statusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, dotColor)
        )

        setStatus(
            context.getText(
                when (state) {
                    BaseService.State.Connected -> R.string.dashboard_connected_status
                    BaseService.State.Connecting -> R.string.dashboard_state_connecting
                    BaseService.State.Stopping -> R.string.dashboard_state_stopping
                    else -> R.string.dashboard_disconnected_status
                }
            )
        )

        applyTheme(isConnected)

        postWhenStarted {
            if (allowShow) performShow()
        }
    }

    fun updateSpeed(txRate: Long, rxRate: Long) {
        // Speed is rendered directly inside DashboardFragment.
    }

    fun updateLatency(ms: Int) {
        latencyValue.text = context.getString(R.string.dashboard_latency_ms, ms)
    }

    fun updateProtocol(name: String) {
        protocolValue.text = name.uppercase()
    }

    fun testConnection() {
        val activity = context as MainActivity
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing))
        runOnDefaultDispatcher {
            try {
                val elapsed = activity.urlTest()
                onMainDispatcher {
                    isEnabled = true
                    updateLatency(elapsed.toInt())
                    setStatus(app.getText(R.string.dashboard_connected_status))
                }
            } catch (e: Exception) {
                Logs.w(e.toString())
                onMainDispatcher {
                    isEnabled = true
                    changeState(DataStore.serviceState)
                    activity.snackbar(
                        app.getString(
                            R.string.connection_test_error,
                            e.readableMessage
                        )
                    ).show()
                }
            }
        }
    }

    private fun applyTheme(isConnected: Boolean) {
        val primary = ContextCompat.getColor(
            context,
            if (isConnected) R.color.dashboard_space_text_primary else R.color.dashboard_text_primary
        )
        val secondary = ContextCompat.getColor(
            context,
            if (isConnected) R.color.dashboard_space_text_secondary else R.color.dashboard_text_tertiary
        )
        val divider = ContextCompat.getColor(
            context,
            if (isConnected) R.color.dashboard_divider_dark else R.color.dashboard_divider_light
        )

        statusLabel.setTextColor(primary)
        latencyValue.setTextColor(primary)
        protocolValue.setTextColor(primary)

        latencyIcon.setTextColor(secondary)
        latencyHeading.setTextColor(secondary)
        statusHeading.setTextColor(secondary)
        protocolIcon.setTextColor(secondary)
        protocolHeading.setTextColor(secondary)

        statsTopBorder.setBackgroundColor(divider)
        statsDividerStart.setBackgroundColor(divider)
        statsDividerEnd.setBackgroundColor(divider)
    }
}
