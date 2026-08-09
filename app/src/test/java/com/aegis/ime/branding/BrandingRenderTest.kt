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

package com.aegis.ime.branding

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrandingRenderTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val outDir = File("build/branding").apply { mkdirs() }

    private fun write(bmp: Bitmap, name: String) {
        FileOutputStream(File(outDir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun composeIcon(size: Int): Bitmap {
        val l = (size * 108f / 72f).roundToInt()
        val layer = Bitmap.createBitmap(l, l, Bitmap.Config.ARGB_8888)
        val c = Canvas(layer)
        ctx.getDrawable(R.drawable.ic_launcher_background)!!.apply { setBounds(0, 0, l, l); draw(c) }
        ctx.getDrawable(R.drawable.ic_launcher_foreground)!!.apply { setBounds(0, 0, l, l); draw(c) }
        val off = (l - size) / 2
        return Bitmap.createBitmap(layer, off, off, size, size)
    }

    private fun mark(size: Int): Bitmap {
        val l = (size * 108f / 72f).roundToInt()
        val bmp = Bitmap.createBitmap(l, l, Bitmap.Config.ARGB_8888)
        ctx.getDrawable(R.drawable.ic_launcher_foreground)!!.apply { setBounds(0, 0, l, l); draw(Canvas(bmp)) }
        val off = (l - size) / 2
        return Bitmap.createBitmap(bmp, off, off, size, size)
    }

    private fun masked(src: Bitmap, kind: String): Bitmap {
        val s = src.width
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()
        when (kind) {
            "circle" -> path.addCircle(s / 2f, s / 2f, s / 2f, Path.Direction.CW)
            "squircle" -> path.addRoundRect(0f, 0f, s.toFloat(), s.toFloat(), s * 0.28f, s * 0.28f, Path.Direction.CW)
            else -> path.addRect(0f, 0f, s.toFloat(), s.toFloat(), Path.Direction.CW)
        }
        c.drawPath(path, p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, 0f, 0f, p)
        return out
    }

    private fun monochrome(size: Int, dark: Boolean): Bitmap {
        val surface = if (dark) 0xFF303134.toInt() else 0xFFDADCE0.toInt()
        val tint = if (dark) 0xFFE3E2E6.toInt() else 0xFF202124.toInt()
        val l = (size * 108f / 72f).roundToInt()
        val layer = Bitmap.createBitmap(l, l, Bitmap.Config.ARGB_8888)
        val c = Canvas(layer)
        c.drawColor(surface)
        ctx.getDrawable(R.drawable.ic_launcher_monochrome)!!.mutate().apply { setTint(tint); setBounds(0, 0, l, l); draw(c) }
        val off = (l - size) / 2
        return masked(Bitmap.createBitmap(layer, off, off, size, size), "circle")
    }

    private fun glyph(res: Int, size: Int, tint: Int, surface: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(surface)
        val pad = size / 6
        ctx.getDrawable(res)!!.mutate().apply { setTint(tint); setBounds(pad, pad, size - pad, size - pad); draw(c) }
        return bmp
    }

    private fun dist2(a: Int, r: Int, g: Int, b: Int): Int {
        val dr = ((a ushr 16) and 0xFF) - r
        val dg = ((a ushr 8) and 0xFF) - g
        val db = (a and 0xFF) - b
        return dr * dr + dg * dg + db * db
    }

    private fun inkFraction(bmp: Bitmap, ink: IntArray, surface: IntArray): Double {
        var n = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val px = bmp.getPixel(x, y)
            if ((px ushr 24) and 0xFF < 128) continue
            if (dist2(px, ink[0], ink[1], ink[2]) < dist2(px, surface[0], surface[1], surface[2])) n++
        }
        return n.toDouble() / (bmp.width * bmp.height)
    }

    @Test
    fun renders_icon_previews_at_all_sizes_masks_and_monochrome() {
        val sizes = intArrayOf(48, 72, 96, 144, 192)
        for (s in sizes) {
            val icon = composeIcon(s)
            write(masked(icon, "circle"), "icon_${s}_circle.png")
            write(masked(icon, "squircle"), "icon_${s}_squircle.png")
            write(masked(icon, "square"), "icon_${s}_full.png")
        }
        for (mask in arrayOf("circle", "squircle")) write(masked(composeIcon(512), mask), "icon_512_$mask.png")
        write(mark(512), "icon_mark_512.png")

        for (s in intArrayOf(96, 192)) {
            write(monochrome(s, dark = false), "mono_${s}_light.png")
            write(monochrome(s, dark = true), "mono_${s}_dark.png")
        }
        write(glyph(R.drawable.ic_subtype_zh, 96, 0xFF202124.toInt(), ImePalette.STATIC_LIGHT.railBg), "subtype_zh_light.png")
        write(glyph(R.drawable.ic_subtype_en, 96, 0xFF202124.toInt(), ImePalette.STATIC_LIGHT.railBg), "subtype_en_light.png")

        val icon = composeIcon(192)
        val black = inkFraction(icon, intArrayOf(0, 0, 0), intArrayOf(255, 255, 255))
        assertTrue("icon must render the black shield mark on white: black=$black", black in 0.10..0.60)

        val mono = monochrome(192, dark = false)
        val ink = inkFraction(mono, intArrayOf(32, 33, 36), intArrayOf(218, 220, 224))
        assertTrue("monochrome must draw a mark (not empty): ink=$ink", ink > 0.06)
        assertTrue("monochrome must stay line-art (not a solid blob): ink=$ink", ink < 0.36)
    }
}
