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

import android.view.View
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * §2 — JANK REMEDIATION, quantified before/after.
 *   (a) KeyboardView no longer sits on a software layer (it drew NO shadow, so the layer was pure per-frame
 *       CPU re-raster on every key press). It must draw straight to the hardware canvas.
 *   (b) ClipboardView's select / sort / category-sort lists recycle their rows: a re-sweep or a select-mode
 *       radio toggle (which re-renders the whole list) allocates ZERO new rows past the high-water mark, and
 *       the row content stays correct after reuse. Before this each of those rebuilt every row from scratch
 *       synchronously. They are also FRAMED now (≤48 rows/frame) like the normal list, not one synchronous
 *       N-view pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JankRemediationTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val light = ImePalette.STATIC_LIGHT

    // ---- (a) KeyboardView software layer removed -------------------------------------------------------

    @Test fun keyboard_view_is_not_on_a_software_layer() {
        val kv = KeyboardView(ctx)
        assertEquals(
            "the keyboard draws only flat fills/strokes (no shadow) — a software layer was pure re-raster cost",
            View.LAYER_TYPE_NONE,
            kv.layerType,
        )
    }

    // ---- (b) ClipboardView recycling + framing ---------------------------------------------------------

    private fun clip(history: List<String>, phrases: Map<String, List<String>> = emptyMap()): ClipboardView =
        ClipboardView(ctx).apply {
            historyProvider = { history }
            categoriesProvider = { phrases.keys.toList() }
            phrasesInProvider = { phrases[it] ?: emptyList() }
            applyPalette(light)
            refresh()
        }

    @Test fun select_mode_recycles_rows_a_radio_toggle_allocates_zero_new_rows() {
        val clips = (1..5).map { "clip-$it" }
        val v = clip(clips)
        v.enterSelectForTest()
        val afterFirst = v.selectRowsAllocatedForTest()
        assertEquals("the pool tops out at the row count", clips.size, afterFirst)
        // A radio tap re-renders the WHOLE list (st.toggleSelect + refresh) — the worst churn path. It must
        // now reuse the pooled rows.
        v.listRowViewForTest(0)?.performClick()
        v.listRowViewForTest(2)?.performClick()
        assertEquals("re-rendering on every toggle allocates no new rows", afterFirst, v.selectRowsAllocatedForTest())
        // Re-entering select mode after leaving also reuses the pool.
        v.exitSelectForTest()
        v.enterSelectForTest()
        assertEquals("a re-sweep allocates nothing new", afterFirst, v.selectRowsAllocatedForTest())
    }

    @Test fun select_mode_content_is_correct_after_recycling() {
        val v = clip(listOf("alpha", "beta", "gamma"))
        v.enterSelectForTest()
        v.exitSelectForTest()
        v.enterSelectForTest() // second render reuses the pooled rows
        val texts = (0 until v.listRowCountForTest()).map { rowText(v.listRowViewForTest(it)!!) }
        assertEquals(listOf("alpha", "beta", "gamma"), texts)
    }

    @Test fun sort_mode_recycles_rows_across_re_renders() {
        val phrases = mapOf("默认" to listOf("p1", "p2", "p3", "p4"))
        val v = clip(emptyList(), phrases)
        v.forcePhrasesStateForTest("默认")
        v.enterSortModeForTest()
        val first = v.sortRowsAllocatedForTest()
        assertEquals(4, first)
        v.enterSortModeForTest() // re-render
        assertEquals("sort rows recycle across rebuilds", first, v.sortRowsAllocatedForTest())
    }

    @Test fun category_sort_mode_recycles_rows_across_re_renders() {
        val phrases = mapOf("A" to listOf("x"), "B" to listOf("y"), "C" to listOf("z"))
        val v = clip(emptyList(), phrases)
        v.forcePhrasesStateForTest("A")
        v.enterCategorySortModeForTest()
        val first = v.catSortRowsAllocatedForTest()
        assertEquals(3, first)
        v.enterCategorySortModeForTest()
        assertEquals("category-sort rows recycle across rebuilds", first, v.catSortRowsAllocatedForTest())
    }

    @Test fun select_mode_is_framed_not_one_synchronous_pass() {
        // 60 clips > INITIAL_SYNC_ROWS(48): the first frame inflates only the sync window, the rest append on
        // later frames — the same framing as the normal list (was a synchronous 60-row pass).
        val v = clip((1..60).map { "c$it" })
        v.enterSelectForTest()
        assertEquals("first frame is capped at the sync window", v.initialSyncRowsForTest(), v.listRowCountForTest())
        while (v.runPendingListAppendForTest()) { /* drain the framed appends */ }
        assertEquals("all rows present once the frames drain", 60, v.listRowCountForTest())
        assertEquals("and the pool still tops out at the total, allocating each once", 60, v.selectRowsAllocatedForTest())
    }

    // ---- §3: mode changes fade-through like the tab switch (consistent motion language) ----------------

    @Test fun clipboard_mode_change_fades_through_only_on_a_real_mode_change() {
        val v = clip(listOf("a", "b"))
        val m0 = v.modeTransitionsForTest()
        v.enterSelectForTest() // normal → select
        assertEquals("entering select fades once", m0 + 1, v.modeTransitionsForTest())
        v.exitSelectForTest() // select → normal
        assertEquals("leaving select fades once", m0 + 2, v.modeTransitionsForTest())
        v.enterSelectForTest()
        val m = v.modeTransitionsForTest()
        v.listRowViewForTest(0)?.performClick() // a select toggle stays in select mode → refresh, no mode fade
        assertEquals("a same-mode refresh must not fade", m, v.modeTransitionsForTest())
    }

    @Test fun recycled_select_rows_repaint_on_a_light_to_dark_palette_change() {
        // The pool captured the palette at build time and rebinds only item state — a theme switch must still
        // repaint the rows (F1 "no stale colours"). applyPalette drops the pools so refresh rebuilds them dark.
        val v = ClipboardView(ctx).apply {
            historyProvider = { listOf("a", "b") }
            applyPalette(ImePalette.STATIC_LIGHT)
            refresh()
        }
        v.enterSelectForTest() // pool built under the light palette
        v.applyPalette(ImePalette.STATIC_DARK)
        val label = (v.listRowViewForTest(0) as android.view.ViewGroup).getChildAt(1) as android.widget.TextView
        assertEquals(
            "recycled select rows must follow a light→dark switch, not keep the stale light colour",
            ImePalette.STATIC_DARK.keyLabel,
            label.currentTextColor,
        )
    }

    private fun rowText(row: View): String {
        fun first(v: View): String? {
            if (v is android.widget.TextView) return v.text?.toString()
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) first(v.getChildAt(i))?.let { return it }
            return null
        }
        return first(row) ?: ""
    }
}
