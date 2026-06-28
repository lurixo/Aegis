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

import kotlin.math.abs

internal class PanelPageGesture(private val touchSlop: Float, private val flingVelocity: Float) {

    var dragging = false
        private set

    var offset = 0f
        private set

    private var tracking = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var pageWidth = 0f
    private var minOffset = 0f
    private var maxOffset = 0f

    fun begin(x: Float, y: Float, pageWidth: Float, hasPrevious: Boolean, hasNext: Boolean) {
        tracking = pageWidth > 0f
        dragging = false
        offset = 0f
        downX = x
        downY = y
        lastX = x
        this.pageWidth = pageWidth
        minOffset = if (hasNext) -pageWidth else 0f
        maxOffset = if (hasPrevious) pageWidth else 0f
    }

    fun move(x: Float, y: Float): Boolean {
        if (dragging) {
            offset = (offset + x - lastX).coerceIn(minOffset, maxOffset)
            lastX = x
        } else if (tracking) {
            val dx = x - downX
            val dy = y - downY
            if (abs(dx) > touchSlop && abs(dx) > abs(dy) * DIRECTION_BIAS) {
                dragging = true
                lastX = x
                offset = (dx - if (dx > 0f) touchSlop else -touchSlop).coerceIn(minOffset, maxOffset)
            } else if (abs(dy) > touchSlop) {
                tracking = false
            }
        }
        return dragging
    }

    fun rebase(x: Float) {
        lastX = x
    }

    fun release(velocityX: Float): Int {
        val step = when {
            !dragging -> 0
            offset < 0f && (velocityX <= -flingVelocity || (velocityX < flingVelocity && -offset > pageWidth / 2f)) -> 1
            offset > 0f && (velocityX >= flingVelocity || (velocityX > -flingVelocity && offset > pageWidth / 2f)) -> -1
            else -> 0
        }
        cancel()
        return step
    }

    fun cancel() {
        tracking = false
        dragging = false
    }

    companion object {
        const val DIRECTION_BIAS = 1.5f
        const val FLING_VELOCITY_DP = 400f
    }
}
