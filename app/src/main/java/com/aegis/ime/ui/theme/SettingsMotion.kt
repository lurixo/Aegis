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

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.aegis.ime.ime.Motion

/**
 * MD3 motion tokens for the settings (Compose) side, kept in lock-step with the IME View layer's [Motion]
 * vocabulary: the DURATIONS are the very same tokens (single source in [Motion]) and the EASINGS mirror the
 * exact PathInterpolator control points as Compose [CubicBezierEasing]. So a duration/easing is never a
 * magic number on either side — both move on the same MD3 curves.
 *
 * The settings graph is a hierarchy (a home screen → group sub-pages), so navigation uses the MD3
 * shared-axis X pattern (a short slide + fade-through); the forward/back direction is mirrored. Reveal/hide
 * of in-page content (the first-run hint) uses expand/shrink + fade on the state-change token.
 */
internal object SettingsMotion {
    // Durations = the IME-side MD3 tokens (Long ms → Int ms for Compose tween).
    val DURATION_NAV = Motion.MODE_SWITCH.toInt()      // 300 — page navigation
    val DURATION_FADE_IN = Motion.FADE_IN.toInt()      // 150
    val DURATION_FADE_OUT = Motion.FADE_OUT.toInt()    // 100
    val DURATION_STATE = Motion.STATE_CHANGE.toInt()   // 200 — reveal / expand-collapse

    // MD3 easings — identical control points to Motion's PathInterpolators.
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Shared-axis X slide fraction: a short spatial cue (MD3 leans on the fade, not a full-screen slide). */
    private const val SLIDE_FRACTION = 8

    /** Forward navigation (home → sub-page): incoming enters from the end, fading in. */
    fun forwardEnter(scope: AnimatedContentTransitionScope<*>): EnterTransition =
        scope.run {
            slideInHorizontally(tween(DURATION_NAV, easing = Emphasized)) { it / SLIDE_FRACTION } +
                fadeIn(tween(DURATION_FADE_IN, easing = EmphasizedDecelerate))
        }

    /** Forward navigation: the outgoing page leaves toward the start, fading out. */
    fun forwardExit(scope: AnimatedContentTransitionScope<*>): ExitTransition =
        scope.run {
            slideOutHorizontally(tween(DURATION_NAV, easing = Emphasized)) { -it / SLIDE_FRACTION } +
                fadeOut(tween(DURATION_FADE_OUT, easing = EmphasizedAccelerate))
        }

    /** Back navigation (sub-page → home): incoming enters from the start, fading in (mirror of forward). */
    fun backEnter(scope: AnimatedContentTransitionScope<*>): EnterTransition =
        scope.run {
            slideInHorizontally(tween(DURATION_NAV, easing = Emphasized)) { -it / SLIDE_FRACTION } +
                fadeIn(tween(DURATION_FADE_IN, easing = EmphasizedDecelerate))
        }

    /** Back navigation: the outgoing page leaves toward the end, fading out. */
    fun backExit(scope: AnimatedContentTransitionScope<*>): ExitTransition =
        scope.run {
            slideOutHorizontally(tween(DURATION_NAV, easing = Emphasized)) { it / SLIDE_FRACTION } +
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
