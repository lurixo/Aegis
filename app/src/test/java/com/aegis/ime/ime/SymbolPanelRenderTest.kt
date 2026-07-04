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

import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.layout.SymbolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Symbols panel (D) rendering: the 常用 badge shows a recorded true origin (item ①), taps report the origin
 * they came from (item ①), and every rounded symbol cell shares one fixed height with wide fallback glyphs
 * drawn a step smaller so none looks taller or bolder than its neighbours (item ③).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SymbolPanelRenderTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val light = ImePalette.STATIC_LIGHT
    private fun idx(id: String) = SymbolCatalog.categories.indexOfFirst { it.id == id } + 1

    /** Every symbol that appears in more than one category, mapped to the categories (in declared order). */
    private fun overlaps(): Map<String, List<String>> {
        val where = LinkedHashMap<String, MutableList<String>>()
        for (c in SymbolCatalog.categories) for (s in c.symbols) where.getOrPut(s) { mutableListOf() }.add(c.title)
        return where.filterValues { it.size > 1 }
    }

    // --- item ①: badge shows the true origin ---

    @Test fun common_badge_shows_the_recorded_true_origin() {
        val origin = mapOf("$" to "货币", "℃" to "角标", "π" to "希腊")
        val sv = SymbolsView(ctx).apply {
            recentProvider = { listOf("$", "℃", "π") }
            recentOriginOf = { origin[it] }
            applyPalette(light)
            openCategoryForTest(0)
        }
        // $ tapped from currency, ℃ from super/subscripts, π from greek — the badge is the REAL source, not the
        // first catalogue category (which would wrongly read 英 / 数 / 数).
        assertEquals("货", sv.gridBadgeForTest("$"))
        assertEquals("角", sv.gridBadgeForTest("℃"))
        assertEquals("希", sv.gridBadgeForTest("π"))
    }

    @Test fun common_badge_falls_back_to_first_category_when_origin_missing() {
        // A recent list with NO recorded origin (older file) must still badge every entry and never crash.
        val sv = SymbolsView(ctx).apply {
            recentProvider = { listOf("$", "℃", "π") }
            applyPalette(light)
            openCategoryForTest(0)
        }
        assertEquals("英", sv.gridBadgeForTest("$"))
        assertEquals("数", sv.gridBadgeForTest("℃"))
        assertEquals("数", sv.gridBadgeForTest("π"))
    }

    @Test fun tapping_a_symbol_reports_the_tab_it_came_from() {
        var got: Pair<String, String?>? = null
        val sv = SymbolsView(ctx).apply {
            onSymbol = { s, o -> got = s to o }
            applyPalette(light)
        }
        sv.openCategoryForTest(idx("currency"))
        assertTrue(sv.tapCellForTest("$"))
        assertEquals("$" to "货币", got)

        sv.openCategoryForTest(idx("math"))
        assertTrue(sv.tapCellForTest("π"))
        assertEquals("π" to "数学", got)
    }

    @Test fun every_overlapping_symbol_badges_its_recorded_origin_not_the_first_category() {
        // Exhaustive: give EACH multi-category symbol its LAST category as the recorded origin (never the
        // first). A first-category fallback would badge a different character, so any mis-attribution fails.
        val overlaps = overlaps()
        val originOf = overlaps.mapValues { it.value.last() }
        val sv = SymbolsView(ctx).apply {
            recentProvider = { overlaps.keys.toList() }
            recentOriginOf = { originOf[it] }
            applyPalette(light)
            openCategoryForTest(0)
        }
        for ((sym, cats) in overlaps) {
            val expected = cats.last().take(1)
            assertEquals("badge of $sym must show its recorded origin ${cats.last()}", expected, sv.gridBadgeForTest(sym))
            assertTrue("test setup: chosen origin is genuinely non-first for $sym", expected != cats.first().take(1))
        }
        assertEquals("every multi-category symbol covered", 39, overlaps.size)
    }

    @Test fun tapping_a_recent_symbol_preserves_its_stored_origin() {
        var got: Pair<String, String?>? = null
        val sv = SymbolsView(ctx).apply {
            recentProvider = { listOf("$") }
            recentOriginOf = { if (it == "$") "货币" else null }
            onSymbol = { s, o -> got = s to o }
            applyPalette(light)
            openCategoryForTest(0)
        }
        assertTrue(sv.tapCellForTest("$"))
        assertEquals("re-using a recent symbol keeps its real origin, not 常用", "$" to "货币", got)
    }

    // --- item ③: uniform cell height + suppressed fallback weight ---

    @Test fun every_cell_in_every_tab_shares_the_one_fixed_height() {
        // The 常用 tab (with a mixed wide/narrow recent list) plus all 11 catalogue tabs — no sampling. A
        // single glyph left at WRAP_CONTENT would give a taller rounded cell and fail here.
        val recentMix = listOf("㎏", "+", "℃", "π", "，", "①")
        for (index in 0..SymbolCatalog.categories.size) {
            val sv = SymbolsView(ctx).apply {
                recentProvider = { recentMix }
                applyPalette(light)
                openCategoryForTest(index)
            }
            val heights = sv.gridTileHeightsForTest()
            assertTrue("tab $index populated", heights.isNotEmpty())
            assertTrue("tab $index: every rounded cell equals the one fixed height",
                heights.all { it == sv.cellHeightForTest() })
        }
    }

    @Test fun every_single_char_cell_is_sized_by_its_glyph_class_in_every_tab() {
        // Exhaustive weight/size check: every single-glyph cell in every tab renders at exactly the full
        // display size, OR the uniform wide-glyph size when (and only when) it is a heavy fallback glyph.
        // No glyph is left individually larger; the shrink is applied wherever the class demands and nowhere
        // else. (Multi-char completions auto-size and are excluded.)
        val scaled = ctx.resources.displayMetrics.scaledDensity
        val full = ImeType.display * scaled
        val shrunk = ImeType.display * SymbolsView.WIDE_GLYPH_SCALE * scaled
        for ((ci, cat) in SymbolCatalog.categories.withIndex()) {
            val sv = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(ci + 1) }
            for (sym in cat.symbols) {
                if (sym.length != 1) continue
                val tv = sv.gridGlyphForTest(sym) ?: continue
                val expected = if (SymbolsView.wideMetricGlyph(sym[0])) shrunk else full
                assertEquals("size of $sym in ${cat.title}", expected, tv.textSize, 0.5f)
            }
        }
    }

    @Test fun wide_fallback_glyphs_are_drawn_smaller_than_latin_tiles() {
        val sv = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("math")) }
        val plus = sv.gridGlyphForTest("+")!!.textSize
        for (wide in listOf("㎏", "㎡", "℃", "ℝ", "ℕ")) {
            val ts = sv.gridGlyphForTest(wide)!!.textSize
            assertTrue("$wide should render smaller than +", ts < plus)
        }
        // an ordinary math operator is NOT shrunk
        assertEquals(plus, sv.gridGlyphForTest("−")!!.textSize, 0.01f)
    }

    @Test fun glyph_cells_disable_font_padding() {
        val sv = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("math")) }
        assertFalse("+ disables font padding", sv.gridGlyphForTest("+")!!.includeFontPadding)
        assertFalse("㎏ disables font padding", sv.gridGlyphForTest("㎏")!!.includeFontPadding)
    }

    @Test
    @Config(sdk = [34], qualifiers = "xxhdpi")
    fun uniform_height_and_wide_shrink_hold_at_high_density() {
        val sv = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("math")) }
        assertTrue("cells stay uniform at xxhdpi", sv.gridTileHeightsForTest().all { it == sv.cellHeightForTest() })
        assertTrue("the fixed cell height scales up with density", sv.cellHeightForTest() > 44)
        assertTrue("wide glyphs stay smaller at xxhdpi too",
            sv.gridGlyphForTest("㎏")!!.textSize < sv.gridGlyphForTest("+")!!.textSize)
    }

    @Test fun greek_and_net_tabs_are_not_regressed() {
        val greek = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("greek")) }
        assertTrue("希腊 cells stay uniform", greek.gridTileHeightsForTest().all { it == greek.cellHeightForTest() })
        // Greek letters are not in the wide-glyph set, so they are drawn at the full display size.
        val disp = ImeType.display * ctx.resources.displayMetrics.scaledDensity
        assertEquals(disp, greek.gridGlyphForTest("π")!!.textSize, 0.5f)
        assertEquals(disp, greek.gridGlyphForTest("Ω")!!.textSize, 0.5f)

        val net = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("net")) }
        assertTrue("网络 still shows its url chip bar", net.netBarVisibleForTest())
        assertTrue("https:// chip still present", "https://" in net.netChipTextsForTest())
    }

    @Test fun wide_metric_classifier_covers_exactly_the_heavy_fallback_glyphs() {
        for (s in listOf("㎏", "㎝", "㎡", "㈠", "㈩", "℃", "℉", "ℝ", "ℕ", "ℤ", "ℚ", "ℂ")) {
            assertTrue("$s is a wide fallback glyph", SymbolsView.wideMetricGlyph(s[0]))
        }
        // ordinary Latin / Greek / operators / roman numerals / circled digits stay full size
        for (s in listOf("+", "−", "×", "π", "α", "A", "Ⅰ", "①", "½", "²")) {
            assertFalse("$s is not a wide fallback glyph", SymbolsView.wideMetricGlyph(s[0]))
        }
    }

    @Test fun old_format_recent_without_origin_does_not_crash_the_badge() {
        val sv = SymbolsView(ctx).apply {
            recentProvider = { listOf("★") }   // a glyph not in any static category
            applyPalette(light)
            openCategoryForTest(0)
        }
        assertNull("no category, no badge — but no crash", sv.gridBadgeForTest("★"))
    }
}
