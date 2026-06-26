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

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadingSwapUnderPressTest {

    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun a_list_swap_under_a_pressed_finger_cannot_commit_the_new_reading() {
        val v = CandidateGridView(ctx)
        val picked = ArrayList<Int>()
        v.onPickReading = { picked.add(it) }
        v.setReadings(listOf("ma", "mo"))
        val tile = requireNotNull(v.readingTileForTest(0))
        tile.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0))
        assertTrue("the press is owned from the first touch", tile.isPressed)
        v.setReadings(listOf("xa", "ma"))
        tile.dispatchTouchEvent(MotionEvent.obtain(0, 30, MotionEvent.ACTION_UP, 1f, 1f, 0))
        assertTrue("a press on a replaced reading commits nothing", picked.isEmpty())
        v.setReadings(listOf("xa", "ma"))
        requireNotNull(v.readingTileForTest(0)).performClick()
        assertEquals(listOf(0), picked)
    }

    @Test fun a_tap_resolves_through_the_reading_not_the_slot() {
        val v = CandidateGridView(ctx)
        val picked = ArrayList<Int>()
        v.onPickReading = { picked.add(it) }
        v.setReadings(listOf("ma", "mo"))
        requireNotNull(v.readingTileForTest(1)).performClick()
        assertEquals(listOf(1), picked)
    }
}
