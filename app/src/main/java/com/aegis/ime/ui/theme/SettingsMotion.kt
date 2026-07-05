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

package com.aegis.ime.ui.theme

import android.content.Context
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.aegis.ime.ime.Motion

/**
 * The settings (Compose) side of the single Aegis motion vocabulary, in lock-step with the IME View layer's
 * [Motion]: DURATIONS are the very same tokens (single source in [Motion]) and EASINGS mirror the exact
 * emphasized control points as Compose [CubicBezierEasing]. So a duration/easing is never a magic number on
 * either side, and both sides share ONE outgoing curve ([EmphasizedAccelerate]) and ONE incoming curve
 * ([EmphasizedDecelerate]) — the honest emphasized family (a former `Emphasized`/`Standard` easing that
 * merely duplicated the standard curve was removed).
 *
 * The settings graph is a hierarchy (a home screen → group sub-pages), so navigation uses the MD3
 * shared-axis X pattern (a short slide + a scale "peek" + fade); enter slides/scales/fades in on the decelerate
 * curve, exit on the accelerate curve — mirrored on the back stack. These mirrored pop transitions
 * ([backEnter]/[backExit]) are also what the settings NavHost's built-in seekable predictive back seeks, so an
 * edge-swipe under gesture navigation makes the current sub-page shrink + follow the finger out while the
 * previous page peeks in (nav-compose 2.9.8 wires the PredictiveBackHandler + SeekableTransitionState
 * internally and seeks these curves off the gesture progress; the app supplies the curves). The scale is the
 * cue that makes the finger-follow read as predictive back on a full page — see [PEEK_SCALE_OUT].
 * Reveal/hide of in-page content (the first-run hint) uses expand/shrink + fade on the state-change token.
 */
internal object SettingsMotion {
    /** True when system animations are on — single source of truth is the IME-side [Motion.enabled] (reads
     *  ANIMATOR_DURATION_SCALE). Under reduced motion the settings navigation, including the NavHost's seekable
     *  predictive-back peek, collapses to an instant cut (直达): the caller swaps in [EnterTransition.None] /
     *  [ExitTransition.None], which the built-in predictive back then has nothing to seek. */
    fun animationsEnabled(context: Context): Boolean = Motion.enabled(context)

    // Durations = the IME-side MD3 tokens (Long ms → Int ms for Compose tween).
    val DURATION_NAV = Motion.MODE_SWITCH.toInt()      // 200 — page navigation (tightened with the IME tier)
    val DURATION_FADE_IN = Motion.FADE_IN.toInt()      // 150
    val DURATION_FADE_OUT = Motion.FADE_OUT.toInt()    // 100
    val DURATION_STATE = Motion.STATE_CHANGE.toInt()   // 200 — reveal / expand-collapse

    // MD3 emphasized easings — identical control points to Motion's incoming/outgoing PathInterpolators.
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f) // every enter (slide + fade in)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f) // every exit (slide + fade out)

    /** Shared-axis X slide: a full-screen page enters/leaves by 1/[SLIDE_FRACTION] of its width — a
     *  width-proportional cue. This is the settings surface's own spatial token, distinct from the IME's fixed
     *  [Motion.REVEAL_SHIFT_DP] because a fixed 8dp is invisible on a full page; both are named tokens. */
    private const val SLIDE_FRACTION = 8

    /** The MD3 predictive-back "peek" affordance: the OUTGOING page shrinks to [PEEK_SCALE_OUT] as it slides
     *  out, and the INCOMING page grows from [PEEK_SCALE_IN] as it reveals. Because nav-compose seeks these pop
     *  transitions off the back-gesture progress, the shrink is what makes the finger-follow read as predictive
     *  back to the naked eye — a bare 1/8 slide + fade alone was too faint to notice on a full page. Named
     *  tokens, not bare literals. Applied to forward navigation too, so the shared axis stays mirrored. */
    private const val PEEK_SCALE_OUT = 0.88f
    private const val PEEK_SCALE_IN = 0.92f

    /** Forward navigation (home → sub-page): incoming enters from the end, sliding + scaling + fading in (decelerate). */
    fun forwardEnter(scope: AnimatedContentTransitionScope<*>): EnterTransition =
        scope.run {
            slideInHorizontally(tween(DURATION_NAV, easing = EmphasizedDecelerate)) { it / SLIDE_FRACTION } +
                scaleIn(tween(DURATION_NAV, easing = EmphasizedDecelerate), initialScale = PEEK_SCALE_IN) +
                fadeIn(tween(DURATION_FADE_IN, easing = EmphasizedDecelerate))
        }

    /** Forward navigation: the outgoing page leaves toward the start, sliding + scaling + fading out (accelerate). */
    fun forwardExit(scope: AnimatedContentTransitionScope<*>): ExitTransition =
        scope.run {
            slideOutHorizontally(tween(DURATION_NAV, easing = EmphasizedAccelerate)) { -it / SLIDE_FRACTION } +
                scaleOut(tween(DURATION_NAV, easing = EmphasizedAccelerate), targetScale = PEEK_SCALE_OUT) +
                fadeOut(tween(DURATION_FADE_OUT, easing = EmphasizedAccelerate))
        }

    /** Back / predictive-back (sub-page → home): the previous page reveals from the start, growing from
     *  [PEEK_SCALE_IN] + sliding + fading in (mirror of forward). This is what the seekable predictive back
     *  seeks for the "上一页浮现" cue. */
    fun backEnter(scope: AnimatedContentTransitionScope<*>): EnterTransition =
        scope.run {
            slideInHorizontally(tween(DURATION_NAV, easing = EmphasizedDecelerate)) { -it / SLIDE_FRACTION } +
                scaleIn(tween(DURATION_NAV, easing = EmphasizedDecelerate), initialScale = PEEK_SCALE_IN) +
                fadeIn(tween(DURATION_FADE_IN, easing = EmphasizedDecelerate))
        }

    /** Back / predictive-back: the current sub-page follows the finger out toward the end — shrinking to
     *  [PEEK_SCALE_OUT] + sliding + fading (accelerate). The shrink is the visible "跟手滑出/缩放" cue. */
    fun backExit(scope: AnimatedContentTransitionScope<*>): ExitTransition =
        scope.run {
            slideOutHorizontally(tween(DURATION_NAV, easing = EmphasizedAccelerate)) { it / SLIDE_FRACTION } +
                scaleOut(tween(DURATION_NAV, easing = EmphasizedAccelerate), targetScale = PEEK_SCALE_OUT) +
                fadeOut(tween(DURATION_FADE_OUT, easing = EmphasizedAccelerate))
        }

    /** Reveal for in-page content (the first-run hint): expand + fade in on the state-change token. */
    fun revealEnter(): EnterTransition =
        expandVertically(tween(DURATION_STATE, easing = EmphasizedDecelerate)) +
            fadeIn(tween(DURATION_STATE, easing = EmphasizedDecelerate))

    /** Collapse for in-page content: shrink + fade out on the state-change token. */
    fun collapseExit(): ExitTransition =
        shrinkVertically(tween(DURATION_STATE, easing = EmphasizedAccelerate)) +
            fadeOut(tween(DURATION_FADE_OUT, easing = EmphasizedAccelerate))
}
