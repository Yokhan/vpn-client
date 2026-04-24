package io.nekohasekai.sagernet.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import io.nekohasekai.sagernet.databinding.LayoutDashboardBinding

internal class DashboardOrbMotionController(
    private val binding: LayoutDashboardBinding,
) {

    private var ringAnimator: ObjectAnimator? = null
    private var flameLeftAnimator: ObjectAnimator? = null
    private var flameRightAnimator: ObjectAnimator? = null
    private var isReleased = false

    fun initializeDisconnected() {
        binding.connectCircle.post {
            if (isReleased) return@post
            binding.rocketGroup.translationY = hiddenRocketTranslation()
        }
    }

    fun update(isConnected: Boolean) {
        if (isReleased) return
        if (binding.connectCircle.height == 0) {
            binding.connectCircle.post { update(isConnected) }
            return
        }

        if (isConnected) {
            showRocket()
        } else {
            hideRocket()
        }
    }

    fun release() {
        isReleased = true
        stopLoopingAnimations()
    }

    private fun stopLoopingAnimations() {
        stopRingAnimation()
        stopFlameAnimations()

        binding.connectRing.scaleX = 1f
        binding.connectRing.scaleY = 1f
        binding.connectRing.alpha = 1f
        binding.rocketFlameLeft.scaleX = 1f
        binding.rocketFlameLeft.scaleY = 1f
        binding.rocketFlameRight.scaleX = 1f
        binding.rocketFlameRight.scaleY = 1f
    }

    private fun stopRingAnimation() {
        ringAnimator?.cancel()
        ringAnimator = null
    }

    private fun stopFlameAnimations() {
        flameLeftAnimator?.cancel()
        flameLeftAnimator = null
        flameRightAnimator?.cancel()
        flameRightAnimator = null
    }

    private fun showRocket() {
        startLoopingAnimations()
        binding.rocketGroup.animate().cancel()
        if (binding.rocketGroup.alpha == 0f) {
            binding.rocketGroup.translationY = hiddenRocketTranslation()
        }
        binding.rocketGroup.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(700L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        binding.rocketFlameLeft.animate().alpha(1f).setDuration(240L).start()
        binding.rocketFlameRight.animate().alpha(1f).setDuration(240L).start()
    }

    private fun hideRocket() {
        stopRingAnimation()
        binding.rocketGroup.animate().cancel()
        binding.rocketGroup.animate()
            .alpha(0f)
            .translationY(-hiddenRocketTranslation())
            .setDuration(800L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                stopFlameAnimations()
                if (isReleased) return@withEndAction
                binding.rocketGroup.translationY = hiddenRocketTranslation()
                binding.rocketFlameLeft.alpha = 0f
                binding.rocketFlameRight.alpha = 0f
            }
            .start()
    }

    private fun startLoopingAnimations() {
        if (ringAnimator == null) {
            ringAnimator = ObjectAnimator.ofPropertyValuesHolder(
                binding.connectRing,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.04f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.04f, 1f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.8f, 1f, 0.8f),
            ).apply {
                duration = 1600L
                interpolator = AccelerateDecelerateInterpolator()
                repeatCount = ObjectAnimator.INFINITE
            }
        }
        if (flameLeftAnimator == null) {
            flameLeftAnimator = flameAnimator(binding.rocketFlameLeft)
        }
        if (flameRightAnimator == null) {
            flameRightAnimator = flameAnimator(binding.rocketFlameRight)
        }
        if (ringAnimator?.isStarted != true) ringAnimator?.start()
        if (flameLeftAnimator?.isStarted != true) flameLeftAnimator?.start()
        if (flameRightAnimator?.isStarted != true) flameRightAnimator?.start()
    }

    private fun flameAnimator(view: View): ObjectAnimator {
        return ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.15f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f, 1f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.82f, 1f, 0.82f),
        ).apply {
            duration = 420L
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ObjectAnimator.INFINITE
        }
    }

    private fun hiddenRocketTranslation(): Float {
        val rootHeight = binding.dashboardRoot.height
        return if (rootHeight > 0) rootHeight * 0.7f else binding.connectCircle.height * 0.45f
    }
}
