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

import android.graphics.RectF

class ImeToast {

    var message: String = ""
        private set

    private var shownAt = 0L
    private var visible = false

    fun show(text: String, now: Long) {
        message = text
        shownAt = now
        visible = text.isNotEmpty()
    }

    fun hide() { visible = false; message = "" }

    fun isShowing(now: Long): Boolean = visible && now - shownAt < TOTAL_MS

    fun alphaAt(now: Long): Float {
        if (!isShowing(now)) return 0f
        val elapsed = now - shownAt
        return when {
            elapsed < FADE_MS -> elapsed.toFloat() / FADE_MS
            elapsed > HOLD_MS -> ((TOTAL_MS - elapsed).toFloat() / FADE_MS).coerceIn(0f, 1f)
            else -> 1f
        }
    }

    fun remainingMs(now: Long): Long = (TOTAL_MS - (now - shownAt)).coerceAtLeast(0L)

    companion object {

        const val HOLD_MS = 1500L
        const val FADE_MS = 180L
        const val TOTAL_MS = HOLD_MS + FADE_MS

        const val ANCHOR_FRACTION = 0.625f

        fun bounds(
            keyboardArea: RectF,
            contentWidth: Float,
            contentHeight: Float,
            paddingStart: Float,
            paddingEnd: Float,
            paddingY: Float,
            minMargin: Float,
        ): RectF {
            val width = (contentWidth + paddingStart + paddingEnd)
                .coerceAtMost((keyboardArea.width() - minMargin * 2).coerceAtLeast(0f))
            val height = contentHeight + paddingY * 2
            val cx = keyboardArea.centerX()
            val cy = keyboardArea.top + keyboardArea.height() * ANCHOR_FRACTION
            val top = cy - height / 2f
            return RectF(cx - width / 2f, top, cx + width / 2f, top + height)
        }
    }
}
