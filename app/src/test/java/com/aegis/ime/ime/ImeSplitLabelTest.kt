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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeSplitLabelTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private fun distance(slash: FloatArray, x: Float, y: Float): Float {
        val dx = slash[2] - slash[0]
        val dy = slash[3] - slash[1]
        return abs(dx * (y - slash[1]) - dy * (x - slash[0])) / hypot(dx, dy)
    }

    private fun slashAngleDegrees(slash: FloatArray): Float =
        Math.toDegrees(atan2((slash[1] - slash[3]).toDouble(), (slash[2] - slash[0]).toDouble())).toFloat()

    private fun assertCornerAnchored(name: String, label: ImeSplitLabel, placed: ImeSplitLabel.Placement, rect: RectF) {
        val side = min(rect.width(), rect.height())
        val margin = side * ImeSplitLabel.MARGIN
        val clearance = side * ImeSplitLabel.CLEARANCE
        val lead = placed.leading
        val trail = placed.trailing
        val s = placed.slash
        assertEquals("$name: the leading word's ink starts at the left margin", rect.left + margin, lead.left, 0.01f)
        assertEquals("$name: the leading word's ink starts at the top margin", rect.top + margin, lead.top, 0.01f)
        assertEquals("$name: the trailing word's ink ends at the right margin", rect.right - margin, trail.right, 0.01f)
        assertEquals("$name: the trailing word's ink ends at the bottom margin", rect.bottom - margin, trail.bottom, 0.01f)
        assertTrue("$name: the words carry ink: $lead / $trail", lead.width() > 0f && lead.height() > 0f && trail.width() > 0f && trail.height() > 0f)
        assertTrue("$name: the words never overlap on both axes: $lead vs $trail", lead.bottom <= trail.top || lead.right <= trail.left)
        assertTrue("$name: the slash rises to the right: ${s.toList()}", s[2] > s[0] && s[3] < s[1])
        assertTrue(
            "$name: the slash keeps clear of the leading word's inner corner: ${s.toList()} vs $lead",
            distance(s, lead.right, lead.bottom) >= clearance - 1f,
        )
        assertTrue(
            "$name: the slash keeps clear of the trailing word's inner corner: ${s.toList()} vs $trail",
            distance(s, trail.left, trail.top) >= clearance - 1f,
        )
        val wanted = label.activePaint.textSize * placed.scale * ImeSplitLabel.SLASH_HEIGHT
        assertTrue("$name: the slash stands no taller than the active word's size: ${s.toList()} vs $wanted", s[1] - s[3] <= wanted + 0.05f)
        assertTrue("$name: the slash keeps at least half of that height: ${s.toList()} vs $wanted", s[1] - s[3] >= wanted / 2f - 0.05f)
        assertTrue(
            "$name: the slash stays inside the margins: ${s.toList()} in $rect",
            s[0] >= rect.left + margin - 0.05f && s[2] <= rect.right - margin + 0.05f &&
                s[3] >= rect.top + margin - 0.05f && s[1] <= rect.bottom - margin + 0.05f,
        )
    }

    @Test fun the_words_anchor_to_opposite_corners_with_the_slash_clear_between_them() {
        val label = ImeSplitLabel(2f, 32f, 28f).apply { applyColors(0xFF111111.toInt(), 0xFF888888.toInt()) }
        val rect = RectF(0f, 0f, 200f, 240f)
        val placed = label.layout(rect, "全部", "单字", leadingActive = true)
        assertEquals("a roomy cell needs no shrinking", 1f, placed.scale, 0.001f)
        assertCornerAnchored("roomy", label, placed, rect)
        assertTrue("the trailing word starts right of the leading word", placed.trailing.left > placed.leading.left)

        val flipped = label.layout(rect, "全部", "单字", leadingActive = false)
        assertTrue("an active word is set larger than an idle one", placed.leading.height() > flipped.leading.height())
        assertTrue("the trailing word grows when it takes over", flipped.trailing.height() > placed.trailing.height())
        assertEquals("measuring leaves the active paint at its authored size", 32f, label.activePaint.textSize, 0.001f)
        assertEquals("measuring leaves the idle paint at its authored size", 28f, label.idlePaint.textSize, 0.001f)
        assertEquals("the active paint carries the active colour", 0xFF111111.toInt(), label.activePaint.color)
        assertEquals("the idle paint carries the idle colour", 0xFF888888.toInt(), label.idlePaint.color)
    }

    @Test fun a_cramped_cell_shrinks_both_words_until_the_slash_clears_them() {
        val label = ImeSplitLabel(1f, 16f, 14f)
        val cell = RectF(0f, 0f, 60f, 68f)
        val english = label.layout(cell, "All", "Single", leadingActive = true)
        assertTrue("All/Single shrinks a little to fit the action cell: ${english.scale}", english.scale < 1f && english.scale >= 0.8f)
        assertCornerAnchored("All/Single", label, english, cell)
        val flipped = label.layout(cell, "All", "Single", leadingActive = false)
        assertCornerAnchored("All/Single flipped", label, flipped, cell)

        val roomy = label.layout(cell, "全部", "单字", leadingActive = true)
        assertTrue("全部/单字 keeps most of its size: ${roomy.scale}", roomy.scale >= 0.85f)
        assertCornerAnchored("全部/单字", label, roomy, cell)

        val narrowKey = RectF(0f, 0f, 39f, 51f)
        val lang = ImeSplitLabel(1f, 20f, 18f)
        for (leadingActive in listOf(true, false)) {
            val placed = lang.layout(narrowKey, "中", "EN", leadingActive)
            assertTrue("中/EN fits a narrow key without shrinking below seven tenths: ${placed.scale}", placed.scale >= 0.7f)
            assertCornerAnchored("中/EN narrow $leadingActive", lang, placed, narrowKey)
        }

        val shrunk = label.layout(RectF(0f, 0f, 30f, 30f), "All", "Single", leadingActive = true)
        assertTrue("a tiny cell scales the words down hard: ${shrunk.scale}", shrunk.scale < 0.6f && shrunk.scale > 0f)
        assertCornerAnchored("tiny", label, shrunk, RectF(0f, 0f, 30f, 30f))

        val outer = label.layout(cell, "All", "Single", leadingActive = true, scale = 0.5f)
        assertEquals("an outer scale multiplies into the placement", english.scale * 0.5f, outer.scale, 0.02f)
    }

    @Test fun the_slash_follows_the_cell_diagonal_between_forty_five_and_sixty_degrees() {
        val label = ImeSplitLabel(1f, 12f, 10f)
        val square = label.layout(RectF(0f, 0f, 100f, 100f), "中", "EN", leadingActive = true)
        assertEquals("a square cell draws a forty-five degree slash", 45f, slashAngleDegrees(square.slash), 0.5f)
        val tall = label.layout(RectF(0f, 0f, 100f, 300f), "中", "EN", leadingActive = true)
        assertEquals("a tall cell caps the slash at sixty degrees", 60f, slashAngleDegrees(tall.slash), 0.5f)
        val wide = label.layout(RectF(0f, 0f, 300f, 100f), "中", "EN", leadingActive = true)
        assertEquals("a wide cell keeps the slash at forty-five degrees", 45f, slashAngleDegrees(wide.slash), 0.5f)
        val portrait = label.layout(RectF(0f, 0f, 60f, 68f), "中", "EN", leadingActive = true)
        assertEquals("a portrait cell follows its own diagonal", Math.toDegrees(atan2(68.0, 60.0)).toFloat(), slashAngleDegrees(portrait.slash), 0.5f)
    }

    private fun keyboard(lang: Lang): KeyboardView = KeyboardView(ctx).apply {
        applyPalette(ImePalette.STATIC_LIGHT)
        setLayout(Layouts.forId(LayoutId.ALPHA, lang), false, false, lang)
        measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((250 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, measuredWidth, measuredHeight)
    }

    private fun rendered(view: View): Bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    private fun inkIn(bmp: Bitmap, face: Int, left: Float, top: Float, right: Float, bottom: Float): Int {
        var ink = 0
        for (y in ceil(top).toInt() until floor(bottom).toInt()) {
            for (x in ceil(left).toInt() until floor(right).toInt()) if (bmp.getPixel(x, y) != face) ink++
        }
        return ink
    }

    private fun slashXAt(slash: FloatArray, y: Float): Float =
        slash[0] + (slash[1] - y) * (slash[2] - slash[0]) / (slash[1] - slash[3])

    @Test fun the_language_key_keeps_中_leading_and_EN_trailing_in_both_languages() {
        for (lang in listOf(Lang.CN, Lang.EN)) {
            val view = keyboard(lang)
            val label = view.langLabelForTest()
            assertEquals("$lang: the active word is set at 20sp", 20f * density, label.activePaint.textSize, 0.01f)
            assertEquals("$lang: the idle word is set one size down at 18sp", 18f * density, label.idlePaint.textSize, 0.01f)
            assertEquals("$lang: the active word takes the secondary label colour", ImePalette.STATIC_LIGHT.keyLabelSecondary, label.activePaint.color)
            assertEquals("$lang: the idle word takes the hint colour", ImePalette.STATIC_LIGHT.keyHint, label.idlePaint.color)
            assertEquals("$lang: 中 leads only while Chinese is active", lang == Lang.CN, view.langLeadingActiveForTest())

            val rect = requireNotNull(view.boundsOfActionForTest(KeyAction.TOGGLE_LANG))
            val placed = requireNotNull(view.langPlacementForTest())
            assertCornerAnchored("$lang", label, placed, rect)

            val bmp = rendered(view)
            val face = bmp.getPixel((rect.left + rect.width() * 0.75f).toInt(), (rect.top + rect.height() * 0.12f).toInt())
            assertEquals("$lang: the language key rests on the rail face", ImePalette.STATIC_LIGHT.railBg, face)
            val lead = placed.leading
            val trail = placed.trailing
            assertTrue("$lang: the leading word's ink box carries ink", inkIn(bmp, face, lead.left, lead.top, lead.right, lead.bottom) > 0)
            assertTrue("$lang: the trailing word's ink box carries ink", inkIn(bmp, face, trail.left, trail.top, trail.right, trail.bottom) > 0)
            var under = 0
            var above = 0
            for (y in ceil(trail.top).toInt() until floor(trail.centerY()).toInt()) {
                under += inkIn(bmp, face, lead.left + 2f, y.toFloat(), slashXAt(placed.slash, y.toFloat()) - 2f, y + 1f)
            }
            for (y in ceil(lead.centerY()).toInt() until floor(lead.bottom).toInt()) {
                above += inkIn(bmp, face, slashXAt(placed.slash, y.toFloat()) + 2f, y.toFloat(), trail.right - 2f, y + 1f)
            }
            assertEquals("$lang: nothing is drawn under the leading word left of the slash", 0, under)
            assertEquals("$lang: nothing is drawn above the trailing word right of the slash", 0, above)
            bmp.recycle()
        }
        assertFalse(keyboard(Lang.EN).langLeadingActiveForTest())
    }

    @Test fun the_language_words_come_from_the_shared_strings() {
        assertEquals("中", ctx.getString(R.string.lang_cn))
        assertEquals("EN", ctx.getString(R.string.lang_en))
    }
}
