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

class ScrollbarFade {

    private var shownAt = 0L
    private var lastScrollAt = 0L
    private var visible = false

    fun scrolled(now: Long) {
        val alpha = alphaAt(now)
        if (alpha < 1f) shownAt = now - (alpha * FADE_MS).toLong()
        lastScrollAt = now
        visible = true
    }

    fun hide() { visible = false }

    fun isShowing(now: Long): Boolean = visible && now - lastScrollAt < HOLD_MS + FADE_MS

    fun alphaAt(now: Long): Float {
        if (!isShowing(now)) return 0f
        val fadeIn = ((now - shownAt).toFloat() / FADE_MS).coerceIn(0f, 1f)
        val idle = now - lastScrollAt
        val fadeOut = if (idle <= HOLD_MS) 1f else ((HOLD_MS + FADE_MS - idle).toFloat() / FADE_MS).coerceIn(0f, 1f)
        return minOf(fadeIn, fadeOut)
    }

    fun nextTickDelayMs(now: Long): Long? {
        if (!isShowing(now)) return null
        val idle = now - lastScrollAt
        return if (now - shownAt < FADE_MS || idle >= HOLD_MS) 0L else HOLD_MS - idle
    }

    companion object {
        const val HOLD_MS = ImeToast.HOLD_MS
        const val FADE_MS = ImeToast.FADE_MS
    }
}
