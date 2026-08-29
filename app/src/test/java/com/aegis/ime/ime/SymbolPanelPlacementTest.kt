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

import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.SymbolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SymbolPanelPlacementTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val light = ImePalette.STATIC_LIGHT
    private fun idx(id: String) = SymbolCatalog.categories.indexOfFirst { it.id == id } + 1

    private fun layout(v: View) {
        val w = ctx.resources.displayMetrics.widthPixels
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun drawnInkCenter(tv: TextView): Pair<Float, Float> {
        val sym = tv.text.toString()
        val ink = Rect()
        tv.paint.getTextBounds(sym, 0, sym.length, ink)
        val fm = tv.paint.fontMetrics
        val advance = tv.paint.measureText(sym)
        val lineLeft = (tv.width - advance) / 2f
        val baseline = tv.height / 2f - (fm.ascent + fm.descent) / 2f
        return (lineLeft + ink.exactCenterX() + tv.translationX) to
            (baseline + ink.exactCenterY() + tv.translationY)
    }

    private fun realVerticalCenter(tv: TextView): Float {
        val ink = Rect()
        val sym = tv.text.toString()
        tv.paint.getTextBounds(sym, 0, sym.length, ink)
        val fm = tv.paint.fontMetrics
        return tv.height / 2f + (ink.exactCenterY() - (fm.ascent + fm.descent) / 2f)
    }

    private fun faceBoundsInRoot(root: ViewGroup, v: View): RectF {
        val outer = Rect(0, 0, v.width, v.height)
        root.offsetDescendantRectToMyCoords(v, outer)
        return (v.background as ImeKeySurface).faceBoundsForTest(v.width, v.height).apply {
            offset(outer.left.toFloat(), outer.top.toFloat())
        }
    }

    @Test fun superscripts_land_high_and_subscripts_land_low() {
        val sv = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("supsub")) }
        layout(sv)
        val sup = sv.gridGlyphForTest("²") ?: throw AssertionError("² missing from the 角标 tab")
        val sub = sv.gridGlyphForTest("₂") ?: throw AssertionError("₂ missing from the 角标 tab")
        val (supCx, supCy) = drawnInkCenter(sup)
        val (subCx, subCy) = drawnInkCenter(sub)

        assertEquals("² keeps its real vertical position", realVerticalCenter(sup), supCy, 0.75f)
        assertEquals("₂ keeps its real vertical position", realVerticalCenter(sub), subCy, 0.75f)
        assertEquals("² stays horizontally ink-centred", sup.width / 2f, supCx, 1.5f)
        assertEquals("₂ stays horizontally ink-centred", sub.width / 2f, subCx, 1.5f)

        assertTrue("² sits above the tile centre (cy=$supCy)", supCy < sup.height / 2f - 1.5f)
        assertTrue("₂ sits below the tile centre (cy=$subCy)", subCy > sub.height / 2f + 1.5f)
        assertTrue("a superscript and a subscript never share one height", subCy - supCy > 3f)
    }

    @Test fun english_punctuation_drops_low_while_quotes_rise() {
        val sv = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("en")) }
        layout(sv)
        for (low in listOf(",", ".")) {
            val tv = sv.gridGlyphForTest(low) ?: throw AssertionError("$low missing from the 英文 tab")
            val (cx, cy) = drawnInkCenter(tv)
            assertEquals("$low keeps its real vertical position", realVerticalCenter(tv), cy, 0.75f)
            assertEquals("$low stays horizontally ink-centred", tv.width / 2f, cx, 1.5f)
            assertTrue("$low sits below the tile centre (cy=$cy)", cy > tv.height / 2f + 1.5f)
        }
        val quote = sv.gridGlyphForTest("'") ?: throw AssertionError("' missing from the 英文 tab")
        val (_, quoteCy) = drawnInkCenter(quote)
        assertEquals("' keeps its real vertical position", realVerticalCenter(quote), quoteCy, 0.75f)
        assertTrue("' rides high in the em-box (cy=$quoteCy)", quoteCy < quote.height / 2f - 1.5f)
    }

    @Test fun representative_glyphs_across_categories_use_real_vertical_placement() {
        val cases = mapOf(
            "currency" to "$",
            "arrow" to "→",
            "greek" to "π",
            "ordinal" to "①",
            "pinyin" to "ā",
            "math" to "=",
        )
        for ((cat, sym) in cases) {
            val sv = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx(cat)) }
            layout(sv)
            val tv = sv.gridGlyphForTest(sym) ?: throw AssertionError("$sym missing from the $cat tab")
            val (cx, cy) = drawnInkCenter(tv)
            assertEquals("$sym in $cat sits at its real vertical em-box position", realVerticalCenter(tv), cy, 0.75f)
            assertEquals("$sym in $cat stays horizontally ink-centred", tv.width / 2f, cx, 1.5f)
        }
    }

    @Test fun curated_cjk_marks_keep_their_mirrored_em_box_positions() {
        val sv = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("zh")) }
        layout(sv)
        for (lowerLeft in listOf("，", "。", "、")) {
            val tv = sv.gridGlyphForTest(lowerLeft) ?: throw AssertionError("$lowerLeft missing from the 中文 tab")
            val (cx, cy) = drawnInkCenter(tv)
            assertTrue("$lowerLeft stays left of the tile centre", cx < tv.width / 2f - 1f)
            assertTrue("$lowerLeft stays below the tile centre", cy > tv.height / 2f + 1f)
        }
        val open = sv.gridGlyphForTest("『") ?: throw AssertionError("『 missing from the 中文 tab")
        val (ox, oy) = drawnInkCenter(open)
        assertTrue("『 stays right of the tile centre", ox > open.width / 2f + 1f)
        assertTrue("『 stays above the tile centre", oy < open.height / 2f - 1f)
    }

    private fun boundsInRoot(root: View, view: View): Rect {
        val out = Rect(0, 0, view.width, view.height)
        var node: View = view
        while (node !== root) {
            out.offset(node.left, node.top)
            node = node.parent as View
        }
        return out
    }

    @Test fun symbols_categories_run_under_the_grid_across_the_whole_panel() {
        val sv = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("en")) }
        layout(sv)

        val bar = boundsInRoot(sv, sv.categoryBarForTest())
        val grid = boundsInRoot(sv, sv.gridViewportForTest())
        val actions = boundsInRoot(sv, sv.actionColumnForTest())

        assertEquals("the category bar starts where the grid and the action column end", grid.bottom, bar.top)
        assertEquals("the action column ends on the category bar too", actions.bottom, bar.top)
        assertEquals("the category bar starts at the panel edge", 0, bar.left)
        assertEquals("the category bar runs to the far edge", sv.width, bar.right)
        assertEquals("the category bar takes the rest of the panel", sv.height, bar.bottom)

        val tab0 = boundsInRoot(sv, sv.railTabForTest(0))
        val tab1 = boundsInRoot(sv, sv.railTabForTest(1))
        assertEquals("常用 leads the bar", 0, tab0.left)
        assertEquals("the next category follows it directly", tab0.right, tab1.left)
        assertEquals("a tab fills the bar height", bar.height(), tab0.height())
    }

    @Test fun emoji_categories_run_under_the_grid_across_the_whole_panel() {
        val ev = EmojiView(ctx).apply { applyPalette(light); openCategoryForTest(1) }
        layout(ev)

        val bar = boundsInRoot(ev, ev.categoryBarForTest())
        val grid = boundsInRoot(ev, ev.gridViewportForTest())
        val actions = boundsInRoot(ev, ev.actionColumnForTest())

        assertEquals("the category bar starts where the grid and the action column end", grid.bottom, bar.top)
        assertEquals("the action column ends on the category bar too", actions.bottom, bar.top)
        assertEquals("the category bar starts at the panel edge", 0, bar.left)
        assertEquals("the category bar runs to the far edge", ev.width, bar.right)
        assertEquals("the category bar takes the rest of the panel", ev.height, bar.bottom)

        val tab0 = boundsInRoot(ev, ev.railTabForTest(0))
        val tab1 = boundsInRoot(ev, ev.railTabForTest(1))
        assertEquals("常用 leads the bar", 0, tab0.left)
        assertEquals("the next category follows it directly", tab0.right, tab1.left)
        assertEquals("a tab fills the bar height", bar.height(), tab0.height())
    }

    private fun measureAt(v: View, heightPx: Int) = measureAt(v, ctx.resources.displayMetrics.widthPixels, heightPx)

    private fun measureAt(v: View, widthPx: Int, heightPx: Int) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    @Test fun the_panel_width_splits_into_five_equal_columns() {
        val density = ctx.resources.displayMetrics.density
        for (widthDp in listOf(320, 360, 411, 480)) {
            val width = (widthDp * density).toInt()
            val sv = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("en")) }
            val ev = EmojiView(ctx).apply { applyPalette(light); openCategoryForTest(1) }
            measureAt(sv, width, 900)
            measureAt(ev, width, 900)
            val symbolCell = sv.gridCellPixelWidthForTest(SymbolCatalog.categories[idx("en") - 1].symbols.first())
            val emojiCell = requireNotNull(ev.gridCellForTest(0)).width

            assertEquals("a symbol column is a fifth of $widthDp dp", width / 5, symbolCell)
            assertEquals("an emoji column is a fifth of $widthDp dp", width / 5, emojiCell)
            assertEquals("the symbol actions take the fifth column at $widthDp dp", width - 4 * symbolCell, sv.actionColumnForTest().width)
            assertEquals("the emoji actions take the fifth column at $widthDp dp", width - 4 * emojiCell, ev.actionColumnForTest().width)
        }
    }

    @Test fun the_symbol_grid_is_four_columns_by_four_rows() {
        val sv = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("en")) }
        layout(sv)
        val grid = sv.gridViewportForTest()

        assertEquals("four columns", 4, sv.gridColumnCountForTest())
        assertEquals("four rows", 4, SymbolsView.ROWS)
        assertEquals("the viewport holds exactly four rows", 4 * sv.cellHeightForTest(), grid.height)
        assertEquals(
            "the four columns tile the viewport with no leftover strip",
            grid.width,
            4 * requireNotNull(sv.gridCellForTest(sv.gridCellTextsForTest().first())).width,
        )
    }

    @Test fun the_emoji_grid_is_four_columns_by_four_rows() {
        val ev = EmojiView(ctx).apply { applyPalette(light); openCategoryForTest(1) }
        layout(ev)
        val grid = ev.gridViewportForTest()

        assertEquals("four columns", 4, ev.gridColumnCountForTest())
        assertEquals("four rows", 4, EmojiView.ROWS)
        assertEquals("the viewport holds exactly four rows", 4 * ev.cellHeightForTest(), grid.height)
        assertEquals(
            "the four columns tile the viewport with no leftover strip",
            grid.width,
            4 * requireNotNull(ev.gridCellForTest(0)).width,
        )
    }

    @Test fun the_panel_height_splits_into_five_equal_rows() {
        for (height in listOf(288, 755, 900)) {
            for (panel in listOf<View>(
                SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("en")) },
                EmojiView(ctx).apply { applyPalette(light); openCategoryForTest(1) },
            )) {
                measureAt(panel, height)
                val (row, grid, bar) = when (panel) {
                    is SymbolsView -> Triple(panel.cellHeightForTest(), panel.gridViewportForTest(), panel.categoryBarForTest())
                    is EmojiView -> Triple(panel.cellHeightForTest(), panel.gridViewportForTest(), panel.categoryBarForTest())
                    else -> throw AssertionError("unexpected panel")
                }
                assertEquals("a grid row is a fifth of $height", height / 5, row)
                assertEquals("the grid holds four fifths of $height", 4 * (height / 5), grid.height)
                assertEquals("the categories take the fifth row of $height", height - 4 * (height / 5), bar.height)
            }
        }
    }

    @Test fun every_category_tab_hugs_its_label() {
        val density = ctx.resources.displayMetrics.density
        val sv = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("en")) }
        val ev = EmojiView(ctx).apply { applyPalette(light); openCategoryForTest(1) }
        layout(sv)
        layout(ev)
        val symbolCell = sv.gridCellPixelWidthForTest(SymbolCatalog.categories[idx("en") - 1].symbols.first())
        val emojiCell = requireNotNull(ev.gridCellForTest(0)).width
        val padding = 2 * (SymbolsView.CATEGORY_PADDING_DP * density).toInt()
        val minimum = (SymbolsView.CATEGORY_MIN_WIDTH_DP * density).toInt()

        assertTrue("precondition: the grids are laid out", symbolCell > 0 && emojiCell > 0)
        assertEquals(SymbolsView.CATEGORY_PADDING_DP, EmojiView.CATEGORY_PADDING_DP)
        assertEquals(SymbolsView.CATEGORY_MIN_WIDTH_DP, EmojiView.CATEGORY_MIN_WIDTH_DP)
        for ((label, tabs) in listOf(
            "symbol" to (0..3).map { sv.railTabForTest(it) },
            "emoji" to (0..3).map { ev.railTabForTest(it) },
        )) {
            for ((i, tab) in tabs.withIndex()) {
                val text = tab.paint.measureText(tab.text.toString())
                val expected = maxOf(minimum, (text + padding).toInt())
                assertTrue(
                    "$label category $i is its label plus the padding, or the minimum: $expected vs ${tab.width}",
                    kotlin.math.abs(expected - tab.width) <= 1,
                )
            }
        }
    }
}
