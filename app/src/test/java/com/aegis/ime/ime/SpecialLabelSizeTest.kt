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
import android.util.TypedValue
import android.view.View
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SpecialLabelSizeTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private class SizeRecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val texts = LinkedHashMap<String, ArrayList<Float>>()

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            super.drawText(text, x, y, paint)
            texts.getOrPut(text) { ArrayList() }.add(paint.textSize)
        }
    }

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, ctx.resources.displayMetrics)

    private fun qwerty(lang: Lang): KeyboardView = KeyboardView(ctx).apply {
        setLayout(Layouts.forId(LayoutId.ALPHA, lang), false, false, lang)
    }

    private fun nine(lang: Lang): KeyboardView = KeyboardView(ctx).apply {
        setLayout(Layouts.nine(Layouts.ninePunctuation()), false, false, lang)
    }

    private fun laidOut(view: KeyboardView): KeyboardView {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(ctx.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    private fun record(view: KeyboardView): SizeRecordingCanvas {
        val canvas = SizeRecordingCanvas(
            Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888),
        )
        view.draw(canvas)
        return canvas
    }

    private fun only(canvas: SizeRecordingCanvas, text: String): Float {
        val drawn = requireNotNull(canvas.texts[text]) { "'$text' was never drawn" }
        assertTrue("'$text' drawn ${drawn.size} times", drawn.size == 1)
        return drawn[0]
    }

    private fun assertBottomRowLabelsRenderAtTheDesignedSize(
        tag: String,
        widths: List<String>,
        view: (Lang) -> KeyboardView,
    ) {
        val fails = ArrayList<String>()
        for (q in widths) {
            RuntimeEnvironment.setQualifiers(q)
            RuntimeEnvironment.setQualifiers("+zh-rCN")
            val canvas = record(laidOut(view(Lang.CN)))
            if (canvas.texts.containsKey("空格")) {
                fails.add("$tag $q still spells out 空格 instead of the space marker")
            }
            for (label in listOf("符号", "123")) {
                val drawn = only(canvas, label)
                if (abs(drawn - sp(20f)) > 0.5f) {
                    fails.add("$tag $q $label is ${drawn}px, not the designed ${sp(20f)}px")
                }
            }
        }
        assertTrue("bottom-row labels do not hold the designed size: $fails", fails.isEmpty())
    }

    @Test fun cn_qwerty_bottom_row_labels_render_at_the_designed_size() =
        assertBottomRowLabelsRenderAtTheDesignedSize(
            "qwerty",
            listOf("w393dp-h851dp-xxhdpi", "w411dp-h891dp-xxhdpi", "w480dp-h900dp-hdpi"),
            ::qwerty,
        )

    @Test fun cn_nine_bottom_row_labels_render_at_the_designed_size() =
        assertBottomRowLabelsRenderAtTheDesignedSize(
            "nine",
            listOf("w320dp-h650dp-xhdpi", "w360dp-h740dp-xxhdpi", "w393dp-h851dp-xxhdpi", "w411dp-h891dp-xxhdpi", "w480dp-h900dp-hdpi"),
            ::nine,
        )

    @Test fun qwerty_bottom_row_punctuation_keeps_the_untouched_base_size() {
        val fails = ArrayList<String>()
        for (q in WIDTHS) {
            for ((locale, lang, marks) in listOf(
                Triple("+zh-rCN", Lang.CN, listOf("，", "。")),
                Triple("+en", Lang.EN, listOf(",", ".")),
            )) {
                RuntimeEnvironment.setQualifiers(q)
                RuntimeEnvironment.setQualifiers(locale)
                val canvas = record(laidOut(qwerty(lang)))
                for (mark in marks) {
                    val drawn = only(canvas, mark)
                    if (abs(drawn - sp(20f)) > 0.5f) {
                        fails.add("$q $lang $mark is ${drawn}px, not the untouched ${sp(20f)}px")
                    }
                }
            }
        }
        assertTrue("bottom-row punctuation was reached by the label fitting: $fails", fails.isEmpty())
    }

    @Test fun cn_bottom_row_labels_stay_near_the_designed_size_on_narrow_screens() {
        val fails = ArrayList<String>()
        for (q in WIDTHS) {
            RuntimeEnvironment.setQualifiers(q)
            RuntimeEnvironment.setQualifiers("+zh-rCN")
            for ((name, view) in listOf("qwerty" to qwerty(Lang.CN), "nine" to nine(Lang.CN))) {
                val canvas = record(laidOut(view))
                for (label in listOf("符号", "123")) {
                    val drawn = only(canvas, label)
                    if (drawn < sp(20f) * NARROW_FLOOR) {
                        fails.add("$q $name $label is ${drawn}px against the designed ${sp(20f)}px")
                    }
                }
            }
        }
        assertTrue("bottom-row labels shrink far past the designed size: $fails", fails.isEmpty())
    }

    private companion object {
        const val NARROW_FLOOR = 0.6f

        val WIDTHS = listOf(
            "w250dp-h700dp-mdpi",
            "w320dp-h650dp-xhdpi",
            "w360dp-h740dp-xxhdpi",
            "w393dp-h851dp-xxhdpi",
            "w411dp-h891dp-xxhdpi",
            "w480dp-h900dp-hdpi",
        )
    }
}
