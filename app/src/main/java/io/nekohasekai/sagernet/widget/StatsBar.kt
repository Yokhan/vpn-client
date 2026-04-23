package io.nekohasekai.sagernet.widget

import android.content.Context
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
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {

    private lateinit var statusDot: TextView
    private lateinit var statusLabel: TextView
    private lateinit var statusHint: TextView
    private lateinit var latencyValue: TextView
    private lateinit var protocolValue: TextView
    private lateinit var behavior: YourBehavior

    var allowShow = true

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior { allowShow }
        return behavior
    }

    class YourBehavior(val getAllowShow: () -> Boolean) : Behavior() {

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout, child: BottomAppBar, target: View,
            dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
            type: Int, consumed: IntArray,
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
        statusHint = findViewById(R.id.status_hint)
        latencyValue = findViewById(R.id.latency_value)
        protocolValue = findViewById(R.id.protocol_value)
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

        // Status dot color
        val dotColor = if (isConnected) R.color.buffit_green else R.color.buffit_red
        statusDot.setTextColor(ContextCompat.getColor(context, dotColor))

        // Status text
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

        // Hint text
        statusHint.text = context.getText(
            if (isConnected) R.string.dashboard_tap_disconnect
            else R.string.dashboard_tap_connect
        )

        if (isConnected) {
            postWhenStarted {
                if (allowShow) performShow()
            }
        } else {
            postWhenStarted {
                if (allowShow) performShow()
            }
        }
    }

    fun updateSpeed(txRate: Long, rxRate: Long) {
        // Speed is now shown in the dashboard fragment, not here
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
                    setStatus(
                        app.getText(R.string.dashboard_connected_status)
                    )
                }
            } catch (e: Exception) {
                Logs.w(e.toString())
                onMainDispatcher {
                    isEnabled = true
                    setStatus(app.getText(R.string.connection_test_testing))
                    activity.snackbar(
                        app.getString(
                            R.string.connection_test_error, e.readableMessage
                        )
                    ).show()
                }
            }
        }
    }
}
