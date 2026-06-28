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
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RenderHarness {

    private val ctx = RuntimeEnvironment.getApplication()
    private val wPx = ctx.resources.displayMetrics.widthPixels
    private val outDir = File("build/render").apply { mkdirs() }

    private fun snap(view: View, hPx: Int, name: String) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, wPx, hPx)
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.MAGENTA)
        view.draw(Canvas(bmp))
        FileOutputStream(File(outDir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val px = IntArray(wPx); bmp.getPixels(px, 0, wPx, 0, hPx / 2, wPx, 1)
        assertTrue("$name rendered nothing (still magenta)", px.any { it != Color.MAGENTA })
    }

    private val themes = listOf("light" to ImePalette.STATIC_LIGHT, "dark" to ImePalette.STATIC_DARK)

    @Test fun symbols_panel() {
        for ((t, pal) in themes) {
            val v = SymbolsView(ctx).apply {
                recentProvider = { listOf("，", "。", "？", "！", "https://", "@") }
                applyPalette(pal); openCategoryForTest(0)
            }
            snap(v, (560 * ctx.resources.displayMetrics.density).toInt(), "symbols_$t.png")
        }
    }

    @Test fun preedit_band() {
        for ((t, pal) in themes) {
            val v = PreeditView(ctx).apply { applyPalette(pal); setText("ni'hao") }
            snap(v, (30 * ctx.resources.displayMetrics.density).toInt(), "preedit_$t.png")
        }
    }
}
