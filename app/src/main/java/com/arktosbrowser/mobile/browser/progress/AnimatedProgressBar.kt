/*
 * Copyright 2016 Anthony Restaino
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.arktosbrowser.mobile.browser.progress

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import androidx.core.view.ViewCompat
import com.arktosbrowser.mobile.R

/**
 * Thin accent-colored progress line drawn under the toolbar. Kept as a
 * custom view (see [ProgressBarPolicy]); updates retarget a single
 * running [ValueAnimator] so the bar tracks page progress without the
 * old queue-the-whole-animation lag.
 */
class AnimatedProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progressValue = 0
    private var drawWidth = 0

    private var progressColor = 0
    private var bidirectionalAnimate = false
    private var animationDuration = ProgressBarPolicy.PROGRESS_DURATION_MS

    private val progressInterpolator: Interpolator = BezierEaseInterpolator()
    private val alphaInterpolator: Interpolator = LinearInterpolator()

    private val paint = Paint()
    private var widthAnimator: ValueAnimator? = null

    init {
        val array =
            context.theme.obtainStyledAttributes(attrs, R.styleable.AnimatedProgressBar, 0, 0)
        try {
            progressColor =
                array.getColor(R.styleable.AnimatedProgressBar_progressColor, Color.RED)
            bidirectionalAnimate =
                array.getBoolean(R.styleable.AnimatedProgressBar_bidirectionalAnimate, false)
            animationDuration = array.getInteger(
                R.styleable.AnimatedProgressBar_animationDuration,
                ProgressBarPolicy.PROGRESS_DURATION_MS.toInt()
            ).toLong()
        } finally {
            array.recycle()
        }
        paint.color = progressColor
    }

    var progress: Int
        /**
         * Returns the current progress value between 0 and 100.
         *
         * @return progress of the view
         */
        get() = progressValue
        /**
         * Sets the progress as an integer value between 0 and 100.
         * Values above or below that interval are clamped to it.
         *
         * @param value an integer between 0 and 100
         */
        set(value) {
            val newProgress = ProgressBarPolicy.clampProgress(value)

            // If the view is not laid out yet we have no width to
            // animate against — retry once layout has run.
            if (measuredWidth == 0 && !ViewCompat.isLaidOut(this)) {
                post { progress = newProgress }
                return
            }

            if (alpha < 1.0f) {
                fadeIn()
            }

            if (ProgressBarPolicy.snapsToStart(
                    newProgress,
                    progressValue,
                    bidirectionalAnimate
                )
            ) {
                drawWidth = 0
            }
            progressValue = newProgress

            val targetWidth = ProgressBarPolicy.targetWidthPx(newProgress, measuredWidth)
            if (targetWidth != drawWidth) {
                animateToWidth(targetWidth)
            } else if (ProgressBarPolicy.fadesOutAtEnd(newProgress)) {
                fadeOut()
            }
        }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, drawWidth.toFloat(), height.toFloat(), paint)
    }

    override fun onDetachedFromWindow() {
        widthAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    /**
     * Retargets the width animation to [targetWidth]. A running
     * animation is cancelled and restarted from the currently drawn
     * width rather than queued behind it.
     */
    private fun animateToWidth(targetWidth: Int) {
        widthAnimator?.cancel()
        widthAnimator = ValueAnimator.ofInt(drawWidth, targetWidth).apply {
            duration = animationDuration
            interpolator = progressInterpolator
            addUpdateListener {
                drawWidth = it.animatedValue as Int
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (ProgressBarPolicy.fadesOutAtEnd(progressValue)) {
                        fadeOut()
                    }
                }
            })
            start()
        }
    }

    private fun fadeIn() {
        animate().alpha(1f)
            .setDuration(ProgressBarPolicy.ALPHA_DURATION_MS)
            .setInterpolator(alphaInterpolator)
            .start()
    }

    private fun fadeOut() {
        animate().alpha(0f)
            .setDuration(ProgressBarPolicy.ALPHA_DURATION_MS)
            .setInterpolator(alphaInterpolator)
            .start()
    }
}
