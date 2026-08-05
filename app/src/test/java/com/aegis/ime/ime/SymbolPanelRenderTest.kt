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

import android.util.TypedValue
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SymbolPanelRenderTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val light = ImePalette.STATIC_LIGHT
    private fun idx(id: String) = SymbolCatalog.categories.indexOfFirst { it.id == id } + 1

    private fun overlaps(): Map<String, List<String>> {
        val where = LinkedHashMap<String, MutableList<String>>()
        for (c in SymbolCatalog.categories) for (s in c.symbols) where.getOrPut(s) { mutableListOf() }.add(c.id)
        return where.filterValues { it.size > 1 }
    }


    @Test fun common_badge_shows_the_recorded_true_origin() {
        val origin = mapOf("$" to "currency", "℃" to "supsub", "π" to "greek")
        val sv = SymbolsView(ctx).apply {
            recentProvider = { listOf("$", "℃", "π") }
            recentOriginOf = { origin[it] }
            applyPalette(light)
            openCategoryForTest(0)
        }
        assertEquals("C", sv.gridBadgeForTest("$"))
        assertEquals("S", sv.gridBadgeForTest("℃"))
        assertEquals("G", sv.gridBadgeForTest("π"))
    }

    @Test fun common_badge_falls_back_to_first_category_when_origin_missing() {
        val sv = SymbolsView(ctx).apply {
            recentProvider = { listOf("$", "℃", "π") }
            applyPalette(light)
            openCategoryForTest(0)
        }
        assertEquals("E", sv.gridBadgeForTest("$"))
        assertEquals("M", sv.gridBadgeForTest("℃"))
        assertEquals("M", sv.gridBadgeForTest("π"))
    }

    @Test fun tapping_a_symbol_reports_the_tab_it_came_from() {
        var got: Pair<String, String?>? = null
        val sv = SymbolsView(ctx).apply {
            onSymbol = { s, o -> got = s to o }
            applyPalette(light)
        }
        sv.openCategoryForTest(idx("currency"))
        assertTrue(sv.tapCellForTest("$"))
        assertEquals("$" to "currency", got)

        sv.openCategoryForTest(idx("math"))
        assertTrue(sv.tapCellForTest("π"))
        assertEquals("π" to "math", got)
    }

    @Test fun every_overlapping_symbol_badges_its_recorded_origin_not_the_first_category() {
        val overlaps = overlaps()
        val originOf = overlaps.mapValues { it.value.last() }
        val sv = SymbolsView(ctx).apply {
            recentProvider = { overlaps.keys.toList() }
            recentOriginOf = { originOf[it] }
            applyPalette(light)
            openCategoryForTest(0)
        }
        for ((sym, cats) in overlaps) {
            val expected = ctx.getString(SymbolCatalog.titleResOf(cats.last())!!).take(1)
            assertEquals("badge of $sym must show its recorded origin ${cats.last()}", expected, sv.gridBadgeForTest(sym))
            assertTrue("test setup: chosen origin is genuinely non-first for $sym", cats.last() != cats.first())
        }
        assertEquals("every multi-category symbol covered", 42, overlaps.size)
    }

    @Test fun tapping_a_recent_symbol_preserves_its_stored_origin() {
        var got: Pair<String, String?>? = null
        val sv = SymbolsView(ctx).apply {
            recentProvider = { listOf("$") }
            recentOriginOf = { if (it == "$") "currency" else null }
            onSymbol = { s, o -> got = s to o }
            applyPalette(light)
            openCategoryForTest(0)
        }
        assertTrue(sv.tapCellForTest("$"))
        assertEquals("re-using a recent symbol keeps its real origin, not the common tab", "$" to "currency", got)
    }


    @Test fun every_cell_in_every_tab_shares_the_one_fixed_height() {
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
        val metrics = ctx.resources.displayMetrics
        val full = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ImeType.display, metrics)
        val shrunk = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            ImeType.display * SymbolsView.WIDE_GLYPH_SCALE,
            metrics,
        )
        for ((ci, cat) in SymbolCatalog.categories.withIndex()) {
            val sv = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(ci + 1) }
            for (sym in cat.symbols) {
                if (sym.length != 1) continue
                val tv = sv.gridGlyphForTest(sym) ?: continue
                val expected = if (SymbolsView.wideMetricGlyph(sym[0])) shrunk else full
                assertEquals("size of $sym in ${cat.id}", expected, tv.textSize, 0.5f)
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
        val disp = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            ImeType.display,
            ctx.resources.displayMetrics,
        )
        assertEquals(disp, greek.gridGlyphForTest("π")!!.textSize, 0.5f)
        assertEquals(disp, greek.gridGlyphForTest("Ω")!!.textSize, 0.5f)

        val net = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("net")) }
        assertFalse("网络 no longer uses a separate url chip bar", net.netBarVisibleForTest())
        assertTrue("https:// rides the merged grid", "https://" in net.gridCellTextsForTest())
        assertTrue("网络 cells stay uniform height", net.gridTileHeightsForTest().all { it == net.cellHeightForTest() })
    }

    @Test fun recents_url_chips_share_the_one_fixed_cell_height() {
        val sv = SymbolsView(ctx).apply {
            recentProvider = { listOf("https://", ".", "。") }
            applyPalette(light)
            openCategoryForTest(0)
        }
        val chipHeights = sv.netChipMeasuredHeightsForTest()
        assertTrue("recents surfaced a url chip", chipHeights.isNotEmpty())
        assertTrue(
            "multi-char url chips are no taller than a single-char cell: $chipHeights vs ${sv.cellHeightForTest()}",
            chipHeights.all { it == sv.cellHeightForTest() },
        )
    }

    @Test fun wide_metric_classifier_covers_exactly_the_heavy_fallback_glyphs() {
        for (s in listOf("㎏", "㎝", "㎡", "㈠", "㈩", "℃", "℉", "ℝ", "ℕ", "ℤ", "ℚ", "ℂ")) {
            assertTrue("$s is a wide fallback glyph", SymbolsView.wideMetricGlyph(s[0]))
        }
        for (s in listOf("+", "−", "×", "π", "α", "A", "Ⅰ", "①", "½", "²")) {
            assertFalse("$s is not a wide fallback glyph", SymbolsView.wideMetricGlyph(s[0]))
        }
    }

    @Test fun old_format_recent_without_origin_does_not_crash_the_badge() {
        val sv = SymbolsView(ctx).apply {
            recentProvider = { listOf("★") }
            applyPalette(light)
            openCategoryForTest(0)
        }
        assertNull("no category, no badge — but no crash", sv.gridBadgeForTest("★"))
    }
}
