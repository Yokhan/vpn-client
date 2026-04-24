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

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var connected = false

    fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = min(width, height) * 0.47f
        val centerX = width / 2f
        val centerY = height / 2f
        paint.color = ContextCompat.getColor(
            context,
            if (connected) R.color.dashboard_green else R.color.dashboard_text_quaternary
        )
        paint.alpha = if (connected) 115 else 102

        repeat(12) { index ->
            val angle = Math.toRadians((index * 30 - 90).toDouble())
            val dotRadius = if (index % 2 == 0) 1.5f else 1.25f
            canvas.drawCircle(
                centerX + cos(angle).toFloat() * radius,
                centerY + sin(angle).toFloat() * radius,
                dotRadius * resources.displayMetrics.density,
                paint
            )
        }
    }
}
