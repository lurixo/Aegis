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
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.SymbolCatalog
import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SymbolGlyphCenteringTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density
    private val light = ImePalette.STATIC_LIGHT
    private fun idx(id: String) = SymbolCatalog.categories.indexOfFirst { it.id == id } + 1

    private fun layout(v: View) {
        val w = ctx.resources.displayMetrics.widthPixels
        val h = 900
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
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

    private fun clearancePx(): Float = (SymbolsView.BADGE_CLEARANCE_DP * density).toInt().toFloat()

    @Test fun other_tab_asymmetric_glyphs_draw_at_tile_center() {
        val sv = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("en")) }
        layout(sv)

        val asymmetric = listOf("^", "~", "{", "}", "[", "]", "(", ")", "•", "§", "*")
        var maxOffset = 0f
        for (s in asymmetric) {
            val tv = sv.gridGlyphForTest(s) ?: throw AssertionError("$s missing from the 英文 tab")
            assertTrue("$s tile must be laid out", tv.width > 0 && tv.height > 0)
            val (cx, cy) = drawnInkCenter(tv)
            assertEquals("$s ink x-centre sits at the tile centre", tv.width / 2f, cx, 1.5f)
            assertEquals("$s ink y-centre sits at the tile centre", tv.height / 2f, cy, 1.5f)
            maxOffset = max(maxOffset, max(abs(tv.translationX), abs(tv.translationY)))
        }
        assertTrue(
            "under real fonts ink-centering must actually displace at least one asymmetric glyph",
            maxOffset > 0.5f,
        )
    }

    @Test fun common_tab_badged_glyph_shifts_left_to_clear_the_badge() {
        val common = SymbolsView(ctx).apply {
            recentProvider = { listOf("$") }
            recentOriginOf = { "currency" }
            applyPalette(light)
            openCategoryForTest(0)
        }
        val origin = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("currency")) }
        layout(common)

        assertNotNull("test premise: \$ carries an origin badge on the 常用 tab", common.gridBadgeForTest("$"))
        val badged = common.gridGlyphForTest("$")!!
        val plain = origin.gridGlyphForTest("$")!!

        val shift = clearancePx()
        assertEquals(
            "the badge clearance moves the glyph exactly one clearance left of its centred position",
            plain.translationX - shift,
            badged.translationX,
            0.5f,
        )
        assertTrue("the badged glyph is strictly left of the un-badged placement", badged.translationX < plain.translationX)
        assertEquals(
            "the horizontal clearance must not disturb vertical centring",
            plain.translationY,
            badged.translationY,
            0.5f,
        )

        val (cx, cy) = drawnInkCenter(badged)
        assertEquals("the badged glyph draws one clearance left of the tile centre", badged.width / 2f - shift, cx, 1.5f)
        assertEquals("the badged glyph stays vertically centred", badged.height / 2f, cy, 1.5f)
        assertTrue("the badged glyph is biased away from the bottom-right badge", cx < badged.width / 2f - 1f)
    }

    @Test fun un_badged_recents_are_centred_like_any_other_tab() {
        val sv = SymbolsView(ctx).apply {
            recentProvider = { listOf("★") }
            applyPalette(light)
            openCategoryForTest(0)
        }
        layout(sv)
        assertEquals("un-badged recent draws no origin mark", null, sv.gridBadgeForTest("★"))
        val tv = sv.gridGlyphForTest("★")!!
        val (cx, _) = drawnInkCenter(tv)
        assertEquals("with no badge to clear, the glyph stays centred", tv.width / 2f, cx, 1.5f)
    }

    @Test fun multi_char_symbols_keep_their_advance_centring() {
        val sv = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("math")) }
        for (s in listOf("sin", "cos", "tan")) {
            val tv = sv.gridGlyphForTest(s) ?: throw AssertionError("$s missing from the 数学 tab")
            assertEquals("$s keeps zero horizontal offset", 0f, tv.translationX, 0f)
            assertEquals("$s keeps zero vertical offset", 0f, tv.translationY, 0f)
        }
    }
}
