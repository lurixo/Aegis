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
import android.view.ViewConfiguration
import android.widget.OverScroller

class FlingScroller(context: Context) {
    private val scroller = OverScroller(context)
    private val minVel = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val maxVel = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val sampleT = LongArray(SAMPLES)
    private val samplePos = FloatArray(SAMPLES)
    private var head = 0
    private var count = 0

    var stopArmed = false
        private set

    fun onDown() {
        stopArmed = !scroller.isFinished
        if (stopArmed) scroller.forceFinished(true)
        count = 0; head = 0
    }

    fun addSample(t: Long, pos: Float) {
        sampleT[head] = t; samplePos[head] = pos
        head = (head + 1) % SAMPLES
        if (count < SAMPLES) count++
    }

    fun velocity(): Float {
        if (count < 2) return 0f
        val newest = (head - 1 + SAMPLES) % SAMPLES
        val tNew = sampleT[newest]; val pNew = samplePos[newest]
        var ref = newest
        for (k in 1 until count) {
            val idx = (newest - k + SAMPLES) % SAMPLES
            ref = idx
            if (tNew - sampleT[idx] >= WINDOW_MS) break
        }
        val dt = (tNew - sampleT[ref]).toFloat()
        if (dt <= 0f) return 0f
        return ((pNew - samplePos[ref]) / dt * 1000f).coerceIn(-maxVel, maxVel)
    }

    fun fling(start: Float, max: Float): Boolean {
        val v = velocity()
        if (kotlin.math.abs(v) <= minVel || max <= 0f) return false
        scroller.fling(0, start.toInt(), 0, (-v).toInt(), 0, 0, 0, max.toInt())
        return true
    }

    fun predictFinalOffset(start: Float): Float {
        val v = velocity()
        if (kotlin.math.abs(v) <= minVel) return start
        scroller.fling(0, start.toInt(), 0, (-v).toInt(), 0, 0, 0, Int.MAX_VALUE)
        val end = scroller.finalY.toFloat()
        scroller.forceFinished(true)
        return end
    }

    val isFinished: Boolean get() = scroller.isFinished

    fun finalOffset(): Float = scroller.finalY.toFloat()

    private companion object {
        const val SAMPLES = 12
        const val WINDOW_MS = 100L
    }
}
