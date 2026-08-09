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

    private fun topInRoot(root: ViewGroup, v: View): Int {
        val r = Rect(0, 0, v.width, v.height)
        root.offsetDescendantRectToMyCoords(v, r)
        return r.top
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

    @Test fun symbols_common_tab_top_aligns_with_the_first_symbol_row() {
        val sv = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("en")) }
        layout(sv)
        val tab0 = sv.railTabForTest(0)
        val tab1 = sv.railTabForTest(1)
        assertTrue("the 常用 tab is nudged down as one unit", (tab0.layoutParams as ViewGroup.MarginLayoutParams).topMargin > 0)
        assertEquals("later rail tabs keep their natural position", 0, (tab1.layoutParams as ViewGroup.MarginLayoutParams).topMargin)

        val row = sv.gridGlyphForTest(",") ?: throw AssertionError(", missing from the first symbol row")
        assertEquals("the 常用 tab lines up with the first symbol row", topInRoot(sv, row).toFloat(), topInRoot(sv, tab0).toFloat(), 2f)
    }

    @Test fun emoji_common_tab_top_aligns_with_the_first_emoji_row() {
        val ev = EmojiView(ctx).apply { applyPalette(light); openCategoryForTest(1) }
        layout(ev)
        val tab0 = ev.railTabForTest(0)
        val tab1 = ev.railTabForTest(1)
        assertTrue("the 常用 tab is nudged down as one unit", (tab0.layoutParams as ViewGroup.MarginLayoutParams).topMargin > 0)
        assertEquals("later rail tabs keep their natural position", 0, (tab1.layoutParams as ViewGroup.MarginLayoutParams).topMargin)

        val cell = ev.gridCellForTest(0) ?: throw AssertionError("first emoji cell missing")
        assertEquals("the 常用 tab lines up with the first emoji row", topInRoot(ev, cell).toFloat(), topInRoot(ev, tab0).toFloat(), 2f)
    }
}
