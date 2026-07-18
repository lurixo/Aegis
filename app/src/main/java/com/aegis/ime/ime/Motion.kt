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
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import com.aegis.ime.ime.theme.ImeShapes
import kotlin.math.roundToInt

object Motion {
    val STANDARD: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    val STANDARD_DECEL: Interpolator = PathInterpolator(0f, 0f, 0f, 1f)
    val EMPHASIZED_DECEL: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    val EMPHASIZED_ACCEL: Interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    const val SHORT1 = 50L
    const val SHORT2 = 100L
    const val SHORT3 = 150L
    const val SHORT4 = 200L

    const val PRESS_IN = SHORT1
    const val PRESS_OUT = SHORT2
    const val STATE_CHANGE = SHORT4
    const val REVEAL = SHORT4

    const val FADE_OUT = SHORT2
    const val FADE_IN = SHORT3

    const val MODE_SWITCH = SHORT4

    const val REVEAL_SHIFT_DP = 8f

    fun enabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    fun withAlpha(argb: Int, alpha: Int): Int = (argb and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    fun stateLayerColor(argb: Int, level: Float, maxAlpha: Int = 0x22): Int =
        withAlpha(argb, (maxAlpha * level.coerceIn(0f, 1f)).roundToInt())

    fun applyTapFeedback(view: View, color: Int, alpha: Int = 0x24, radiusDp: Float = ImeShapes.keyRadiusDp) {
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = radiusDp * view.resources.displayMetrics.density
        }
        view.foreground = RippleDrawable(
            ColorStateList.valueOf(withAlpha(color, alpha)),
            null,
            mask,
        )
    }

    fun fadeIn(view: View, duration: Long = FADE_IN) {
        view.animate().cancel()
        if (!view.isAttachedToWindow || !enabled()) {
            showImmediately(view)
            return
        }
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(duration).setInterpolator(EMPHASIZED_DECEL).start()
    }

    enum class EnterFrom { NONE, START, END, TOP, BOTTOM }

    fun revealIn(view: View, from: EnterFrom = EnterFrom.NONE, distanceDp: Float = REVEAL_SHIFT_DP, duration: Long = REVEAL) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
        val distance = distanceDp * view.resources.displayMetrics.density
        if (!view.isAttachedToWindow || !enabled()) {
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

    fun hide(
        view: View,
        endVisibility: Int = View.GONE,
        toward: EnterFrom = EnterFrom.NONE,
        distanceDp: Float = REVEAL_SHIFT_DP,
        duration: Long = FADE_OUT,
        endAction: (() -> Unit)? = null,
    ) {
        view.animate().cancel()
        if (!view.isAttachedToWindow || !enabled()) {
            view.visibility = endVisibility
            reset(view)
            endAction?.invoke()
            return
        }
        val distance = distanceDp * view.resources.displayMetrics.density
        view.animate()
            .alpha(0f)
            .translationX(
                when (toward) {
                    EnterFrom.START -> -distance
                    EnterFrom.END -> distance
                    else -> 0f
                },
            )
            .translationY(
                when (toward) {
                    EnterFrom.TOP -> -distance
                    EnterFrom.BOTTOM -> distance
                    else -> 0f
                },
            )
            .setDuration(duration)
            .setInterpolator(EMPHASIZED_ACCEL)
            .setListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    view.animate().setListener(null)
                    if (cancelled) return
                    view.visibility = endVisibility
                    reset(view)
                    endAction?.invoke()
                }
            })
            .start()
    }

    fun swapIn(incoming: View, outgoing: View?, outDuration: Long = FADE_OUT, inDuration: Long = FADE_IN) {
        incoming.animate().cancel()
        outgoing?.animate()?.cancel()
        if (outgoing == null || !outgoing.isAttachedToWindow || !enabled()) {
            outgoing?.let { it.visibility = View.GONE; reset(it) }
            showImmediately(incoming)
            return
        }
        outgoing.animate()
            .alpha(0f)
            .setDuration(outDuration)
            .setInterpolator(EMPHASIZED_ACCEL)
            .setListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    outgoing.animate().setListener(null)
                    if (cancelled) return
                    outgoing.visibility = View.GONE
                    reset(outgoing)
                    incoming.visibility = View.VISIBLE
                    incoming.alpha = 0f
                    incoming.translationX = 0f
                    incoming.translationY = 0f
                    incoming.animate().alpha(1f).setDuration(inDuration).setInterpolator(EMPHASIZED_DECEL).start()
                }
            })
            .start()
    }

    fun fadeThrough(view: View, outDuration: Long = FADE_OUT, inDuration: Long = FADE_IN, swap: () -> Unit) {
        view.animate().cancel()
        if (!view.isAttachedToWindow || !enabled()) {
            swap()
            showImmediately(view)
            return
        }
        view.animate()
            .alpha(0f)
            .setDuration(outDuration)
            .setInterpolator(EMPHASIZED_ACCEL)
            .setListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    view.animate().setListener(null)
                    if (cancelled) return
                    swap()
                    view.alpha = 0f
                    view.animate().alpha(1f).setDuration(inDuration).setInterpolator(EMPHASIZED_DECEL).start()
                }
            })
            .start()
    }

    fun crossfadeColor(view: View, from: Int, to: Int, duration: Long = STATE_CHANGE, apply: (Int) -> Unit): ValueAnimator? {
        if (from == to) { apply(to); return null }
        if (!view.isAttachedToWindow || !enabled()) { apply(to); return null }
        return ValueAnimator.ofArgb(from, to).apply {
            this.duration = duration
            interpolator = STANDARD
            addUpdateListener { apply(it.animatedValue as Int) }
            start()
        }
    }

    private fun showImmediately(view: View) {
        view.visibility = View.VISIBLE
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.invalidate()
    }

    fun reset(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.translationZ = 0f
    }

    class ContentSwap(private val view: View, private val invalidate: () -> Unit = { view.invalidate() }) {
        var active = false
            private set
        private var fraction = 1f
        private var animator: ValueAnimator? = null

        val outAlpha: Float
            get() {
                if (!active) return 0f
                return 1f - EMPHASIZED_ACCEL.getInterpolation((fraction * SHORT3 / SHORT2).coerceAtMost(1f))
            }

        val inAlpha: Float
            get() = if (active) EMPHASIZED_DECEL.getInterpolation(fraction) else 1f

        fun start() {
            animator?.cancel()
            animator = null
            if (!view.isAttachedToWindow || !enabled()) {
                applyEndState()
                return
            }
            active = true
            fraction = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = SHORT3
                interpolator = null
                addUpdateListener {
                    fraction = it.animatedValue as Float
                    view.postInvalidateOnAnimation()
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (cancelled) return
                        animator = null
                        applyEndState()
                    }
                })
                start()
            }
        }

        fun cancel() {
            animator?.cancel()
            animator = null
            applyEndState()
        }

        private fun applyEndState() {
            active = false
            fraction = 1f
            invalidate()
        }
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
            if (!view.isAttachedToWindow || !enabled()) {
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
