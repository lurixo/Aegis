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
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import com.aegis.ime.ime.theme.ImeShapes
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

    const val MEDIUM3 = 350L
    const val LONG1 = 400L

    const val PRESS_IN = SHORT1
    const val PRESS_OUT = SHORT2
    const val STATE_CHANGE = SHORT4
    const val REVEAL = MEDIUM1

    /**
     * MD3 fade-through split (container-content swap): the outgoing content leaves on an accelerating
     * curve over [FADE_OUT], the incoming content arrives on a decelerating curve over [FADE_IN]. Used
     * for panel tab switches and the candidate strip's toolbar↔candidates role change — never per
     * keystroke (see [CandidateView]), so it never strobes the typing path.
     */
    const val FADE_OUT = SHORT2
    const val FADE_IN = SHORT3

    /** Keyboard mode change (9键↔26键↔数字↔符号): an incoming fade-through — a larger, rarer transition. */
    const val MODE_SWITCH = MEDIUM2

    /** False when the user has turned system animations off — callers then jump straight to the end state. */
    fun enabled(ctx: Context): Boolean =
        runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) != 0f

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

    /**
     * MD3 fade-through for a container whose CONTENT is swapped in place (panel tab switch, candidate strip
     * role change): fade [view] out to transparent (accelerate, [outDuration]), run [swap] at the trough so
     * the new content is applied while invisible, then fade the new content back in (decelerate, [inDuration]).
     * Alpha only — the container keeps its bounds, so the IME height never moves. When platform animators are
     * disabled or the view is detached the swap runs immediately at full opacity (reduced-motion → end state).
     * Re-entrant: a call landing mid-transition cancels the previous one and its (now-stale) pending swap.
     */
    fun fadeThrough(view: View, outDuration: Long = FADE_OUT, inDuration: Long = FADE_IN, swap: () -> Unit) {
        view.animate().cancel()
        if (!view.isAttachedToWindow || !enabled(view.context)) {
            swap()
            showImmediately(view)
            return
        }
        view.animate()
            .alpha(0f)
            .setDuration(outDuration)
            .setInterpolator(STANDARD_ACCEL)
            .setListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    view.animate().setListener(null)
                    if (cancelled) return // a newer fadeThrough took over; its swap wins
                    swap()
                    view.alpha = 0f
                    view.animate().alpha(1f).setDuration(inDuration).setInterpolator(EMPHASIZED_DECEL).start()
                }
            })
            .start()
    }

    /**
     * Cross-fade a colour from [from] to [to] over an MD3 state-change, feeding each interpolated ARGB to
     * [apply] (e.g. `TextView::setTextColor`). Used for the non-instant selected-state colour on the expanded
     * grid's reading column and the panel rails (MD3 state layers transition, they don't snap). Returns the
     * running [ValueAnimator] so the caller can cancel it before starting a newer one on the same target;
     * returns null (after applying [to] once) when there is nothing to animate or animators are disabled.
     */
    fun crossfadeColor(view: View, from: Int, to: Int, duration: Long = STATE_CHANGE, apply: (Int) -> Unit): ValueAnimator? {
        if (from == to) { apply(to); return null }
        if (!view.isAttachedToWindow || !enabled(view.context)) { apply(to); return null }
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
