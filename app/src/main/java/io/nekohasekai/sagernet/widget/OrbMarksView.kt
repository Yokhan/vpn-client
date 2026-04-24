package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class OrbMarksView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private companion object {
        private const val MARK_COUNT = 12
        private const val FULL_CIRCLE_DEGREES = 360
        private const val START_ANGLE_DEGREES = -90
        private const val RADIUS_FACTOR = 0.47f
        private const val CONNECTED_ALPHA = 115
        private const val DISCONNECTED_ALPHA = 102
        private const val LARGE_DOT_RADIUS_DP = 1.5f
        private const val SMALL_DOT_RADIUS_DP = 1.25f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var connected = false

    fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = min(width, height) * RADIUS_FACTOR
        val centerX = width / 2f
        val centerY = height / 2f
        paint.color = ContextCompat.getColor(
            context,
            if (connected) R.color.dashboard_green else R.color.dashboard_text_quaternary
        )
        paint.alpha = if (connected) CONNECTED_ALPHA else DISCONNECTED_ALPHA

        repeat(MARK_COUNT) { index ->
            val angle = Math.toRadians(
                (index * FULL_CIRCLE_DEGREES / MARK_COUNT + START_ANGLE_DEGREES).toDouble()
            )
            val dotRadius = if (index % 2 == 0) LARGE_DOT_RADIUS_DP else SMALL_DOT_RADIUS_DP
            canvas.drawCircle(
                centerX + cos(angle).toFloat() * radius,
                centerY + sin(angle).toFloat() * radius,
                dotRadius * resources.displayMetrics.density,
                paint
            )
        }
    }
}
