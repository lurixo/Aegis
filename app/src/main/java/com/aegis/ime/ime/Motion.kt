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
 * The single Material-3 motion vocabulary for the IME's View layer (mirrored on the settings side by
 * [com.aegis.ime.ui.theme.SettingsMotion]). See docs/MOTION_SPEC — Aegis motion is deliberately
 * RESTRAINED: alpha-dominant, short, tiny spatial nudges only; never overshoot/bounce/spin/rotate and
 * never a large translate/scale (those are the "晃眼/头晕" the design forbids). Every incoming transition
 * has a symmetric outgoing partner ([revealIn]↔[hide], [swapIn], [fadeThrough]); high-frequency actions
 * (keystroke/candidate update/preedit) get no layout/position animation, only the sub-100ms press layer.
 * Helpers avoid layout-affecting properties on the typing path and jump straight to the end state when
 * platform animators are disabled (system reduced-motion) or the view is detached.
 */
object Motion {
    // MD3 easings — every member is USED and HONEST (an "emphasized" curve whose points equalled STANDARD,
    // and its unused accelerate twin, were removed; the emphasized family is the decel(in)/accel(out) pair).
    /** Symmetric utility curve: colour cross-fades, the press-release state layer. */
    val STANDARD: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    /** Press-in state layer (a soft landing). */
    val STANDARD_DECEL: Interpolator = PathInterpolator(0f, 0f, 0f, 1f)
    /** Every INCOMING transition (reveal / fade-in / fade-through-in): MD3 emphasized-decelerate — a gentle
     *  settle with no overshoot. */
    val EMPHASIZED_DECEL: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    /** Every OUTGOING transition (hide / fade-through-out): MD3 emphasized-accelerate — a quick, unobtrusive
     *  exit. Single outgoing curve on both the IME and settings sides. */
    val EMPHASIZED_ACCEL: Interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    // Duration ladder (ms) — Aegis's restrained subset of the MD3 scale. MD3's medium/long tiers (250-500)
    // exist for large expressive transitions this IME never performs, so the ladder tops out at SHORT4.
    const val SHORT1 = 50L
    const val SHORT2 = 100L
    const val SHORT3 = 150L
    const val SHORT4 = 200L

    // Semantic tokens — every animation references one of these, never a bare ms literal.
    /** Press-down state layer (fast in). */
    const val PRESS_IN = SHORT1
    /** Press-up state layer (slower out — MD3 state layers appear fast, fade slow). */
    const val PRESS_OUT = SHORT2
    /** Colour / expand state change. */
    const val STATE_CHANGE = SHORT4
    /** A surface revealing/dismissing (panel, edit-bar, overlay, action row): slide + fade. Same tier as a
     *  state change — one "standard transition" duration across the whole IME. */
    const val REVEAL = SHORT4

    /**
     * MD3 fade-through split (container-content swap): the outgoing content leaves on an accelerating
     * curve over [FADE_OUT], the incoming content arrives on a decelerating curve over [FADE_IN]. Used
     * for panel tab switches and the candidate strip's toolbar↔candidates role change — never per
     * keystroke (see [CandidateView]), so it never strobes the typing path.
     */
    const val FADE_OUT = SHORT2
    const val FADE_IN = SHORT3

    /** Keyboard mode change (9键↔26键↔数字↔符号): an alpha-only fade of the whole surface. Tightened to the
     *  standard-transition tier (was 300ms — the single most attention-grabbing motion; §3② anti-dizziness). */
    const val MODE_SWITCH = SHORT4

    /** The ONE spatial nudge for every IME [revealIn]/[hide] slide (dp). A small, consistent shift regardless
     *  of surface size — no scattered 6/8/10dp magic numbers. Full-screen settings pages use their own
     *  width-proportional cue (see SettingsMotion), a distinct surface class. */
    const val REVEAL_SHIFT_DP = 8f

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

    /** Fade [view] in from transparent (the "appear" fade — candidate role change, preedit tab, keyboard
     *  mode switch, keyboard return from a panel). Alpha only, so IME height and hit regions never change. */
    fun fadeIn(view: View, duration: Long = FADE_IN) {
        view.animate().cancel()
        if (!view.isAttachedToWindow || !enabled(view.context)) {
            showImmediately(view)
            return
        }
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(duration).setInterpolator(EMPHASIZED_DECEL).start()
    }

    enum class EnterFrom { NONE, START, END, TOP, BOTTOM }

    /** Reveal [view] with a small directional slide + fade. [from] names the edge it enters from; the slide
     *  distance is always [REVEAL_SHIFT_DP] and the duration [REVEAL] — callers pass only the direction so
     *  every reveal across the IME is one tier. Its symmetric partner is [hide] with the matching `toward`. */
    fun revealIn(view: View, from: EnterFrom = EnterFrom.NONE, distanceDp: Float = REVEAL_SHIFT_DP, duration: Long = REVEAL) {
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

    /**
     * The symmetric partner of [revealIn]/[fadeIn]: fade [view] out (and, when [toward] names an edge, slide
     * it that way by [distanceDp] — the mirror of the reveal slide) on the outgoing curve, then set
     * [endVisibility] and run [endAction] once. Under reduced motion / when detached it jumps straight to the
     * end state (and still runs [endAction]) so callers close synchronously. Re-entrant: a newer call cancels
     * this one without stealing its end state.
     */
    fun hide(
        view: View,
        endVisibility: Int = View.GONE,
        toward: EnterFrom = EnterFrom.NONE,
        distanceDp: Float = REVEAL_SHIFT_DP,
        duration: Long = FADE_OUT,
        endAction: (() -> Unit)? = null,
    ) {
        view.animate().cancel()
        if (!view.isAttachedToWindow || !enabled(view.context)) {
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
                    if (cancelled) return // a newer hide/reveal took over; it owns the end state
                    view.visibility = endVisibility
                    reset(view)
                    endAction?.invoke()
                }
            })
            .start()
    }

    /**
     * Symmetric same-slot swap of two sibling views that must never both take height at once (the strip's
     * candidate↔copy-bar role, held at a fixed height): fade [outgoing] out (accelerate, [outDuration]), then
     * at the trough set it GONE and fade [incoming] in (decelerate, [inDuration]) — a fade-through across two
     * views. Under reduced motion / detached it swaps immediately (outgoing GONE, incoming shown at full
     * opacity) so callers can read the end state synchronously. Alpha only ⇒ the IME height never moves.
     */
    fun swapIn(incoming: View, outgoing: View?, outDuration: Long = FADE_OUT, inDuration: Long = FADE_IN) {
        incoming.animate().cancel()
        outgoing?.animate()?.cancel()
        if (outgoing == null || !outgoing.isAttachedToWindow || !enabled(outgoing.context)) {
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
                    if (cancelled) return // a newer swap took over
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
            .setInterpolator(EMPHASIZED_ACCEL)
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

    /** Cancel any running animation and reset [view] to its resting state (incl. translationZ, so a lifted
     *  drag elevation never bleeds into a recycled row). */
    fun reset(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.translationZ = 0f
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
