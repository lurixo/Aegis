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
import android.view.View
import android.widget.FrameLayout
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JankRemediationTest {

    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun keyboard_view_is_not_on_a_software_layer() {
        val kv = KeyboardView(ctx)
        assertEquals(
            "the keyboard draws only flat fills/strokes (no shadow) — a software layer was pure re-raster cost",
            View.LAYER_TYPE_NONE,
            kv.layerType,
        )
    }

    @Test fun candidate_strip_and_preedit_tab_are_not_on_a_software_layer() {
        assertEquals(View.LAYER_TYPE_NONE, CandidateView(ctx).layerType)
        assertEquals(View.LAYER_TYPE_NONE, PreeditView(ctx).layerType)
    }

    private fun drawInHost(child: View, childHeight: Int, margin: Int): Bitmap {
        val width = (360 * ctx.resources.displayMetrics.density).toInt()
        val host = FrameLayout(ctx)
        host.addView(child, FrameLayout.LayoutParams(width, childHeight).apply { topMargin = margin })
        val hostHeight = childHeight + margin * 2
        host.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(hostHeight, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, width, hostHeight)
        val bitmap = Bitmap.createBitmap(width, hostHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        host.draw(Canvas(bitmap))
        return bitmap
    }

    private fun assertRowUntouched(bitmap: Bitmap, y: Int, label: String) {
        val row = IntArray(bitmap.width)
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        assertTrue("$label: nothing may paint outside the view bounds at y=$y", row.all { it == Color.MAGENTA })
    }

    @Test fun toolbar_capsule_keeps_its_shadow_clipped_to_the_strip() {
        val density = ctx.resources.displayMetrics.density
        val stripHeight = (44 * density).toInt()
        val margin = (12 * density).toInt()
        for (palette in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val strip = CandidateView(ctx).apply {
                applyPalette(palette)
                setContent(emptyList(), "")
            }
            val bitmap = drawInHost(strip, stripHeight, margin)
            val capsuleBottom = margin + stripHeight - ImeShapes.toolbarCapsuleMarginDp * density
            val shadowY = (capsuleBottom + 2 * density).toInt()
            val shadowed = bitmap.getPixel(bitmap.width / 2, shadowY)
            assertNotEquals("the capsule still casts its shadow below itself", palette.keyboardBg, shadowed)
            assertTrue(
                "the shadow darkens the strip floor",
                Color.red(shadowed) + Color.green(shadowed) + Color.blue(shadowed) <
                    Color.red(palette.keyboardBg) + Color.green(palette.keyboardBg) + Color.blue(palette.keyboardBg),
            )
            assertRowUntouched(bitmap, margin - 1, "strip top")
            assertRowUntouched(bitmap, margin + stripHeight, "strip bottom")
        }
    }

    @Test fun preedit_tab_keeps_its_shadow_clipped_to_the_band() {
        val density = ctx.resources.displayMetrics.density
        val bandHeight = (26 * density).toInt()
        val margin = (12 * density).toInt()
        for (palette in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val preedit = PreeditView(ctx).apply {
                applyPalette(palette)
                setText("ni'hao")
            }
            val bitmap = drawInHost(preedit, bandHeight, margin)
            val tab = preedit.tabBounds()
            val shadowed = bitmap.getPixel((tab.right + 2 * density).toInt(), margin + bandHeight / 2)
            assertNotEquals("the tab still casts its shadow beside itself", Color.MAGENTA, shadowed)
            val face = bitmap.getPixel((tab.left + 2 * density).toInt(), margin + bandHeight - 2)
            assertEquals("the tab face stays the key surface", palette.keySurface, face)
            assertRowUntouched(bitmap, margin - 1, "band top")
            assertRowUntouched(bitmap, margin + bandHeight, "band bottom")
        }
    }
}
