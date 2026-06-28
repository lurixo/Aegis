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

import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

object Motion {
    val STANDARD: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    val EMPHASIZED_DECEL: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    const val SHORT2 = 100L
    const val SHORT4 = 200L

    fun enabled(ctx: Context): Boolean =
        runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) != 0f

    fun fadeIn(view: View, duration: Long = SHORT4) {
        view.animate().cancel()
        if (!view.isAttachedToWindow || !enabled(view.context)) { view.alpha = 1f; return }
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(duration).setInterpolator(EMPHASIZED_DECEL).start()
    }

    fun reset(view: View) {
        view.animate().cancel()
        view.alpha = 1f
    }
}
