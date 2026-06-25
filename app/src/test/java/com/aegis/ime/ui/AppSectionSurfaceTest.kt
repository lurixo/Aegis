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
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ui.theme.AegisTheme
import com.aegis.ime.ui.theme.SECTION_CONTROL_CONTRAST
import com.aegis.ime.ui.theme.SECTION_DIVIDER_CONTRAST
import com.aegis.ime.ui.theme.SECTION_SUPPORT_CONTRAST
import com.aegis.ime.ui.theme.aegisColorScheme
import com.aegis.ime.ui.theme.appSectionFace
import com.aegis.ime.ui.theme.appSectionScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    @Test fun app_sections_carry_the_same_face_as_the_keyboard_function_keys() {
        for (dark in listOf(false, true)) {
            val scheme = aegisColorScheme(ctx, dark)
            val fill = sectionFill(dark)
            assertEquals(
                "dark=$dark: the card shares the function face of the keyboard",
                ImePalette.from(ctx, dark).functionSurface,
                fill,
            )
            assertNotEquals("dark=$dark: the card still stands apart from the page background", scheme.background.toArgb(), fill)
        }
    }

    @Test fun app_sections_stand_off_the_page_background_in_both_themes() {
        for (dark in listOf(false, true)) {
            val background = aegisColorScheme(ctx, dark).background.toArgb()
            val fill = appSectionFace(ctx, dark)
            val cardLuminance = ColorUtils.calculateLuminance(fill)
            val pageLuminance = ColorUtils.calculateLuminance(background)
            if (dark) {
                assertTrue("dark: the card must sit above the page", cardLuminance > pageLuminance)
            } else {
                assertTrue("light: the card must sink below the page", cardLuminance < pageLuminance)
            }
            val contrast = ColorUtils.calculateContrast(
                if (dark) fill else background,
                if (dark) background else fill,
            )
            assertTrue("dark=$dark: the card parts from the page at $contrast", contrast >= 1.45)
        }
    }

    @Test fun section_ink_compensates_for_the_sunken_card() {
        for (dark in listOf(false, true)) {
            val base = aegisColorScheme(ctx, dark)
            val face = appSectionFace(ctx, dark)
            val scheme = appSectionScheme(base, face)
            val support = ColorUtils.calculateContrast(scheme.onSurfaceVariant.toArgb(), face)
            val divider = ColorUtils.calculateContrast(scheme.outlineVariant.toArgb(), face)
            val title = ColorUtils.calculateContrast(scheme.onSurface.toArgb(), face)
            val track = ColorUtils.calculateContrast(scheme.surfaceContainerHighest.toArgb(), face)
            assertTrue("dark=$dark: the supporting text reads on the card at $support", support >= SECTION_SUPPORT_CONTRAST)
            assertTrue("dark=$dark: the divider parts from the card at $divider", divider >= SECTION_DIVIDER_CONTRAST)
            assertTrue("dark=$dark: the switch track parts from the card at $track", track >= SECTION_CONTROL_CONTRAST)
            assertTrue("dark=$dark: the supporting text stays below the title at $support vs $title", support < title)
            assertEquals("dark=$dark: the title ink is untouched", base.onSurface, scheme.onSurface)
        }
    }

    @Test fun the_page_outside_the_card_keeps_the_untouched_roles() {
        for (dark in listOf(false, true)) {
            val base = aegisColorScheme(ctx, dark)
            val page = base.background.toArgb()
            val support = ColorUtils.calculateContrast(base.onSurfaceVariant.toArgb(), page)
            assertTrue("dark=$dark: the page intro reads on the window background at $support", support >= 4.5)
            val stock = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            assertEquals(
                "dark=$dark: the card must compensate rather than restyle the whole page",
                stock.onSurfaceVariant,
                base.onSurfaceVariant,
            )
            assertEquals("dark=$dark: the page keeps the stock divider ink", stock.outlineVariant, base.outlineVariant)
            assertEquals("dark=$dark: the page keeps the stock control face", stock.surfaceContainerHighest, base.surfaceContainerHighest)
        }
    }
}
