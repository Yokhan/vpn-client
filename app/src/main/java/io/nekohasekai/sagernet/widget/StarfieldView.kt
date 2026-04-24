package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

class StarfieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private data class StarSpec(
        val left: Float,
        val widthDp: Float,
        val heightDp: Float,
        val opacity: Float,
        val durationMs: Long,
        val delayMs: Long,
    )

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private var running = false

    private val staticStars = floatArrayOf(
        3f, 8f, 0.5f, 13f, 24f, 0.3f, 19f, 68f, 0.4f, 27f, 15f, 0.45f,
        32f, 44f, 0.35f, 38f, 82f, 0.5f, 46f, 21f, 0.3f, 54f, 58f, 0.45f,
        61f, 11f, 0.35f, 69f, 36f, 0.55f, 76f, 72f, 0.35f, 84f, 17f, 0.5f,
        91f, 48f, 0.4f, 96f, 86f, 0.35f
    )

    private val stars = listOf(
        StarSpec(2f, 2f, 2f, 0.5f, 9000L, -3000L),
        StarSpec(7f, 1f, 1f, 0.4f, 14000L, -11000L),
        StarSpec(12f, 3f, 3f, 0.7f, 8000L, -1000L),
        StarSpec(17f, 1f, 1f, 0.3f, 16000L, -7000L),
        StarSpec(22f, 2f, 2f, 0.6f, 11000L, -9000L),
        StarSpec(28f, 1f, 1f, 0.5f, 13000L, -4000L),
        StarSpec(33f, 2f, 2f, 0.4f, 10000L, -6000L),
        StarSpec(39f, 1f, 1f, 0.6f, 15000L, -12000L),
        StarSpec(45f, 3f, 3f, 0.7f, 9500L, -2000L),
        StarSpec(51f, 1f, 1f, 0.35f, 12000L, -8000L),
        StarSpec(57f, 2f, 2f, 0.5f, 14500L, -10000L),
        StarSpec(63f, 1f, 1f, 0.4f, 10500L, -5000L),
        StarSpec(69f, 2f, 2f, 0.6f, 8500L, -7000L),
        StarSpec(75f, 1f, 1f, 0.3f, 16000L, -13000L),
        StarSpec(81f, 3f, 3f, 0.65f, 11500L, -3000L),
        StarSpec(87f, 1f, 1f, 0.45f, 13500L, -9000L),
        StarSpec(93f, 2f, 2f, 0.5f, 9000L, -6000L),
        StarSpec(11f, 1f, 12f, 0.4f, 2800L, -1200L),
        StarSpec(23f, 2f, 18f, 0.35f, 2200L, -500L),
        StarSpec(41f, 1f, 10f, 0.5f, 3000L, -2100L),
        StarSpec(55f, 1f, 15f, 0.3f, 2500L, -1800L),
        StarSpec(68f, 2f, 20f, 0.4f, 2000L, -300L),
        StarSpec(82f, 1f, 8f, 0.45f, 3200L, -2500L),
        StarSpec(47f, 1f, 14f, 0.35f, 2600L, -1400L),
        StarSpec(88f, 1f, 10f, 0.3f, 2400L, -800L),
    )

    fun setConnected(connected: Boolean) {
        if (running == connected) return
        running = connected
        if (connected) postInvalidateOnAnimation() else invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running || width == 0 || height == 0) return

        drawStaticStars(canvas)
        drawFlyingStars(canvas)
        postInvalidateOnAnimation()
    }

    private fun drawStaticStars(canvas: Canvas) {
        var index = 0
        while (index < staticStars.size) {
            paint.alpha = (255 * staticStars[index + 2]).toInt()
            canvas.drawCircle(
                width * staticStars[index] / 100f,
                height * staticStars[index + 1] / 100f,
                0.75f * density,
                paint
            )
            index += 3
        }
    }

    private fun drawFlyingStars(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        stars.forEach { star ->
            val phase = ((now - star.delayMs) % star.durationMs).toFloat() / star.durationMs
            val left = width * star.left / 100f
            val top = height * 1.2f * phase
            paint.alpha = (255 * star.opacity).toInt()
            canvas.drawRoundRect(
                left,
                top,
                left + star.widthDp * density,
                top + star.heightDp * density,
                star.widthDp * density,
                star.widthDp * density,
                paint
            )
        }
    }
}
