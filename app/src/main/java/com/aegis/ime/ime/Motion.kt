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

/**
 * U-anim: a tiny MD3 motion vocabulary for the IME. Animation was entirely absent; this adds ONLY restrained,
 * one-shot, View-layer (alpha) transitions that cannot affect layout — never height, never the root position,
 * never a per-frame onDraw loop — so onComputeInsets (the fixed IME height / U19) and the input subsystem's
 * scroll/fling are untouched. Honours the system "remove animations" setting (ANIMATOR_DURATION_SCALE == 0).
 */
object Motion {
    /** MD3 standard easing — the everyday in/out curve. */
    val STANDARD: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    /** MD3 emphasized-decelerate — for an element ENTERING the screen. */
    val EMPHASIZED_DECEL: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    /** MD3 duration tokens (ms). */
    const val SHORT2 = 100L
    const val SHORT4 = 200L

    /** False when the user has turned system animations off — callers then jump straight to the end state. */
    fun enabled(ctx: Context): Boolean =
        runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) != 0f

    /**
     * Fade [view] in from transparent (MD3 fade-through, incoming half). One-shot alpha only — no scale,
     * translation, or size change. No-op to a fully-shown view when animations are disabled.
     */
    fun fadeIn(view: View, duration: Long = SHORT4) {
        view.animate().cancel()
        // Skip when detached (no frame loop to drive it — also keeps unit tests at the resting alpha) or when
        // the user disabled system animations: jump straight to the shown state. We MUST invalidate() as well,
        // not just set alpha: the caller has changed content (e.g. PreeditView.setText) and a persistently-
        // visible view may already be resting at alpha 1f, so setAlpha(1f) hits View's `mAlpha == alpha` no-op
        // guard and schedules no repaint — without this the new content would stay unpainted until the next
        // change (the reduced-motion preedit-blank regression). invalidate() is one-shot, not a frame loop.
        if (!view.isAttachedToWindow || !enabled(view.context)) {
            view.alpha = 1f
            view.invalidate()
            return
        }
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(duration).setInterpolator(EMPHASIZED_DECEL).start()
    }

    /** Cancel any running animation and reset [view] to its resting state (call from onDetachedFromWindow so a
     *  theme-switch rebuild — S3 — never inherits a half-faded view). */
    fun reset(view: View) {
        view.animate().cancel()
        view.alpha = 1f
    }
}
