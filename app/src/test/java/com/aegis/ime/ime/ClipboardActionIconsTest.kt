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
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class ClipboardActionIconsTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density
    private val pal = ImePalette.STATIC_LIGHT

    private fun dp(v: Int) = (v * density).toInt()

    private fun layout(v: View) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec((411 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((600 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun clipView(history: List<String>): ClipboardView = ClipboardView(ctx).apply {
        historyProvider = { history }; applyPalette(pal); refresh()
    }

    private fun phraseView(phrases: List<String>): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { listOf("默认") }
        phrasesInProvider = { c -> if (c == "默认") phrases else emptyList() }
        applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
    }

    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(x: View) { out.add(x); if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(root); return out
    }

    private fun actionIcon(v: ClipboardView, label: String): Drawable =
        requireNotNull(
            allViews(v).filterIsInstance<TextView>()
                .first { it.text?.toString() == label && it.compoundDrawables[0] != null }
                .compoundDrawables[0],
        )

    private fun swipeButton(v: ClipboardView, desc: String): View =
        allViews(v).first { it !is TextView && it.contentDescription?.toString() == desc && it.hasOnClickListeners() }

    private fun renderIcon(icon: Drawable): Bitmap {
        val bmp = Bitmap.createBitmap(icon.intrinsicWidth, icon.intrinsicHeight, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, icon.intrinsicWidth, icon.intrinsicHeight)
        icon.draw(Canvas(bmp))
        return bmp
    }

    private fun renderView(v: View): Bitmap {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp
    }

    private fun isInk(pixel: Int): Boolean {
        if ((pixel ushr 24) < 128) return false
        val r = (pixel shr 16) and 0xFF; val g = (pixel shr 8) and 0xFF; val b = pixel and 0xFF
        return (r + g + b) / 3 < 110
    }

    private fun inkBox(bmp: Bitmap, left: Int, top: Int, right: Int, bottom: Int): IntArray? {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        for (y in top until bottom) for (x in left until right) {
            if (isInk(bmp.getPixel(x, y))) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        return if (maxX < minX) null else intArrayOf(minX, minY, maxX, maxY)
    }

    private fun inkRectOf(symbol: String): Rect {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ImeType.caption, ctx.resources.displayMetrics)
            typeface = Typeface.DEFAULT_BOLD
        }
        return Rect().also { paint.getTextBounds(symbol, 0, symbol.length, it) }
    }

    private class TextRecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val texts = ArrayList<Triple<String, Paint.Align, Pair<Float, Float>>>()

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            super.drawText(text, x, y, paint)
            texts.add(Triple(text, paint.textAlign, x to y))
        }
    }

    @Test fun inline_action_row_char_icons_measure_14dp_square() {
        val clip = clipView(listOf("第一条")).apply { expandForTest("第一条") }
        layout(clip)
        val phrase = phraseView(listOf("你好")).apply { expandForTest("你好") }
        layout(phrase)
        val cases = listOf(
            clip to ctx.getString(com.aegis.ime.R.string.clip_split_word),
            phrase to ctx.getString(com.aegis.ime.R.string.clip_move),
        )
        for ((view, label) in cases) {
            val icon = actionIcon(view, label)
            assertEquals(label, dp(14), icon.intrinsicWidth)
            assertEquals(label, dp(14), icon.intrinsicHeight)
            val ink = requireNotNull(inkBox(renderIcon(icon), 0, 0, icon.intrinsicWidth, icon.intrinsicHeight))
            assertEquals("$label ink width", dp(14), ink[2] - ink[0] + 1)
            assertEquals("$label ink height", dp(14), ink[3] - ink[1] + 1)
        }
    }

    @Test fun swipe_strip_char_icons_render_a_15dp_box_centered_in_the_button() {
        val clip = clipView(listOf("第一条")).apply { revealSwipeForTest("第一条") }
        layout(clip)
        val phrase = phraseView(listOf("你好")).apply { revealSwipeForTest("你好") }
        layout(phrase)
        val cases = listOf(
            clip to ctx.getString(com.aegis.ime.R.string.clip_split_word),
            phrase to ctx.getString(com.aegis.ime.R.string.clip_move),
        )
        for ((view, desc) in cases) {
            val button = swipeButton(view, desc)
            assertEquals(dp(44), button.width)
            val box = dp(15)
            val left = (button.width - box) / 2
            val top = (button.height - box) / 2
            val ink = requireNotNull(inkBox(renderView(button), 0, 0, button.width, button.height))
            assertEquals("$desc ink left", left, ink[0])
            assertEquals("$desc ink top", top, ink[1])
            assertEquals("$desc ink right", left + box - 1, ink[2])
            assertEquals("$desc ink bottom", top + box - 1, ink[3])
        }
    }

    @Test fun inline_char_glyph_is_ink_centered_in_its_box() {
        val phrase = phraseView(listOf("你好")).apply { expandForTest("你好") }
        layout(phrase)
        val icon = actionIcon(phrase, ctx.getString(com.aegis.ime.R.string.clip_move))
        val size = icon.intrinsicWidth
        val canvas = TextRecordingCanvas(Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888))
        icon.setBounds(0, 0, size, size)
        icon.draw(canvas)
        val ink = inkRectOf("移")
        assertEquals(1, canvas.texts.size)
        val (text, align, anchor) = canvas.texts[0]
        assertEquals("移", text)
        assertEquals(Paint.Align.LEFT, align)
        assertEquals(size / 2f - ink.exactCenterX(), anchor.first, 0.01f)
        assertEquals(size / 2f - ink.exactCenterY(), anchor.second, 0.01f)
        val inset = dp(2)
        val glyph = requireNotNull(inkBox(renderIcon(icon), inset, inset, size - inset, size - inset))
        assertEquals(size / 2f, (glyph[0] + glyph[2] + 1) / 2f, 1.5f)
        assertEquals(size / 2f, (glyph[1] + glyph[3] + 1) / 2f, 1.5f)
    }

    @Test fun swipe_strip_char_glyph_is_ink_centered_in_its_box() {
        val clip = clipView(listOf("第一条")).apply { revealSwipeForTest("第一条") }
        layout(clip)
        val button = swipeButton(clip, ctx.getString(com.aegis.ime.R.string.clip_split_word))
        val canvas = TextRecordingCanvas(Bitmap.createBitmap(button.width, button.height, Bitmap.Config.ARGB_8888))
        button.draw(canvas)
        val box = dp(15)
        val left = (button.width - box) / 2
        val top = (button.height - box) / 2
        val boxCx = left + box / 2f
        val boxCy = top + box / 2f
        val ink = inkRectOf("拆")
        assertEquals(1, canvas.texts.size)
        val (text, align, anchor) = canvas.texts[0]
        assertEquals("拆", text)
        assertEquals(Paint.Align.LEFT, align)
        assertEquals(boxCx - ink.exactCenterX(), anchor.first, 0.01f)
        assertEquals(boxCy - ink.exactCenterY(), anchor.second, 0.01f)
        val inset = dp(2)
        val glyph = requireNotNull(inkBox(renderView(button), left + inset, top + inset, left + box - inset, top + box - inset))
        assertEquals(boxCx, (glyph[0] + glyph[2] + 1) / 2f, 1.5f)
        assertEquals(boxCy, (glyph[1] + glyph[3] + 1) / 2f, 1.5f)
    }

    @Test fun edit_square_glyph_ink_matches_the_designed_geometry() {
        val s = 50f
        val cx = 100f
        val cy = 100f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = 2f * density; color = pal.keyLabel
        }
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        Glyphs.drawEditSquare(Canvas(bmp), paint, cx, cy, s)
        val half = paint.strokeWidth / 2f
        val ink = requireNotNull(inkBox(bmp, 0, 0, 200, 200))
        assertEquals(cx - 0.7f * s - half, ink[0].toFloat(), 2f)
        assertEquals(cy - 0.91f * s - half, ink[1].toFloat(), 2f)
        assertEquals(cx + 0.91f * s + half, ink[2].toFloat(), 2f)
        assertEquals(cy + 0.8f * s + half, ink[3].toFloat(), 2f)
        assertTrue(inkBox(bmp, (cx + 0.25f * s).toInt(), (cy - s).toInt(), (cx + s).toInt(), (cy - 0.25f * s).toInt()) != null)
        assertNull(inkBox(bmp, (cx - 0.2f * s).toInt(), (cy + 0.05f * s).toInt(), (cx + 0.05f * s).toInt(), (cy + 0.3f * s).toInt()))
    }

    @Test fun edit_action_sites_render_the_edit_square_glyph() {
        val phrase = phraseView(listOf("你好")).apply { expandForTest("你好") }
        layout(phrase)
        val icon = actionIcon(phrase, ctx.getString(com.aegis.ime.R.string.clip_edit))
        val bmp = renderIcon(icon)
        val cx = icon.intrinsicWidth / 2f
        val cy = icon.intrinsicHeight / 2f
        val s = icon.intrinsicWidth * 0.42f
        assertTrue(inkBox(bmp, 0, 0, icon.intrinsicWidth, icon.intrinsicHeight) != null)
        assertTrue(inkBox(bmp, (cx + 0.3f * s).toInt(), (cy - s).toInt(), (cx + s).toInt(), (cy - 0.3f * s).toInt()) != null)
        val swiped = phraseView(listOf("你好")).apply { revealSwipeForTest("你好") }
        layout(swiped)
        val button = swipeButton(swiped, ctx.getString(com.aegis.ime.R.string.clip_edit))
        val viewBmp = renderView(button)
        val bx = button.width / 2f
        val by = button.height / 2f
        val bs = dp(9).toFloat()
        assertTrue(inkBox(viewBmp, (bx + 0.3f * bs).toInt(), (by - 1.1f * bs).toInt(), (bx + bs).toInt(), (by - 0.3f * bs).toInt()) != null)
    }
}
