// SPDX-License-Identifier: GPL-3.0-only
//
// Copyright (C) 2026 lurixo
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, version 3.
//
// This program is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
// PARTICULAR PURPOSE. See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// this program. If not, see <https://www.gnu.org/licenses/>.

package com.aegis.ime.ime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import kotlin.math.roundToInt

/**
 * Small Material 3 motion vocabulary for the IME's View layer. Helpers here avoid layout-affecting properties
 * on the typing path and complete immediately when platform animators are disabled.
 */
object Motion {
    /** MD3 easing tokens. */
    val STANDARD: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    val STANDARD_ACCEL: Interpolator = PathInterpolator(0.3f, 0f, 1f, 1f)
    val STANDARD_DECEL: Interpolator = PathInterpolator(0f, 0f, 0f, 1f)
    val EMPHASIZED: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    val EMPHASIZED_ACCEL: Interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    val EMPHASIZED_DECEL: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    /** MD3 duration tokens (ms). */
    const val SHORT1 = 50L
    const val SHORT2 = 100L
    const val SHORT3 = 150L
    const val SHORT4 = 200L
    const val MEDIUM1 = 250L
    const val MEDIUM2 = 300L

    const val PRESS_IN = SHORT1
    const val PRESS_OUT = SHORT2
    const val STATE_CHANGE = SHORT4
    const val REVEAL = MEDIUM1

    /** False when the user has turned system animations off — callers then jump straight to the end state. */
    fun enabled(ctx: Context): Boolean =
        runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) != 0f

    fun withAlpha(argb: Int, alpha: Int): Int = (argb and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    fun stateLayerColor(argb: Int, level: Float, maxAlpha: Int = 0x22): Int =
        withAlpha(argb, (maxAlpha * level.coerceIn(0f, 1f)).roundToInt())

    fun applyTapFeedback(view: View, color: Int, alpha: Int = 0x24) {
        view.foreground = RippleDrawable(
            ColorStateList.valueOf(withAlpha(color, alpha)),
            ColorDrawable(Color.TRANSPARENT),
            null,
        )
    }

    /** Fade [view] in from transparent. Alpha only, so IME height and hit regions remain unchanged. */
    fun fadeIn(view: View, duration: Long = SHORT4) {
        view.animate().cancel()
        if (!view.isAttachedToWindow || !enabled(view.context)) {
            showImmediately(view)
            return
        }
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(duration).setInterpolator(EMPHASIZED_DECEL).start()
    }

    enum class EnterFrom { NONE, START, END, TOP, BOTTOM }

    fun revealIn(view: View, from: EnterFrom = EnterFrom.NONE, distanceDp: Float = 8f, duration: Long = REVEAL) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
        val distance = distanceDp * view.resources.displayMetrics.density
        if (!view.isAttachedToWindow || !enabled(view.context)) {
            showImmediately(view)
            return
        }
        view.alpha = 0f
        view.translationX = when (from) {
            EnterFrom.START -> -distance
            EnterFrom.END -> distance
            else -> 0f
        }
        view.translationY = when (from) {
            EnterFrom.TOP -> -distance
            EnterFrom.BOTTOM -> distance
            else -> 0f
        }
        view.animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(duration)
            .setInterpolator(EMPHASIZED_DECEL)
            .start()
    }

    fun hide(view: View, endVisibility: Int = View.GONE, duration: Long = SHORT3) {
        view.animate().cancel()
        if (!view.isAttachedToWindow || !enabled(view.context)) {
            view.visibility = endVisibility
            reset(view)
            return
        }
        view.animate()
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(STANDARD_ACCEL)
            .setListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) {
                        view.visibility = endVisibility
                        reset(view)
                    }
                    view.animate().setListener(null)
                }
            })
            .start()
    }

    fun swapIn(incoming: View, outgoing: View?) {
        outgoing?.let {
            it.visibility = View.GONE
            reset(it)
        }
        revealIn(incoming)
    }

    private fun showImmediately(view: View) {
        view.visibility = View.VISIBLE
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.invalidate()
    }

    /** Cancel any running animation and reset [view] to its resting state. */
    fun reset(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    class PressFeedback(private val view: View, private val invalidate: () -> Unit = { view.invalidate() }) {
        var level: Float = 0f
            private set
        private var animator: ValueAnimator? = null

        fun press() = animateTo(1f, PRESS_IN, STANDARD_DECEL)

        fun release() = animateTo(0f, PRESS_OUT, STANDARD)

        fun cancel() = release()

        fun reset() {
            animator?.cancel()
            animator = null
            level = 0f
            invalidate()
        }

        private fun animateTo(target: Float, duration: Long, interpolator: Interpolator) {
            animator?.cancel()
            if (!view.isAttachedToWindow || !enabled(view.context)) {
                level = target
                invalidate()
                return
            }
            animator = ValueAnimator.ofFloat(level, target).apply {
                this.duration = duration
                this.interpolator = interpolator
                addUpdateListener {
                    level = it.animatedValue as Float
                    view.postInvalidateOnAnimation()
                }
                start()
            }
        }
    }
}
