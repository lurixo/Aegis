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

import android.os.Handler
import android.os.Looper
import kotlin.math.abs

class BackspaceGesture(density: Float) {

    var onRepeat: () -> Unit = {}
    var onSwipe: (Boolean) -> Unit = {}

    private val handler = Handler(Looper.getMainLooper())
    private val swipeThreshold = SWIPE_THRESHOLD_DP * density
    private var active = false
    private var downX = 0f
    private var downY = 0f
    private var repeating = false
    private var swiped = false
    private var lockedDirectionUp: Boolean? = null

    var swipeDirectionUp: Boolean? = null
        private set

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (!active) return
            repeating = true
            onRepeat()
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    fun begin(x: Float, y: Float) {
        stopRepeat()
        active = true
        repeating = false
        swiped = false
        lockedDirectionUp = null
        swipeDirectionUp = null
        downX = x
        downY = y
        handler.postDelayed(repeatRunnable, REPEAT_DELAY_MS)
    }

    fun move(x: Float, y: Float, inBounds: Boolean) {
        if (!active) return
        val dy = y - downY
        if (!swiped && !repeating && abs(dy) > swipeThreshold && abs(dy) > abs(x - downX)) {
            swiped = true
            lockedDirectionUp = dy < 0f
            stopRepeat()
        } else if (!inBounds) {
            stopRepeat()
        }
        if (swiped) swipeDirectionUp = if (onLockedSide(dy)) lockedDirectionUp else null
    }

    private fun onLockedSide(dy: Float): Boolean {
        val locked = lockedDirectionUp ?: return false
        return abs(dy) > swipeThreshold && (dy < 0f) == locked
    }

    fun finish(y: Float): Boolean {
        stopRepeat()
        swipeDirectionUp = null
        if (!active) return false
        active = false
        val locked = lockedDirectionUp
        val committed = onLockedSide(y - downY)
        lockedDirectionUp = null
        if (swiped) {
            if (!repeating && committed && locked != null) onSwipe(locked)
            return false
        }
        return !repeating
    }

    fun cancel() {
        stopRepeat()
        active = false
        repeating = false
        swiped = false
        lockedDirectionUp = null
        swipeDirectionUp = null
    }

    private fun stopRepeat() {
        handler.removeCallbacks(repeatRunnable)
    }

    companion object {
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 55L
        const val SWIPE_THRESHOLD_DP = 24f
    }
}
