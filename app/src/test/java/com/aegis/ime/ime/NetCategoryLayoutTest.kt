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
import android.view.ViewGroup
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.SymbolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetCategoryLayoutTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val light = ImePalette.STATIC_LIGHT
    private val density = ctx.resources.displayMetrics.density
    private val netIndex = SymbolCatalog.categories.indexOfFirst { it.id == "net" } + 1
    private val mathIndex = SymbolCatalog.categories.indexOfFirst { it.id == "math" } + 1

    @Test fun net_merges_url_keys_and_symbols_into_one_span_grid() {
        val sv = SymbolsView(ctx)
        sv.applyPalette(light)
        sv.openCategoryForTest(netIndex)

        assertFalse("no separate 网址补全 strip on the 网络 tab", sv.netBarVisibleForTest())
        assertFalse("no chip bar container shown", sv.chipBarVisibleForTest())
        val cells = sv.gridCellTextsForTest()
        assertEquals(
            "url keys ride the front of the merged grid, then the single symbols in order",
            listOf("http://", "https://", ".", "/", "@", "-", "_", ":", "#", "?", "&", "=", "%"),
            cells,
        )
        assertFalse("http://www. variant dropped", "http://www." in cells)
        assertFalse("https://www. variant dropped", "https://www." in cells)
        assertFalse("standalone www. gone", "www." in cells)
        assertFalse("standalone :// gone", "://" in cells)
        assertEquals("all 13 net keys are grid cells", 13, sv.gridCellCountForTest())
    }

    @Test fun net_url_key_spans_two_single_cells_and_columns_line_up() {
        val sv = SymbolsView(ctx)
        sv.applyPalette(light)
        sv.openCategoryForTest(netIndex)
        val widthPx = (600 * density).toInt()
        sv.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        sv.layout(0, 0, sv.measuredWidth, sv.measuredHeight)

        val single = listOf(".", "/", "@", "-", "_", ":", "#", "?", "&", "=", "%")
            .map { sv.gridCellPixelWidthForTest(it) }
        assertTrue("every single cell is laid out", single.all { it > 0 })
        assertTrue("single-char columns line up (equal width): $single", single.max() - single.min() <= 2)

        val col0 = sv.gridCellPixelWidthForTest("-")
        val col1 = sv.gridCellPixelWidthForTest("_")
        val urlW = sv.gridCellPixelWidthForTest("http://")
        assertEquals(
            "a url key owns exactly two adjacent logical hit cells while key-face insets provide the visual gap",
            (col0 + col1).toFloat(),
            urlW.toFloat(),
            1.5f,
        )
    }

    @Test fun a_completion_surfaced_in_recents_is_chipped_not_truncated_in_the_grid() {
        val sv = SymbolsView(ctx)
        sv.recentProvider = { listOf("https://", ".", "。") }
        sv.applyPalette(light)
        sv.openCategoryForTest(0)

        assertTrue("net bar shown for the recents completion", sv.netBarVisibleForTest())
        assertTrue("https:// remains available as a full recents chip", "https://" in sv.netChipTextsForTest())
        assertEquals("the single glyphs stay in the grid", 2, sv.gridCellCountForTest())
    }

    @Test fun a_short_recent_completion_keeps_a_48dp_input_target() {
        val sv = SymbolsView(ctx)
        sv.recentProvider = { listOf("://") }
        sv.applyPalette(light)
        sv.openCategoryForTest(0)

        assertEquals(listOf("://"), sv.netChipTextsForTest())
        val minimum = (SymbolsView.MIN_KEY_TARGET_DP * density).toInt()
        assertTrue(sv.netChipMeasuredWidthsForTest().single() >= minimum)
        assertTrue(sv.netChipMeasuredHeightsForTest().single() >= minimum)
    }

    @Test fun non_net_tabs_keep_the_plain_grid_with_no_net_bar() {
        val sv = SymbolsView(ctx)
        sv.applyPalette(light)
        sv.openCategoryForTest(mathIndex)

        assertFalse("net bar hidden on 数学", sv.netBarVisibleForTest())
        assertTrue("数学 grid is populated", sv.gridCellCountForTest() > 0)
    }

    @Test fun leaving_a_recents_url_bar_for_net_hides_the_bar() {
        val sv = SymbolsView(ctx)
        sv.recentProvider = { listOf("https://", ".") }
        sv.applyPalette(light)
        sv.openCategoryForTest(0)
        assertTrue("recents holding a url shows the url bar", sv.netBarVisibleForTest())

        sv.openCategoryForTest(netIndex)
        assertFalse("网络 uses the merged grid, no separate url bar", sv.netBarVisibleForTest())
        assertTrue("http:// rides the 网络 grid", "http://" in sv.gridCellTextsForTest())
    }


    private fun textLeaves(v: View): List<TextView> = when (v) {
        is TextView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { textLeaves(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun clickByText(root: View, text: String): Boolean {
        var v: View? = textLeaves(root).firstOrNull { it.text == text } ?: return false
        while (v != null && !v.isClickable) v = v.parent as? View
        v?.performClick()
        return v != null
    }

    @Test fun chinese_marks_are_single_cell_grid_cells_with_no_chip_bar() {
        val sv = SymbolsView(ctx)
        sv.applyPalette(light)
        sv.openCategoryForTest(1)
        val cells = sv.gridCellTextsForTest()
        assertTrue("single — / … are grid cells", "—" in cells && "…" in cells)
        assertFalse("双破折号 —— dropped", "——" in cells)
        assertFalse("双省略号 …… dropped", "……" in cells)
        assertFalse("NO chip bar on 中文 (only single-cell marks)", sv.chipBarVisibleForTest())
        assertFalse("中文 is not a 网址补全 url bar", sv.netBarVisibleForTest())
    }

    @Test fun tapping_a_chinese_mark_cell_commits_that_exact_string() {
        val sv = SymbolsView(ctx)
        var committed: String? = null
        sv.onSymbol = { s, _ -> committed = s }
        sv.applyPalette(light)
        sv.openCategoryForTest(1)
        assertTrue("— cell present + clickable", clickByText(sv, "—"))
        assertEquals("clicking the — cell inserts the single em-dash", "—", committed)
        committed = null
        assertTrue("… cell present + clickable", clickByText(sv, "…"))
        assertEquals("clicking the … cell inserts the single ellipsis", "…", committed)
    }

    @Test fun common_tab_mixing_a_url_and_a_chinese_mark_chips_only_the_url() {
        val sv = SymbolsView(ctx)
        sv.recentProvider = { listOf("https://", "——", "。") }
        sv.applyPalette(light)
        sv.openCategoryForTest(0)
        assertEquals("only the url is chipped", listOf("https://"), sv.netChipTextsForTest())
        assertTrue("the url bar shows (a real url completion is present)", sv.netBarVisibleForTest())
        val cells = sv.gridCellTextsForTest()
        assertTrue("—— rides the grid, not the chip bar", "——" in cells)
        assertTrue("the single glyph 。 stays in the grid", "。" in cells)
        assertFalse("—— is NOT chipped", "——" in sv.netChipTextsForTest())
    }
}
