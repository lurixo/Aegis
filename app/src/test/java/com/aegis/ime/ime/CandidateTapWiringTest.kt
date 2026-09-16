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

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CandidateTapWiringTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val stale = listOf("就", "就是", "九", "酒")
    private val fresh = listOf("就是", "就是说", "九", "酒")

    private fun inputView(picked: MutableList<Int>): InputView {
        val iv = InputView(ctx).apply {
            showKeyboard(Layouts.forId(LayoutId.NINE, Lang.CN), false, false, Lang.CN)
            showCandidates(stale, "jiu", emptyList(), candidatesPending = true)
            onPickCandidate = { picked += it }
        }
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(iv)
        val density = ctx.resources.displayMetrics.density
        iv.measure(
            View.MeasureSpec.makeMeasureSpec((411 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((891 * density).toInt(), View.MeasureSpec.AT_MOST),
        )
        iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)
        return iv
    }

    private fun event(action: Int, down: Long, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)

    private fun tapStrip(iv: InputView, index: Int) {
        val bar = iv.candidateBarForTest()
        val (x, y) = requireNotNull(bar.centerOfCandidateForTest(index))
        val down = SystemClock.uptimeMillis()
        bar.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, down, x, y))
        bar.dispatchTouchEvent(event(MotionEvent.ACTION_UP, down, x, y))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private fun settleSlowly(iv: InputView, next: List<String>) {
        advance(CandidateTapGuard.STALE_VISIBLE_MILLIS + 30)
        iv.showCandidates(next, "jiu", emptyList(), candidatesPending = false)
        iv.measure(
            View.MeasureSpec.makeMeasureSpec(iv.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(iv.height, View.MeasureSpec.EXACTLY),
        )
        iv.layout(0, 0, iv.width, iv.height)
    }

    @Test fun aStripTapRightAfterASlowListLandsSkipsAWordThatReplacedTheOneOnScreen() {
        val picked = ArrayList<Int>()
        val iv = inputView(picked)
        settleSlowly(iv, fresh)
        tapStrip(iv, 0)
        assertEquals(emptyList<Int>(), picked)
    }

    @Test fun aStripTapRightAfterASlowListLandsKeepsAWordThatStayedPut() {
        val picked = ArrayList<Int>()
        val iv = inputView(picked)
        settleSlowly(iv, fresh)
        tapStrip(iv, 3)
        assertEquals(listOf(3), picked)
    }

    @Test fun aStripTapOnceTheListHasSettledIsCommitted() {
        val picked = ArrayList<Int>()
        val iv = inputView(picked)
        settleSlowly(iv, fresh)
        advance(CandidateTapGuard.SETTLE_MILLIS + 10)
        tapStrip(iv, 0)
        assertEquals(listOf(0), picked)
    }

    @Test fun anExpandedGridWordReplacedUnderTheFingerIsNotCommitted() {
        val picked = ArrayList<Int>()
        val iv = inputView(picked)
        iv.showExpandedCandidates()
        shadowOf(Looper.getMainLooper()).idle()
        val grid = iv.expandedGridForTest()
        val x = grid.width / 2f
        val y = grid.height / 2f
        val down = SystemClock.uptimeMillis()
        grid.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, down, x, y))
        grid.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, down, x, y))
        iv.showCandidates(fresh, "jiu", emptyList(), candidatesPending = false)
        assertTrue(grid.tapCandidateForTest(1))
        assertEquals(emptyList<Int>(), picked)
        assertTrue(grid.tapCandidateForTest(2))
        assertEquals(listOf(2), picked)
    }
}
