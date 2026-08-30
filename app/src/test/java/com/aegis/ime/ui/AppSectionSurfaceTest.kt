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

package com.aegis.ime.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ui.theme.AegisTheme
import com.aegis.ime.ui.theme.aegisColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSectionSurfaceTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private fun sectionFill(dark: Boolean): Int {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val compose = ComposeView(activity).apply {
            setContent {
                AegisTheme(darkTheme = dark) {
                    AppSection { Box(Modifier.fillMaxWidth().height(120.dp)) }
                }
            }
        }
        activity.setContentView(compose)
        shadowOf(Looper.getMainLooper()).idle()
        val w = (300 * density).toInt()
        val h = (200 * density).toInt()
        compose.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        compose.layout(0, 0, w, h)
        shadowOf(Looper.getMainLooper()).idle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        compose.draw(Canvas(bmp))
        val fill = bmp.getPixel(w / 2, (60 * density).toInt())
        bmp.recycle()
        return fill
    }

    @Test fun app_sections_fill_with_the_ime_keyboard_background() {
        for (dark in listOf(false, true)) {
            val scheme = aegisColorScheme(ctx, dark)
            val fill = sectionFill(dark)
            assertEquals("dark=$dark: the card fills with surfaceDim", scheme.surfaceDim.toArgb(), fill)
            assertEquals("dark=$dark: that is the colour the keyboard paints behind its keys", ImePalette.from(ctx, dark).keyboardBg, fill)
            assertNotEquals("dark=$dark: the card still stands apart from the page background", scheme.background.toArgb(), fill)
        }
    }
}
