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
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * §1 (jank root cause). The old [CandidateGridView.setCandidates]/[setReadings] did
 * removeAllViews + a fresh TextView + RippleDrawable per candidate / per reading on EVERY render (i.e. every
 * keystroke, and — the reported jank — every left-column tap re-decode), allocating hundreds of Views + ripples
 * in one frame. These prove the recycling pools cut that to O(peak), keep content / click-index / ripple correct
 * across the full list-change matrix (grow / shrink / change / clear), and move the selected state via an MD3
 * state-layer colour cross-fade rather than a rebuild.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CandidateGridRecyclingTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private fun grid() = CandidateGridView(ctx).apply {
        applyPalette(ImePalette.STATIC_LIGHT)
        measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((250 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, measuredWidth, measuredHeight)
    }

    // ---- QUANTIFICATION: allocation before/after (the hard gate) ----------------------------------------

    @Test fun middle_grid_allocates_at_peak_not_sum_across_a_typing_burst() {
        val v = grid()
        val passes = 20
        val perList = 60
        // A realistic composing burst: 20 keystrokes, each a DIFFERENT 60-candidate list (defeats the dedup
        // guard so every pass really rebinds).
        for (p in 0 until passes) v.setCandidates((0 until perList).map { "候$p-$it" })
        // OLD behaviour allocated perList TextViews + perList RippleDrawables PER pass = 20*60 = 1200 of each.
        // NEW: the pool tops out at the peak list size — 60 — and never allocates again.
        assertEquals("chip allocations must equal the peak list size, not the sum over passes", perList, v.chipsAllocatedForTest())
        assertTrue("recycling must beat the old O(sum)=1200 allocations by an order of magnitude", v.chipsAllocatedForTest() * 10 <= passes * perList)
    }

    @Test fun left_column_re_tap_allocates_at_peak_not_sum() {
        val v = grid()
        val passes = 20
        val perList = 12
        // Each left-column tap re-renders the reading column (the reported "左栏每点再全重建" jank).
        for (p in 0 until passes) v.setReadings((0 until perList).map { "r$p-$it" }, selected = p % perList)
        assertEquals("reading allocations must equal the peak list size, not the sum over passes", perList, v.readingsAllocatedForTest())
    }

    @Test fun click_frame_after_warmup_allocates_zero_views() {
        val v = grid()
        // Warm the pools to the working-set size (what a live session reaches within the first keystrokes).
        v.setCandidates((0 until 80).map { "暖$it" })
        v.setReadings((0 until 12).map { "w$it" }, selected = 0)
        val chipsBefore = v.chipsAllocatedForTest()
        val readingsBefore = v.readingsAllocatedForTest()
        // Simulate ONE left-column tap frame: reading selection changes AND the candidates re-decode.
        v.setReadings((0 until 12).map { "w$it" }, selected = 5) // pure selection change
        v.setCandidates((0 until 80).map { "候$it" })            // re-decode, new content, same size
        assertEquals("a warm click frame must allocate zero new chips (old code: 80)", chipsBefore, v.chipsAllocatedForTest())
        assertEquals("a warm click frame must allocate zero new reading tiles (old code: 12)", readingsBefore, v.readingsAllocatedForTest())
    }

    // ---- CORRECTNESS across the full list-change matrix --------------------------------------------------

    @Test fun middle_grid_content_is_correct_across_grow_shrink_change_clear() {
        val v = grid()
        v.setCandidates(listOf("你", "好"))
        assertEquals(listOf("你", "好"), v.renderedCandidateTextsForTest())
        v.setCandidates(listOf("你", "好", "吗", "呀", "呢")) // grow
        assertEquals(listOf("你", "好", "吗", "呀", "呢"), v.renderedCandidateTextsForTest())
        v.setCandidates(listOf("我", "们")) // shrink + change
        assertEquals(listOf("我", "们"), v.renderedCandidateTextsForTest())
        v.setCandidates(emptyList()) // clear
        assertEquals(emptyList<String>(), v.renderedCandidateTextsForTest())
        v.setCandidates(listOf("重", "来")) // re-grow after clear reuses the pool
        assertEquals(listOf("重", "来"), v.renderedCandidateTextsForTest())
    }

    @Test fun left_column_content_is_correct_across_grow_shrink_change_clear() {
        val v = grid()
        v.setReadings(listOf("ni", "hao"))
        assertEquals(listOf("ni", "hao"), v.renderedReadingTextsForTest())
        v.setReadings(listOf("zhang", "xiang", "xia")) // grow + change
        assertEquals(listOf("zhang", "xiang", "xia"), v.renderedReadingTextsForTest())
        v.setReadings(listOf("wo")) // shrink parks the surplus pooled tiles GONE
        assertEquals(listOf("wo"), v.renderedReadingTextsForTest())
        v.setReadings(emptyList()) // clear
        assertEquals(emptyList<String>(), v.renderedReadingTextsForTest())
    }

    @Test fun middle_grid_click_index_stays_bound_after_recycling() {
        val v = grid()
        var picked = -1
        v.onPick = { picked = it }
        v.setCandidates((0 until 30).map { "候$it" })
        v.setCandidates((0 until 8).map { "字$it" }) // shrink + fully different content
        assertTrue("tapping the 5th recycled chip", v.tapCandidateForTest(5))
        assertEquals("the recycled chip must still pick candidate index 5, not a stale index", 5, picked)
    }

    @Test fun left_column_click_index_stays_bound_after_recycling() {
        val v = grid()
        var pickedReading = -1
        v.onPickReading = { pickedReading = it }
        v.setReadings((0 until 10).map { "r$it" }, selected = 0)
        v.setReadings((0 until 4).map { "s$it" }, selected = 0) // shrink + change
        assertTrue(v.tapReadingForTest(2))
        assertEquals("the recycled reading tile must still pick reading index 2", 2, pickedReading)
    }

    @Test fun recycled_tiles_keep_a_ripple_foreground() {
        val v = grid()
        v.setCandidates(listOf("你", "好"))
        v.setReadings(listOf("ni", "hao"), selected = 0)
        v.setCandidates(listOf("我")) // recycle
        v.setReadings(listOf("wo"), selected = 0)
        // Prove the press feedback survived recycling (the ripple is built once and re-tinted, never dropped).
        assertNotNull("recycled chip keeps its ripple", v.firstChipForegroundForTest())
        assertTrue("recycled chip foreground is a RippleDrawable", v.firstChipForegroundForTest() is RippleDrawable)
    }

    // ---- SELECTED-STATE colour cross-fade (§2: 网格与左栏选中态 — 非瞬时变色) --------------------------------

    @Test fun selection_only_change_recolours_without_reallocating() {
        val v = grid()
        val pal = ImePalette.STATIC_LIGHT
        v.setReadings(listOf("zhang", "xiang", "xia"), selected = 0)
        val allocBefore = v.readingsAllocatedForTest()
        v.setReadings(listOf("zhang", "xiang", "xia"), selected = 2) // same list, moved selection
        assertEquals("a pure selection change must not allocate", allocBefore, v.readingsAllocatedForTest())
        assertEquals("new selection paints the accent", pal.accentBottom, v.readingTextColorForTest(2))
        assertEquals("old selection returns to the default text colour", pal.candidateText, v.readingTextColorForTest(0))
        assertEquals("untouched middle reading keeps the default colour", pal.candidateText, v.readingTextColorForTest(1))
    }

    @Test fun selection_change_crossfades_when_attached_and_animated() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = FrameLayout(activity)
        val v = CandidateGridView(activity).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        host.addView(v)
        activity.setContentView(host)
        v.setReadings(listOf("ni", "hao", "hai"), selected = 0)
        v.setReadings(listOf("ni", "hao", "hai"), selected = 2) // selection-only → state-layer cross-fade
        assertTrue("an attached, animated selection change runs a colour cross-fade (state layer, not a snap)", v.activeReadingColorAnimatorsForTest() > 0)
    }

    @Test fun selection_change_under_reduced_motion_snaps_to_the_final_colour() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val pal = ImePalette.STATIC_LIGHT
        val v = grid()
        v.setReadings(listOf("ni", "hao", "hai"), selected = 0)
        v.setReadings(listOf("ni", "hao", "hai"), selected = 2)
        assertEquals("reduced motion jumps straight to the accent", pal.accentBottom, v.readingTextColorForTest(2))
        assertEquals(0, v.activeReadingColorAnimatorsForTest())
    }
}
