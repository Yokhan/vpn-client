package com.yokhan.gates.app.ui

import android.content.Context
import android.text.format.Formatter
import com.yokhan.gates.shared.domain.LatencyDisplay
import com.yokhan.gates.shared.domain.RateDisplay
import io.nekohasekai.sagernet.R

internal object DashboardTelemetryFormatter {
    fun speed(context: Context, rate: RateDisplay): String {
        return if (rate.isIdle) {
            context.getString(R.string.dashboard_speed_idle)
        } else {
            context.getString(
                R.string.speed,
                Formatter.formatFileSize(context, rate.bytesPerSecond),
            )
        }
    }

    fun latency(context: Context, latency: LatencyDisplay): String {
        return latency.milliseconds?.let {
            context.getString(R.string.dashboard_latency_ms, it)
        } ?: "--"
    }
}
