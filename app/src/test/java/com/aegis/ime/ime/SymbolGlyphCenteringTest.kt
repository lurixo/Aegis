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

    private fun realVerticalCenter(tv: TextView): Float {
        val ink = Rect()
        val sym = tv.text.toString()
        tv.paint.getTextBounds(sym, 0, sym.length, ink)
        val fm = tv.paint.fontMetrics
        return tv.height / 2f + (ink.exactCenterY() - (fm.ascent + fm.descent) / 2f)
    }

    @Test fun other_tab_glyphs_stay_horizontally_ink_centred_with_real_vertical_positions() {
        val sv = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("en")) }
        layout(sv)

        val asymmetric = listOf("^", "~", "{", "}", "[", "]", "(", ")", "•", "§", "*")
        var maxOffset = 0f
        for (s in asymmetric) {
            val tv = sv.gridGlyphForTest(s) ?: throw AssertionError("$s missing from the 英文 tab")
            assertTrue("$s tile must be laid out", tv.width > 0 && tv.height > 0)
            val (cx, cy) = drawnInkCenter(tv)
            assertEquals("$s ink x-centre stays at the tile centre", tv.width / 2f, cx, 1.5f)
            assertEquals("$s draws at its real vertical em-box position, not a forced centre", realVerticalCenter(tv), cy, 0.75f)
            maxOffset = max(maxOffset, abs(tv.translationX))
        }
        assertTrue(
            "under real fonts horizontal ink-centering must actually displace at least one asymmetric glyph",
            maxOffset > 0.5f,
        )

        for (high in listOf("^", "*")) {
            val tv = sv.gridGlyphForTest(high)!!
            val (_, cy) = drawnInkCenter(tv)
            assertTrue("$high sits high in the em-box, not dead-centre (cy=$cy)", cy < tv.height / 2f - 1f)
        }
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
            "the horizontal clearance must not disturb the glyph's real vertical position",
            plain.translationY,
            badged.translationY,
            0.5f,
        )

        val (cx, cy) = drawnInkCenter(badged)
        assertEquals("the badged glyph draws one clearance left of the tile centre", badged.width / 2f - shift, cx, 1.5f)
        assertEquals("the badge clearance leaves the glyph at its real vertical em-box position", realVerticalCenter(badged), cy, 0.75f)
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

    @Test fun cjk_punctuation_sits_at_its_em_box_position_not_dead_centre() {
        val sv = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("zh")) }
        layout(sv)

        fun offset(sym: String): Pair<Float, Float> {
            val tv = sv.gridGlyphForTest(sym) ?: throw AssertionError("$sym missing from the 中文 tab")
            assertTrue("$sym tile must be laid out", tv.width > 0 && tv.height > 0)
            val (cx, cy) = drawnInkCenter(tv)
            return (cx - tv.width / 2f) to (cy - tv.height / 2f)
        }

        for (lowerLeft in listOf("，", "。", "、", "』")) {
            val (dx, dy) = offset(lowerLeft)
            assertTrue("$lowerLeft draws left of the tile centre (dx=$dx)", dx < -1f)
            assertTrue("$lowerLeft draws below the tile centre (dy=$dy)", dy > 1f)
        }

        val (ux, uy) = offset("『")
        assertTrue("『 draws right of the tile centre (dx=$ux)", ux > 1f)
        assertTrue("『 draws above the tile centre (dy=$uy)", uy < -1f)
    }

    @Test fun operators_absent_from_the_placement_table_sit_at_their_real_em_box_position() {
        val sv = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(idx("math")) }
        layout(sv)
        for (op in listOf("×", "÷", "±", "=")) {
            val tv = sv.gridGlyphForTest(op) ?: throw AssertionError("$op missing from the 数学 tab")
            val (cx, cy) = drawnInkCenter(tv)
            assertEquals("$op stays horizontally ink-centred", tv.width / 2f, cx, 1.5f)
            assertEquals("$op sits at its real vertical em-box position", realVerticalCenter(tv), cy, 0.75f)
        }
    }
}
