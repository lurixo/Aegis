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

/**
 * P5: the 网络 tab renders the multi-char URL completions (http:// https:// www. ://) as content-sized chips
 * in a 网址补全 bar — shown IN FULL, never truncated — above the single-glyph helpers, which still go through
 * the uniform 7-column grid. Every other tab keeps the plain grid with no net bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetCategoryLayoutTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val light = ImePalette.STATIC_LIGHT
    // +1 for the leading dynamic 常用 tab that sits before the static catalogue.
    private val netIndex = SymbolCatalog.categories.indexOfFirst { it.id == "net" } + 1
    private val mathIndex = SymbolCatalog.categories.indexOfFirst { it.id == "math" } + 1

    @Test fun net_completions_render_as_full_chips_and_glyphs_stay_in_the_grid() {
        val sv = SymbolsView(ctx)
        sv.applyPalette(light)
        sv.openCategoryForTest(netIndex)

        assertTrue("网址补全 bar is visible on the 网络 tab", sv.netBarVisibleForTest())
        val chips = sv.netChipTextsForTest()
        assertTrue("https:// is shown in full (not truncated)", "https://" in chips)
        assertEquals(
            listOf("://", "http://", "https://", "www.").sorted(),
            chips.sorted(),
        )
        // the 11 single-glyph helpers (. / @ - _ : # ? & = %) still render through the uniform grid path
        assertEquals(11, sv.gridCellCountForTest())
    }

    @Test fun a_completion_surfaced_in_recents_is_chipped_not_truncated_in_the_grid() {
        // The 常用 tab renders recents; a URL completion that was used before must also render as a full chip,
        // not as a clipped glyph cell — otherwise the P5 fix would be incomplete for 常用.
        val sv = SymbolsView(ctx)
        sv.recentProvider = { listOf("https://", ".", "。") }
        sv.applyPalette(light)
        sv.openCategoryForTest(0) // 常用

        assertTrue("net bar shown for the recents completion", sv.netBarVisibleForTest())
        assertTrue("https:// chipped in 常用", "https://" in sv.netChipTextsForTest())
        assertEquals("the single glyphs stay in the grid", 2, sv.gridCellCountForTest())
    }

    @Test fun non_net_tabs_keep_the_plain_grid_with_no_net_bar() {
        val sv = SymbolsView(ctx)
        sv.applyPalette(light)
        sv.openCategoryForTest(mathIndex)

        assertFalse("net bar hidden on 数学", sv.netBarVisibleForTest())
        assertTrue("数学 grid is populated", sv.gridCellCountForTest() > 0)
    }

    @Test fun leaving_the_net_tab_hides_the_net_bar() {
        val sv = SymbolsView(ctx)
        sv.applyPalette(light)
        sv.openCategoryForTest(netIndex)
        assertTrue(sv.netBarVisibleForTest())

        sv.openCategoryForTest(1) // 中文
        assertFalse("net bar hidden after leaving 网络", sv.netBarVisibleForTest())
        assertTrue("中文 grid populated", sv.gridCellCountForTest() > 0)
    }

    // ---- debug.17 A: 中文 破折号 —— / 省略号 …… render as ORDINARY EQUAL-WIDTH GRID CELLS (in their natural
    //      position), NOT wide chips that break the 7-column grid, and never a 网址补全 url bar ----

    private fun textLeaves(v: View): List<TextView> = when (v) {
        is TextView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { textLeaves(v.getChildAt(it)) }
        else -> emptyList()
    }

    /** Click the cell/chip whose text == [text] by walking up from the leaf to the first clickable ancestor
     *  (a grid cell's click sits on the tile, not its TextView). */
    private fun clickByText(root: View, text: String): Boolean {
        var v: View? = textLeaves(root).firstOrNull { it.text == text } ?: return false
        while (v != null && !v.isClickable) v = v.parent as? View
        v?.performClick()
        return v != null
    }

    @Test fun chinese_double_dash_and_ellipsis_are_ordinary_grid_cells_not_a_chip_bar() {
        val sv = SymbolsView(ctx)
        sv.applyPalette(light)
        sv.openCategoryForTest(1) // 中文
        // debug.17 A: the multi-char marks ride the GRID as equal-width cells (text auto-shrinks to fit), in
        // their natural catalogue position — NOT a wide chip tile, NOT a chip bar.
        val cells = sv.gridCellTextsForTest()
        assertTrue("中文 破折号 —— is a grid cell", "——" in cells)
        assertTrue("中文 省略号 …… is a grid cell", "……" in cells)
        assertTrue("single — / … still present as grid cells", "—" in cells && "…" in cells)
        assertFalse("NO chip bar on 中文 (the marks ride the grid now)", sv.chipBarVisibleForTest())
        assertFalse("中文 is not a 网址补全 url bar", sv.netBarVisibleForTest())
    }

    @Test fun tapping_a_chinese_mark_cell_commits_that_exact_string() {
        val sv = SymbolsView(ctx)
        var committed: String? = null
        sv.onSymbol = { committed = it }
        sv.applyPalette(light)
        sv.openCategoryForTest(1) // 中文
        assertTrue("—— cell present + clickable", clickByText(sv, "——"))
        assertEquals("clicking the —— cell inserts the double em-dash", "——", committed)
        committed = null
        assertTrue("…… cell present + clickable", clickByText(sv, "……"))
        assertEquals("clicking the …… cell inserts the double ellipsis", "……", committed)
    }

    @Test fun common_tab_mixing_a_url_and_a_chinese_mark_chips_only_the_url() {
        // debug.17 A (an edge case): 常用 recents holding BOTH a url completion AND a 中文 mark must chip
        // ONLY the url — the —— rides the grid as an ordinary cell, never re-promoted to a wide chip.
        val sv = SymbolsView(ctx)
        sv.recentProvider = { listOf("https://", "——", "。") }
        sv.applyPalette(light)
        sv.openCategoryForTest(0) // 常用
        assertEquals("only the url is chipped", listOf("https://"), sv.netChipTextsForTest())
        assertTrue("the url bar shows (a real url completion is present)", sv.netBarVisibleForTest())
        val cells = sv.gridCellTextsForTest()
        assertTrue("—— rides the grid, not the chip bar", "——" in cells)
        assertTrue("the single glyph 。 stays in the grid", "。" in cells)
        assertFalse("—— is NOT chipped", "——" in sv.netChipTextsForTest())
    }
}
