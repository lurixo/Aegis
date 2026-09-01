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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class QwertySymbolsLabelSizeTest {

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

    private fun qwerty(): KeyboardView = KeyboardView(ctx).apply {
        setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
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

    @Test fun en_qwerty_symbols_label_renders_two_lines_at_the_designed_size() {
        val fails = ArrayList<String>()
        for (q in listOf("w393dp-h851dp-xxhdpi", "w411dp-h891dp-xxhdpi", "w480dp-h900dp-hdpi")) {
            RuntimeEnvironment.setQualifiers(q)
            RuntimeEnvironment.setQualifiers("+en")
            val canvas = record(laidOut(qwerty()))
            assertFalse("$q still draws 'Symbols' as a single fitted line", canvas.texts.containsKey("Symbols"))
            assertFalse("$q still spells out Space instead of the space marker", canvas.texts.containsKey("Space"))
            for (line in listOf("Sym", "bols")) {
                val drawn = only(canvas, line)
                if (abs(drawn - sp(20f)) > 0.5f) {
                    fails.add("$q $line is ${drawn}px, not the designed ${sp(20f)}px")
                }
            }
        }
        assertTrue("the Symbols label does not share the designed size: $fails", fails.isEmpty())
    }

    @Test fun en_qwerty_symbols_label_stays_near_the_designed_size_on_narrow_screens() {
        val fails = ArrayList<String>()
        for (q in listOf(
            "w250dp-h700dp-mdpi",
            "w320dp-h650dp-xhdpi",
            "w360dp-h740dp-xxhdpi",
            "w393dp-h851dp-xxhdpi",
            "w411dp-h891dp-xxhdpi",
            "w480dp-h900dp-hdpi",
        )) {
            RuntimeEnvironment.setQualifiers(q)
            RuntimeEnvironment.setQualifiers("+en")
            val canvas = record(laidOut(qwerty()))
            assertFalse("$q still draws 'Symbols' as a single fitted line", canvas.texts.containsKey("Symbols"))
            assertFalse("$q still spells out Space instead of the space marker", canvas.texts.containsKey("Space"))
            val head = only(canvas, "Sym")
            val tail = only(canvas, "bols")
            if (abs(head - tail) > 0.5f) {
                fails.add("$q lines differ: Sym ${head}px against bols ${tail}px")
            }
            if (head < sp(20f) * NARROW_FLOOR) {
                fails.add("$q Sym is ${head}px against the designed ${sp(20f)}px")
            }
        }
        assertTrue("the Symbols label shrinks far past the designed size: $fails", fails.isEmpty())
    }

    private companion object {
        const val NARROW_FLOOR = 0.6f
    }
}
