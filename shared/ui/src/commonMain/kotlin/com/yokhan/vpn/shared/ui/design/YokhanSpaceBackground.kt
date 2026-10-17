package com.yokhan.gates.shared.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlin.coroutines.coroutineContext

@Composable
fun YokhanStarfield(modifier: Modifier = Modifier, active: Boolean = true) {
    var elapsedSeconds by remember { mutableStateOf(0.0) }
    val stars = remember { staticStars to movingStars }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val durationScale = coroutineContext[MotionDurationScale]
        snapshotFlow { durationScale?.scaleFactor ?: 1f }.collectLatest { scale ->
            if (scale <= 0f) return@collectLatest
            var previousFrame: Long? = null
            while (true) {
                val frame = withFrameNanos { it }
                previousFrame?.let { elapsedSeconds += starfieldElapsedDeltaSeconds(frame - it, scale) }
                previousFrame = frame
            }
        }
    }
    Box(modifier.graphicsLayer { alpha = if (active) 1f else 0f }) {
        if (active) Canvas(Modifier.fillMaxSize()) {
            stars.first.forEach { star ->
                drawCircle(
                    color = Color.White.copy(alpha = star.alpha),
                    radius = star.radius.dp.toPx(),
                    center = Offset(size.width * star.x, size.height * star.y),
                )
            }
            // Only drawing observes the clock. Each star wraps at its own period.
            val elapsed = elapsedSeconds
            stars.second.forEach { star ->
                val progress = starProgress(elapsed, star.durationSeconds, star.initialOffsetSeconds)
                val y = size.height * progress * 1.2f
                if (star.streak) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = star.alpha),
                        topLeft = Offset(size.width * star.x, y),
                        size = Size(star.width.dp.toPx(), star.height.dp.toPx()),
                        cornerRadius = CornerRadius(1.dp.toPx()),
                    )
                } else {
                    drawCircle(
                        color = Color.White.copy(alpha = star.alpha),
                        radius = (star.width / 2f).dp.toPx(),
                        center = Offset(size.width * star.x, y),
                    )
                }
            }
        }
    }
}

internal fun starfieldElapsedDeltaSeconds(frameDeltaNanos: Long, scaleFactor: Float): Double =
    if (scaleFactor > 0f) frameDeltaNanos / 1_000_000_000.0 / scaleFactor else 0.0

@Immutable
private data class StaticStar(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float,
)

@Immutable
private data class MovingStar(
    val x: Float,
    val width: Float,
    val height: Float,
    val alpha: Float,
    val durationSeconds: Float,
    val initialOffsetSeconds: Float,
    val streak: Boolean,
)

private val staticStars = listOf(
    StaticStar(0.03f, 0.08f, 1f, 0.5f),
    StaticStar(0.07f, 0.42f, 1f, 0.4f),
    StaticStar(0.12f, 0.18f, 1.5f, 0.7f),
    StaticStar(0.15f, 0.65f, 1f, 0.3f),
    StaticStar(0.18f, 0.88f, 1f, 0.5f),
    StaticStar(0.22f, 0.05f, 1f, 0.4f),
    StaticStar(0.25f, 0.35f, 1.5f, 0.6f),
    StaticStar(0.28f, 0.72f, 1f, 0.3f),
    StaticStar(0.33f, 0.15f, 1f, 0.5f),
    StaticStar(0.36f, 0.55f, 1f, 0.4f),
    StaticStar(0.40f, 0.82f, 1.5f, 0.6f),
    StaticStar(0.44f, 0.28f, 1f, 0.3f),
    StaticStar(0.48f, 0.48f, 1f, 0.5f),
    StaticStar(0.52f, 0.12f, 1f, 0.4f),
    StaticStar(0.55f, 0.62f, 1.5f, 0.7f),
    StaticStar(0.58f, 0.90f, 1f, 0.3f),
    StaticStar(0.62f, 0.22f, 1f, 0.5f),
    StaticStar(0.65f, 0.50f, 1f, 0.4f),
    StaticStar(0.68f, 0.78f, 1.5f, 0.6f),
    StaticStar(0.72f, 0.06f, 1f, 0.3f),
    StaticStar(0.75f, 0.38f, 1f, 0.5f),
    StaticStar(0.78f, 0.68f, 1f, 0.4f),
    StaticStar(0.82f, 0.92f, 1.5f, 0.7f),
    StaticStar(0.85f, 0.25f, 1f, 0.3f),
    StaticStar(0.88f, 0.52f, 1f, 0.5f),
    StaticStar(0.91f, 0.75f, 1f, 0.4f),
    StaticStar(0.94f, 0.10f, 1.5f, 0.6f),
    StaticStar(0.97f, 0.45f, 1f, 0.3f),
    StaticStar(0.05f, 0.95f, 1f, 0.5f),
    StaticStar(0.45f, 0.03f, 1f, 0.4f),
    StaticStar(0.60f, 0.40f, 1f, 0.3f),
    StaticStar(0.35f, 0.95f, 1.5f, 0.6f),
    StaticStar(0.80f, 0.15f, 1f, 0.5f),
    StaticStar(0.50f, 0.75f, 1f, 0.4f),
    StaticStar(0.10f, 0.30f, 1f, 0.3f),
)

private val movingStars = listOf(
    MovingStar(0.02f, 2f, 2f, 0.5f, 9f, 3f, false),
    MovingStar(0.07f, 1f, 1f, 0.4f, 14f, 11f, false),
    MovingStar(0.12f, 3f, 3f, 0.7f, 8f, 1f, false),
    MovingStar(0.17f, 1f, 1f, 0.3f, 16f, 7f, false),
    MovingStar(0.22f, 2f, 2f, 0.6f, 11f, 9f, false),
    MovingStar(0.28f, 1f, 1f, 0.5f, 13f, 4f, false),
    MovingStar(0.33f, 2f, 2f, 0.4f, 10f, 6f, false),
    MovingStar(0.39f, 1f, 1f, 0.6f, 15f, 12f, false),
    MovingStar(0.45f, 3f, 3f, 0.7f, 9.5f, 2f, false),
    MovingStar(0.51f, 1f, 1f, 0.35f, 12f, 8f, false),
    MovingStar(0.57f, 2f, 2f, 0.5f, 14.5f, 10f, false),
    MovingStar(0.63f, 1f, 1f, 0.4f, 10.5f, 5f, false),
    MovingStar(0.69f, 2f, 2f, 0.6f, 8.5f, 7f, false),
    MovingStar(0.75f, 1f, 1f, 0.3f, 16f, 13f, false),
    MovingStar(0.81f, 3f, 3f, 0.65f, 11.5f, 3f, false),
    MovingStar(0.87f, 1f, 1f, 0.45f, 13.5f, 9f, false),
    MovingStar(0.93f, 2f, 2f, 0.5f, 9f, 6f, false),
    MovingStar(0.11f, 1f, 12f, 0.4f, 2.8f, 1.2f, true),
    MovingStar(0.23f, 2f, 18f, 0.35f, 2.2f, 0.5f, true),
    MovingStar(0.41f, 1f, 10f, 0.5f, 3f, 2.1f, true),
    MovingStar(0.55f, 1f, 15f, 0.3f, 2.5f, 1.8f, true),
    MovingStar(0.68f, 2f, 20f, 0.4f, 2f, 0.3f, true),
    MovingStar(0.82f, 1f, 8f, 0.45f, 3.2f, 2.5f, true),
    MovingStar(0.47f, 1f, 14f, 0.35f, 2.6f, 1.4f, true),
    MovingStar(0.88f, 1f, 10f, 0.3f, 2.4f, 0.8f, true),
)
