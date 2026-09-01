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
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyFaceStyleTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, ctx.resources.displayMetrics)

    private fun laidOut(id: LayoutId, palette: ImePalette = ImePalette.STATIC_LIGHT): KeyboardView =
        KeyboardView(ctx).apply {
            applyPalette(palette)
            setLayout(Layouts.forId(id, Lang.CN), false, false, Lang.CN)
            measure(
                View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            layout(0, 0, measuredWidth, measuredHeight)
        }

    private fun inkIn(view: View, region: RectF): Rect {
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val inset = 4f * density
        val scan = RectF(region).apply { inset(inset, inset) }
        val counts = HashMap<Int, Int>()
        for (y in scan.top.toInt() until scan.bottom.toInt()) {
            for (x in scan.left.toInt() until scan.right.toInt()) {
                counts.merge(bmp.getPixel(x, y), 1, Int::plus)
            }
        }
        val background = counts.maxBy { it.value }.key
        var l = view.width
        var t = view.height
        var r = -1
        var b = -1
        for (y in scan.top.toInt() until scan.bottom.toInt()) {
            for (x in scan.left.toInt() until scan.right.toInt()) {
                if (bmp.getPixel(x, y) == background) continue
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
        }
        return Rect(l, t, r + 1, b + 1)
    }

    private class TextRecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val drawn = ArrayList<Triple<String, Float, Int>>()

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            super.drawText(text, x, y, paint)
            drawn.add(Triple(text, paint.textSize, paint.color))
        }
    }

    private fun record(view: KeyboardView): TextRecordingCanvas {
        val canvas = TextRecordingCanvas(
            Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888),
        )
        view.draw(canvas)
        return canvas
    }

    @Test fun every_face_casts_a_one_dp_bottom_edge_shadow() {
        val palette = ImePalette.STATIC_LIGHT.copy(
            keyboardBg = Color.WHITE,
            shadow = 0xFFFF0000.toInt(),
        )
        val v = laidOut(LayoutId.ALPHA, palette)
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        for (action in listOf(KeyAction.COMMIT, KeyAction.SHIFT, KeyAction.ENTER)) {
            val bounds = requireNotNull(v.keyBoundsForTest().first { (key, _) -> key.action == action }.second)
            val below = bmp.getPixel(bounds.centerX().toInt(), (bounds.bottom + 0.5f * density).toInt())
            assertEquals("$action face keeps a bottom edge shadow", palette.shadow, below)
            val above = bmp.getPixel(bounds.centerX().toInt(), (bounds.top - 0.5f * density).toInt())
            assertEquals("$action face casts no shadow above itself", palette.keyboardBg, above)
        }
    }

}
