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
import androidx.core.widget.TextViewCompat
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.layout.EmojiCatalog
import com.aegis.ime.layout.SymbolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanelRecyclingTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val light = ImePalette.STATIC_LIGHT
    private val metrics = ctx.resources.displayMetrics


    @Test fun emoji_grid_allocates_at_peak_not_sum_across_a_full_sweep() {
        val v = EmojiView(ctx).apply { applyPalette(light) }
        for (i in 0..EmojiCatalog.categories.size) v.openCategoryForTest(i)
        val afterSweep1 = v.emojiCellsAllocatedForTest()
        for (i in 0..EmojiCatalog.categories.size) v.openCategoryForTest(i)
        assertEquals("a second full sweep must allocate zero new cells", afterSweep1, v.emojiCellsAllocatedForTest())
        val peak = EmojiCatalog.categories.maxOf { it.emoji.size }
        val total = EmojiCatalog.categories.sumOf { it.emoji.size }
        assertEquals("the pool tops out at the largest category", peak, afterSweep1)
        assertTrue("recycling beats the old per-sweep total ($total) of cell allocations", peak < total)
    }

    @Test fun emoji_grid_content_and_tap_are_correct_after_recycling() {
        var picked = ""
        val v = EmojiView(ctx).apply { applyPalette(light); onEmoji = { picked = it } }
        val cat1 = EmojiCatalog.categories[0].emoji
        val cat2 = EmojiCatalog.categories[1].emoji
        v.openCategoryForTest(2)
        v.openCategoryForTest(1)
        assertEquals("recycled grid shows category 1's glyphs", cat1, v.gridCellTextsForTest())
        v.tapCellForTest(0)
        assertEquals("tapping a recycled cell emits its current glyph", cat1[0], picked)
        v.openCategoryForTest(2)
        assertEquals("recycled grid rebinds to category 2's glyphs", cat2, v.gridCellTextsForTest())
    }


    private fun idx(id: String) = SymbolCatalog.categories.indexOfFirst { it.id == id } + 1

    @Test fun symbol_grid_allocates_at_peak_not_sum_across_a_full_sweep() {
        val v = SymbolsView(ctx).apply { applyPalette(light) }
        for (i in 1..SymbolCatalog.categories.size) v.openCategoryForTest(i)
        val afterSweep1 = v.tilesAllocatedForTest()
        for (i in 1..SymbolCatalog.categories.size) v.openCategoryForTest(i)
        assertEquals("a second full sweep must allocate zero new tiles", afterSweep1, v.tilesAllocatedForTest())
        val totalGridGlyphs = SymbolCatalog.categories.sumOf { c -> c.symbols.count { it.length == 1 } }
        assertTrue("the pool caps well below the old per-sweep total ($totalGridGlyphs)", afterSweep1 < totalGridGlyphs)
    }

    @Test fun reused_symbol_tile_toggles_autosize_off_for_a_fixed_wide_glyph() {
        var recent = listOf("arcsin")
        val v = SymbolsView(ctx).apply { recentProvider = { recent }; applyPalette(light) }
        v.openCategoryForTest(0)
        val multi = v.gridGlyphForTest("arcsin")!!
        assertEquals(
            "multi-char token uses auto-size",
            TextViewCompat.AUTO_SIZE_TEXT_TYPE_UNIFORM,
            TextViewCompat.getAutoSizeTextType(multi),
        )
        recent = listOf("℃")
        v.openCategoryForTest(0)
        val wide = v.gridGlyphForTest("℃")!!
        assertEquals(
            "reused tile turns auto-size back off for a single glyph",
            TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE,
            TextViewCompat.getAutoSizeTextType(wide),
        )
        val expected = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            ImeType.display * SymbolsView.WIDE_GLYPH_SCALE,
            metrics,
        )
        assertEquals("wide fallback glyph keeps its one-step-smaller fixed size after reuse", expected, wide.textSize, 0.5f)
    }

    @Test fun reused_symbol_tile_hides_the_badge_when_moving_off_the_recent_tab() {
        val recent = listOf("$")
        val v = SymbolsView(ctx).apply {
            recentProvider = { recent }
            recentOriginOf = { if (it == "$") "货币" else null }
            applyPalette(light)
        }
        v.openCategoryForTest(0)
        assertEquals("货", v.gridBadgeForTest("$"))
        val catId = SymbolCatalog.categories[0].id
        val firstCatSymbol = SymbolCatalog.categories[0].symbols.first { it.length == 1 }
        v.openCategoryForTest(idx(catId))
        assertNull("a recycled tile must hide its badge on a non-recent tab", v.gridBadgeForTest(firstCatSymbol))
    }

    @Test fun symbol_tap_reports_the_origin_after_recycling() {
        var tappedOrigin: String? = "unset"
        val v = SymbolsView(ctx).apply { applyPalette(light); onSymbol = { _, origin -> tappedOrigin = origin } }
        val catNo = SymbolCatalog.categories.indexOfFirst { it.id != "net" && it.symbols.any { s -> s.length == 1 } }
        v.openCategoryForTest(idx("net"))
        v.openCategoryForTest(catNo + 1)
        val sym = SymbolCatalog.categories[catNo].symbols.first { it.length == 1 }
        assertTrue(v.tapCellForTest(sym))
        assertEquals(
            "a recycled tile reports its current tab as the tap origin",
            SymbolCatalog.categories[catNo].title,
            tappedOrigin,
        )
    }


    @Test fun clipboard_tab_switch_fades_only_on_a_real_tab_change() {
        val v = ClipboardView(ctx).apply {
            historyProvider = { listOf("clip-a", "clip-b") }
            categoriesProvider = { listOf("默认") }
            phrasesInProvider = { c -> if (c == "默认") listOf("phrase-a") else emptyList() }
            applyPalette(light)
            refresh()
        }
        val t0 = v.tabTransitionsForTest()
        v.refresh()
        assertEquals("a plain refresh must not fade", t0, v.tabTransitionsForTest())
        v.switchTabForTest(toClipboard = false)
        assertEquals("a real tab switch fades once", t0 + 1, v.tabTransitionsForTest())
        v.switchTabForTest(toClipboard = false)
        assertEquals("re-selecting the current tab must not fade", t0 + 1, v.tabTransitionsForTest())
        v.switchTabForTest(toClipboard = true)
        assertEquals("switching back fades once more", t0 + 2, v.tabTransitionsForTest())
    }
}
