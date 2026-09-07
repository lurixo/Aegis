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

import android.graphics.Color
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.view.MotionEvent
import android.view.View
import com.aegis.ime.LandscapeImeWindowPolicy
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-port-xhdpi")
class PreeditEditingViewTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private fun dp(v: Int) = (v * density).toInt()

    private fun input(nine: Boolean): InputView = InputView(ctx).apply {
        val id = if (nine) LayoutId.NINE else LayoutId.ALPHA
        showKeyboard(Layouts.forId(id, Lang.CN, true), false, false, Lang.CN)
    }

    private fun layout(iv: InputView) {
        iv.measure(
            View.MeasureSpec.makeMeasureSpec(dp(360), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)
    }

    private fun tap(iv: InputView, x: Float, y: Float) {
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(0L, 16L, MotionEvent.ACTION_UP, x, y, 0)
        try {
            iv.dispatchTouchEvent(down)
            iv.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun tapY(iv: InputView): Float = iv.preeditVisualTopPx() + iv.barTopInsetPx() / 2f

    private fun model(raw: String, caret: Int, locked: Int = 0): PreeditModel {
        val text = if (raw == "64426" || raw == "nihao") "ni'hao" else raw
        val display = if (locked > 0) listOf(locked) else emptyList()
        return PreeditModel.align(text, 0, raw, display, display, caret)
    }

    private fun forEachLayout(block: (nine: Boolean, raw: String) -> Unit) {
        block(false, "nihao")
        block(true, "64426")
    }

    @Test fun idle_preedit_tab_is_tappable_and_reports_a_tap() {
        forEachLayout { nine, raw ->
            val iv = input(nine)
            var taps = 0
            iv.onPreeditTap = { taps++ }
            iv.showCandidates(listOf("你好"), "ni'hao", listOf("ni"))
            layout(iv)
            assertFalse(iv.isPreeditEditing())
            assertEquals(dp(26), iv.barTopInsetPx())
            val tab = iv.preeditTabForTest()
            assertNotNull("raw=$raw idle tab is exposed for insets", iv.preeditTabBoundsInWindow())
            tap(iv, iv.preeditVisualLeftPx() + tab.centerX(), tapY(iv))
            assertEquals("raw=$raw tapping the idle tab enters editing", 1, taps)
            tap(iv, iv.preeditVisualLeftPx() + tab.right + dp(40), tapY(iv))
            assertEquals("raw=$raw tapping beside the tab does nothing", 1, taps)
        }
    }

    @Test fun editing_grows_the_band_and_fills_the_width() {
        forEachLayout { nine, raw ->
            val iv = input(nine)
            var overlayChanges = 0
            iv.onOverlayChanged = { overlayChanges++ }
            iv.showCandidates(listOf("你好"), "ni'hao", listOf("ni"))
            layout(iv)
            val idleTab = iv.preeditTabForTest()
            iv.showCandidates(listOf("你好"), "ni'hao", listOf("ni"), preeditModel = model(raw, 5))
            layout(iv)
            assertTrue(iv.isPreeditEditing())
            assertEquals(1, overlayChanges)
            assertEquals("raw=$raw editing band is 44dp", dp(44), iv.barTopInsetPx())
            val editTab = iv.preeditTabForTest()
            assertTrue("raw=$raw editing tab spans the row", editTab.width() > idleTab.width() * 2)
            assertTrue(editTab.right >= iv.width - dp(24))
            iv.showCandidates(listOf("你好"), "ni'hao", listOf("ni"))
            layout(iv)
            assertFalse(iv.isPreeditEditing())
            assertEquals(2, overlayChanges)
            assertEquals(dp(26), iv.barTopInsetPx())
        }
    }

    @Test fun tapping_a_boundary_moves_the_caret_and_the_back_label_leaves_editing() {
        forEachLayout { nine, raw ->
            val iv = input(nine)
            val carets = mutableListOf<Int>()
            var done = 0
            iv.onPreeditCaret = { carets.add(it) }
            iv.onPreeditEditDone = { done++ }
            iv.showCandidates(listOf("你好"), "ni'hao", listOf("ni"), preeditModel = model(raw, 5))
            layout(iv)
            val left = iv.preeditVisualLeftPx()
            tap(iv, left + iv.preeditBoundaryXForTest(3), tapY(iv))
            assertEquals("raw=$raw display boundary 3 sits after the apostrophe", listOf(2), carets)
            tap(iv, left + iv.preeditBoundaryXForTest(0), tapY(iv))
            assertEquals(listOf(2, 0), carets)
            tap(iv, left + iv.preeditBoundaryXForTest(6), tapY(iv))
            assertEquals(listOf(2, 0, 5), carets)
            tap(iv, left + iv.preeditDoneLeftForTest() + dp(10), tapY(iv))
            assertEquals(1, done)
            assertEquals(listOf(2, 0, 5), carets)
        }
    }

    @Test fun the_edit_exit_is_a_plain_back_label_in_the_key_label_color() {
        forEachLayout { nine, raw ->
            val iv = input(nine)
            iv.showCandidates(listOf("你好"), "ni'hao", listOf("ni"), preeditModel = model(raw, 5))
            layout(iv)
            assertEquals(ctx.getString(com.aegis.ime.R.string.panel_back), iv.preeditDoneLabelForTest())
            for (palette in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
                iv.applyPalette(palette)
                assertEquals(palette.keyLabel, iv.preeditDoneTextColorForTest())
                assertTrue(
                    "back label stays readable on the preedit surface",
                    contrastRatio(iv.preeditDoneTextColorForTest(), palette.keySurface) >= 4.5,
                )
            }
        }
    }

    private fun contrastRatio(fg: Int, bg: Int): Double {
        val l1 = luminance(fg)
        val l2 = luminance(bg)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun luminance(color: Int): Double =
        0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color))

    private fun channel(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    @Test fun back_closes_editing_below_panels_and_the_edit_bar() {
        val iv = input(false)
        var done = 0
        iv.onPreeditEditDone = { done++ }
        iv.showCandidates(listOf("你好"), "ni'hao", listOf("ni"))
        assertEquals("NONE", iv.backTargetKindForTest())
        assertFalse(iv.hasOverlay())
        iv.showCandidates(listOf("你好"), "ni'hao", listOf("ni"), preeditModel = model("nihao", 5))
        assertTrue(iv.hasOverlay())
        assertEquals("PREEDIT_EDIT", iv.backTargetKindForTest())
        iv.showPanel(View(ctx))
        assertEquals("PANEL", iv.backTargetKindForTest())
        assertTrue(iv.closeTopOverlay())
        assertEquals(0, done)
        assertEquals("PREEDIT_EDIT", iv.backTargetKindForTest())
        assertTrue(iv.closeTopOverlay())
        assertEquals(1, done)
    }

    @Test fun portrait_insets_expose_only_the_tab_above_the_body() {
        forEachLayout { nine, raw ->
            val iv = input(nine)
            iv.showCandidates(listOf("你好"), "ni'hao", listOf("ni"), preeditModel = model(raw, 5))
            layout(iv)
            val window = Rect(0, 0, iv.width, iv.height)
            val tab = requireNotNull(iv.preeditTabBoundsInWindow())
            val normalTop = iv.barTopInsetPx()
            val spec = LandscapeImeWindowPolicy.resolve(
                compactLandscape = iv.isCompactLandscapeDock(),
                normalTop = normalTop,
                windowBottom = iv.height,
                surfaceBounds = iv.dockTouchableBoundsInWindow(),
                windowBounds = window,
                preeditTab = tab,
            )
            assertEquals(normalTop, spec.contentTop)
            assertEquals(normalTop, spec.visibleTop)
            assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_REGION, spec.touchableInsets)
            assertEquals(Rect(0, normalTop, iv.width, iv.height), spec.touchableRegion)
            assertEquals(tab, spec.touchableExtra)
            assertTrue("raw=$raw the tab lives above the body", tab.top < normalTop)
            assertTrue(tab.bottom <= normalTop)
        }
    }

    @Test fun portrait_without_a_tab_keeps_the_visible_insets_contract() {
        val spec = LandscapeImeWindowPolicy.resolve(
            compactLandscape = false,
            normalTop = 404,
            windowBottom = 891,
            surfaceBounds = Rect(0, 378, 411, 891),
            windowBounds = Rect(0, 0, 411, 891),
            preeditTab = null,
        )
        assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE, spec.touchableInsets)
        assertNull(spec.touchableRegion)
        assertNull(spec.touchableExtra)
    }

    @Test fun compact_landscape_ignores_the_tab_because_the_row_is_already_touchable() {
        val surface = Rect(698, 93, 1280, 582)
        val spec = LandscapeImeWindowPolicy.resolve(
            compactLandscape = true,
            normalTop = 132,
            windowBottom = 582,
            surfaceBounds = surface,
            windowBounds = Rect(0, 0, 1280, 582),
            preeditTab = Rect(720, 95, 800, 130),
        )
        assertEquals(surface, spec.touchableRegion)
        assertNull(spec.touchableExtra)
    }
}
