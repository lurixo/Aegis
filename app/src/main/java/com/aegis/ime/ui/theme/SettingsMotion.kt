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

internal object SettingsMotion {
    val DURATION_NAV = Motion.MODE_SWITCH.toInt()
    val DURATION_FADE_IN = Motion.FADE_IN.toInt()
    val DURATION_FADE_OUT = Motion.FADE_OUT.toInt()
    val DURATION_STATE = Motion.STATE_CHANGE.toInt()

    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    private const val SLIDE_FRACTION = 8

    fun forwardEnter(scope: AnimatedContentTransitionScope<*>): EnterTransition =
        scope.run {
            slideInHorizontally(tween(DURATION_NAV, easing = Emphasized)) { it / SLIDE_FRACTION } +
                fadeIn(tween(DURATION_FADE_IN, easing = EmphasizedDecelerate))
        }

    fun forwardExit(scope: AnimatedContentTransitionScope<*>): ExitTransition =
        scope.run {
            slideOutHorizontally(tween(DURATION_NAV, easing = Emphasized)) { -it / SLIDE_FRACTION } +
                fadeOut(tween(DURATION_FADE_OUT, easing = EmphasizedAccelerate))
        }

    fun backEnter(scope: AnimatedContentTransitionScope<*>): EnterTransition =
        scope.run {
            slideInHorizontally(tween(DURATION_NAV, easing = Emphasized)) { -it / SLIDE_FRACTION } +
                fadeIn(tween(DURATION_FADE_IN, easing = EmphasizedDecelerate))
        }

    fun backExit(scope: AnimatedContentTransitionScope<*>): ExitTransition =
        scope.run {
            slideOutHorizontally(tween(DURATION_NAV, easing = Emphasized)) { it / SLIDE_FRACTION } +
                fadeOut(tween(DURATION_FADE_OUT, easing = EmphasizedAccelerate))
        }

    fun revealEnter(): EnterTransition =
        expandVertically(tween(DURATION_STATE, easing = EmphasizedDecelerate)) +
            fadeIn(tween(DURATION_STATE, easing = EmphasizedDecelerate))

    fun collapseExit(): ExitTransition =
        shrinkVertically(tween(DURATION_STATE, easing = EmphasizedAccelerate)) +
            fadeOut(tween(DURATION_FADE_OUT, easing = EmphasizedAccelerate))
}
