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
import android.graphics.RectF
import android.view.View
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class QwertyPunctuationInkCenterTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density
    private val pal = ImePalette.STATIC_LIGHT

    private fun qwerty(lang: Lang): KeyboardView = KeyboardView(ctx).apply {
        applyPalette(pal)
        setLayout(Layouts.forId(LayoutId.ALPHA, lang), false, false, lang)
    }

    private fun layOut(v: KeyboardView, widthDp: Int = 360): KeyboardView {
        v.measure(
            View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((230 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v
    }

    private fun render(v: KeyboardView): Bitmap {
        val bmp = Bitmap.createBitmap(v.measuredWidth, v.measuredHeight, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp
    }

    private fun assertAllCnQwertyInkCenters(widthsDp: List<Int>) {
        val fails = ArrayList<String>()
        for (widthDp in widthsDp) {
            val v = layOut(qwerty(Lang.CN), widthDp)
            val canvas = AnchorRecordingCanvas(Bitmap.createBitmap(v.measuredWidth, v.measuredHeight, Bitmap.Config.ARGB_8888))
            v.draw(canvas)
            val keys = v.keyBoundsForTest()
            val rects = keys.associate { it.first.label to it.second }
            for (label in listOf("，", "。")) {
                val rect = requireNotNull(rects[label]) { "$label key missing" }
                val drawn = canvas.texts.filter { it.first == label }.map { it.second }
                if (drawn.size != 1) { fails.add("${widthDp}dp $label drawn ${drawn.size} times"); continue }
                val a = drawn[0]
                if (a.align != Paint.Align.LEFT) fails.add("${widthDp}dp $label align ${a.align}")
                if (kotlin.math.abs(a.ink.centerX() - rect.centerX()) > 1f) {
                    fails.add("${widthDp}dp $label ink X off by ${a.ink.centerX() - rect.centerX()}")
                }
                if (kotlin.math.abs(a.y - (rect.centerY() - a.metricCenter)) > 0.5f) {
                    fails.add("${widthDp}dp $label anchor Y off by ${a.y - (rect.centerY() - a.metricCenter)}")
                }
            }
            for ((key, rect) in keys.filter { it.first.sub != null }) {
                val sub = requireNotNull(key.sub)
                val drawn = canvas.texts.filter { it.first == sub }.map { it.second }
                if (drawn.size != 1) { fails.add("${widthDp}dp ${key.label}/$sub drawn ${drawn.size} times"); continue }
                val a = drawn[0]
                if (a.align != Paint.Align.LEFT) fails.add("${widthDp}dp ${key.label}/$sub align ${a.align}")
                if (kotlin.math.abs(a.ink.centerX() - rect.centerX()) > 1f) {
                    fails.add("${widthDp}dp ${key.label}/$sub ink X off by ${a.ink.centerX() - rect.centerX()}")
                }
                val scale = kotlin.math.min(1f, rect.height() / (52f * density))
                if (kotlin.math.abs(a.y - (rect.top + 15f * density * scale)) > 0.5f) {
                    fails.add("${widthDp}dp ${key.label}/$sub top baseline changed")
                }
            }
        }
        assertTrue("CN qwerty glyph ink is not horizontally centred: $fails", fails.isEmpty())
    }

    @Test fun cn_qwerty_all_hints_and_bottom_punctuation_are_ink_centered_at_xxhdpi() =
        assertAllCnQwertyInkCenters(listOf(280, 320, 360, 411))

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun cn_qwerty_all_hints_and_bottom_punctuation_are_ink_centered_at_mdpi() =
        assertAllCnQwertyInkCenters(listOf(280, 320, 360, 411))

    private class Anchor(
        val x: Float,
        val y: Float,
        val align: Paint.Align,
        val metricCenter: Float,
        val textSize: Float,
        val color: Int,
        val ink: RectF,
    )

    private class AnchorRecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val texts = ArrayList<Pair<String, Anchor>>()

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            super.drawText(text, x, y, paint)
            val bounds = Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            val advance = paint.measureText(text)
            val originX = when (paint.textAlign) {
                Paint.Align.CENTER -> x - advance / 2f
                Paint.Align.RIGHT -> x - advance
                Paint.Align.LEFT -> x
            }
            texts.add(
                text to Anchor(
                    x,
                    y,
                    paint.textAlign,
                    (paint.descent() + paint.ascent()) / 2f,
                    paint.textSize,
                    paint.color,
                    RectF(originX + bounds.left, y + bounds.top, originX + bounds.right, y + bounds.bottom),
                ),
            )
        }
    }

    private fun sp(v: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)

    @Test fun en_qwerty_comma_and_period_keep_font_metric_centring() {
        val v = layOut(qwerty(Lang.CN))
        render(v)
        v.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
        layOut(v)
        val canvas = AnchorRecordingCanvas(Bitmap.createBitmap(v.measuredWidth, v.measuredHeight, Bitmap.Config.ARGB_8888))
        v.draw(canvas)
        val rects = v.keyBoundsForTest().associate { it.first.label to it.second }
        val fails = ArrayList<String>()
        for (label in listOf(",", ".")) {
            val rect = requireNotNull(rects[label]) { "$label key missing" }
            val drawn = canvas.texts.filter { it.first == label }.map { it.second }
            if (drawn.size != 1) { fails.add("$label drawn ${drawn.size} times"); continue }
            val a = drawn[0]
            if (a.align != Paint.Align.CENTER) fails.add("$label align ${a.align}")
            if (kotlin.math.abs(a.x - rect.centerX()) > 0.5f) fails.add("$label anchor X off by ${a.x - rect.centerX()}")
            if (kotlin.math.abs(a.y - (rect.centerY() - a.metricCenter)) > 0.5f) fails.add("$label anchor Y off by ${a.y - (rect.centerY() - a.metricCenter)}")
        }
        assertTrue("EN qwerty punctuation left the font-metric centring path: $fails", fails.isEmpty())
    }

    @Test fun qwerty_letters_drop_below_center_and_sub_hint_sits_top_center() {
        for (lang in listOf(Lang.CN, Lang.EN)) {
            val v = layOut(qwerty(lang))
            val canvas = AnchorRecordingCanvas(Bitmap.createBitmap(v.measuredWidth, v.measuredHeight, Bitmap.Config.ARGB_8888))
            v.draw(canvas)
            val rects = v.keyBoundsForTest().associate { it.first.label to it.second }
            val fails = ArrayList<String>()
            val hints = if (lang == Lang.CN) {
                listOf("q" to "1", "a" to "～", "z" to "（")
            } else {
                listOf("q" to "1", "a" to "~", "z" to "(")
            }
            for ((label, sub) in hints) {
                val rect = requireNotNull(rects[label]) { "$lang $label key missing" }
                val scale = kotlin.math.min(1f, rect.height() / (52f * density))
                val drop = 7f * density * scale
                val letter = canvas.texts.filter { it.first == label }.map { it.second }
                val hint = canvas.texts.filter { it.first == sub }.map { it.second }
                if (letter.size != 1) { fails.add("$lang $label letter drawn ${letter.size} times"); continue }
                if (hint.size != 1) { fails.add("$lang $label hint '$sub' drawn ${hint.size} times"); continue }
                val l = letter[0]; val h = hint[0]
                if (l.align != Paint.Align.CENTER) fails.add("$lang $label letter align ${l.align}")
                if (kotlin.math.abs(l.x - rect.centerX()) > 0.5f) fails.add("$lang $label letter X off by ${l.x - rect.centerX()}")
                if (kotlin.math.abs(l.y - (rect.centerY() + drop - l.metricCenter)) > 0.5f) {
                    fails.add("$lang $label letter not dropped below centre by $drop")
                }
                val expectedHintAlign = if (lang == Lang.CN) Paint.Align.LEFT else Paint.Align.CENTER
                if (h.align != expectedHintAlign) fails.add("$lang $label hint align ${h.align}")
                if (lang == Lang.CN) {
                    if (kotlin.math.abs(h.ink.centerX() - rect.centerX()) > 1f) {
                        fails.add("$lang $label hint ink X off centre by ${h.ink.centerX() - rect.centerX()}")
                    }
                } else if (kotlin.math.abs(h.x - rect.centerX()) > 0.5f) {
                    fails.add("$lang $label hint X off centre by ${h.x - rect.centerX()}")
                }
                if (kotlin.math.abs(h.y - (rect.top + 15f * density * scale)) > 0.5f) fails.add("$lang $label hint Y not at top band")
                if (h.y >= rect.centerY()) fails.add("$lang $label hint sits below centre")
                if (h.y >= l.y) fails.add("$lang $label hint not above the letter")
            }
            assertTrue("qwerty sub-hint geometry wrong ($lang): $fails", fails.isEmpty())
        }
    }

    @Test fun qwerty_sub_hint_is_enlarged_and_deepened() {
        for (lang in listOf(Lang.CN, Lang.EN)) {
            val v = layOut(qwerty(lang))
            val canvas = AnchorRecordingCanvas(Bitmap.createBitmap(v.measuredWidth, v.measuredHeight, Bitmap.Config.ARGB_8888))
            v.draw(canvas)
            val rects = v.keyBoundsForTest().associate { it.first.label to it.second }
            val fails = ArrayList<String>()
            val hints = if (lang == Lang.CN) {
                listOf("q" to "1", "a" to "～", "z" to "（")
            } else {
                listOf("q" to "1", "a" to "~", "z" to "(")
            }
            for ((label, sub) in hints) {
                val rect = requireNotNull(rects[label]) { "$lang $label key missing" }
                val scale = kotlin.math.min(1f, rect.height() / (52f * density))
                val hint = canvas.texts.filter { it.first == sub }.map { it.second }
                if (hint.size != 1) { fails.add("$lang hint '$sub' drawn ${hint.size} times"); continue }
                val h = hint[0]
                val expected = sp(12f) * scale
                if (kotlin.math.abs(h.textSize - expected) > 0.5f) fails.add("$lang '$sub' size ${h.textSize} != $expected")
                if (h.color != pal.keyLabelSecondary) fails.add("$lang '$sub' color ${Integer.toHexString(h.color)} != keyLabelSecondary ${Integer.toHexString(pal.keyLabelSecondary)}")
            }
            assertTrue("qwerty sub-hint size/color not pinned ($lang): $fails", fails.isEmpty())
        }
    }
}
