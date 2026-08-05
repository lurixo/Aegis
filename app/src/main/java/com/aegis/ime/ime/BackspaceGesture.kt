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
        downX = x
        downY = y
        handler.postDelayed(repeatRunnable, REPEAT_DELAY_MS)
    }

    fun move(x: Float, y: Float, inBounds: Boolean) {
        if (!active) return
        val dy = y - downY
        if (!swiped && !repeating && abs(dy) > swipeThreshold && abs(dy) > abs(x - downX)) {
            swiped = true
            stopRepeat()
        } else if (!inBounds) {
            stopRepeat()
        }
    }

    fun finish(y: Float): Boolean {
        stopRepeat()
        if (!active) return false
        active = false
        if (swiped) {
            if (!repeating) onSwipe(y - downY < 0f)
            return false
        }
        return !repeating
    }

    fun cancel() {
        stopRepeat()
        active = false
        repeating = false
        swiped = false
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
