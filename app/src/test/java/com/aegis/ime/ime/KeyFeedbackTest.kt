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
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.KeyboardLayout
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyFeedbackTest {

    private val context = RuntimeEnvironment.getApplication()
    private val density = context.resources.displayMetrics.density

    private fun keyboardView(
        keyboardLayout: KeyboardLayout,
        shifted: Boolean = false,
        locked: Boolean = false,
        language: Lang = Lang.EN,
    ): KeyboardView = KeyboardView(context).apply {
        setLayout(keyboardLayout, shifted, locked, language)
        measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        layout(0, 0, measuredWidth, measuredHeight)
    }

    private fun alphaView(): KeyboardView = keyboardView(Layouts.forId(LayoutId.ALPHA, Lang.EN))

    private fun assertOrdinaryKeyRendersWithoutOutline(
        state: String,
        keyboardLayout: KeyboardLayout,
        language: Lang,
    ) {
        val palette = ImePalette.STATIC_LIGHT.copy(
            keyboardBg = Color.BLACK,
            keySurface = Color.WHITE,
            separator = Color.RED,
        )
        val view = keyboardView(keyboardLayout, language = language).apply { applyPalette(palette) }
        val bounds = view.keyBoundsForTest().first { (key, _) -> key.action == KeyAction.COMMIT && !key.accent }.second
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val centerY = bounds.centerY().toInt()
        assertEquals(state, palette.keyboardBg, bitmap.getPixel((bounds.left - 2f * density).toInt(), centerY))
        assertEquals(state, palette.keySurface, bitmap.getPixel((bounds.left + 2f * density).toInt(), centerY))
        val left = (bounds.left - density).toInt()
        val right = (bounds.left + density).toInt()
        val top = (bounds.top + 12f * density).toInt()
        val bottom = (bounds.bottom - 12f * density).toInt()
        var chromaticPixels = 0
        for (y in top..bottom) for (x in left..right) {
            val pixel = bitmap.getPixel(x, y)
            if (maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) -
                minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 1
            ) {
                chromaticPixels++
            }
        }
        assertEquals(state, 0, chromaticPixels)
    }

    private fun KeyboardView.down(x: Float, y: Float) = dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
    private fun KeyboardView.move(x: Float, y: Float) = dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_MOVE, x, y, 0))
    private fun KeyboardView.up(x: Float, y: Float) = dispatchTouchEvent(MotionEvent.obtain(0, 20, MotionEvent.ACTION_UP, x, y, 0))

    @Test fun every_font_drawn_key_label_uses_normal_weight() {
        assertTrue(alphaView().keyLabelPaintsUseNormalWeightForTest())
    }

    @Test fun enter_glyph_is_centered_and_matches_function_icon_scale_in_every_layout_state() {
        val cases = listOf(
            Triple(Layouts.forId(LayoutId.ALPHA, Lang.CN), false, false),
            Triple(Layouts.forId(LayoutId.ALPHA, Lang.EN), true, true),
            Triple(Layouts.nine(Lang.CN, Layouts.ninePunctuation(), composing = false), false, false),
            Triple(Layouts.nine(Lang.CN, listOf(Key("ci")), composing = true), false, false),
            Triple(Layouts.forId(LayoutId.NUMBER, Lang.CN), false, false),
            Triple(Layouts.forId(LayoutId.SYMBOL, Lang.CN), false, false),
            Triple(Layouts.forId(LayoutId.NUMPAD, Lang.CN), false, false),
        )
        for ((keyboardLayout, shifted, locked) in cases) {
            val view = keyboardView(keyboardLayout, shifted, locked, Lang.CN)
            val key = view.boundsOfActionForTest(KeyAction.ENTER)!!
            val backspace = view.boundsOfActionForTest(KeyAction.BACKSPACE)!!
            val glyph = view.enterGlyphBoundsForTest()!!
            val scale = minOf(backspace.width(), backspace.height()) * 0.24f
            assertEquals(key.centerX(), glyph.centerX(), 0.01f)
            assertEquals(key.centerY(), glyph.centerY(), 0.01f)
            assertEquals(scale * 1.8f, glyph.width(), 0.01f)
            assertEquals(scale * 1.4f, glyph.height(), 0.01f)
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [34], qualifiers = "xxhdpi")
    @Test fun ordinary_key_background_renders_without_an_outer_stroke_in_every_alpha_and_nine_reachable_layout_state() {
        val controller = KeyboardController(
            object : ImeHost {
                override fun commitText(text: CharSequence) {}
                override fun deleteBackward() {}
                override fun performEnter() {}
            },
            object : CandidateEngine {
                override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
            },
        )
        controller.onKey(Key(action = KeyAction.SWITCH_NINE))
        "94".forEach { controller.onKey(Key(it.toString(), output = it.toString())) }
        controller.onKey(Key(action = KeyAction.SEGMENT))
        val firstForcedCut = Layouts.nine(Lang.CN, controller.nineLeftColumn(), composing = true)
        "26".forEach { controller.onKey(Key(it.toString(), output = it.toString())) }
        val secondForcedCut = Layouts.nine(Lang.CN, controller.nineLeftColumn(), composing = true)
        val states = listOf(
            Triple("26-key Chinese", Layouts.forId(LayoutId.ALPHA, Lang.CN), Lang.CN),
            Triple("26-key English", Layouts.forId(LayoutId.ALPHA, Lang.EN), Lang.EN),
            Triple("nine-key resting", Layouts.nine(Lang.CN, Layouts.ninePunctuation(), composing = false), Lang.CN),
            Triple("nine-key composing", Layouts.nine(Lang.CN, listOf(Key("ci", action = KeyAction.PICK_READING)), composing = true), Lang.CN),
            Triple("nine-key first forced-cut chunk", firstForcedCut, Lang.CN),
            Triple("nine-key second forced-cut chunk", secondForcedCut, Lang.CN),
            Triple("nine-key number page", Layouts.forId(LayoutId.NUMBER, Lang.CN), Lang.CN),
            Triple("nine-key symbol page", Layouts.forId(LayoutId.SYMBOL, Lang.CN), Lang.CN),
            Triple("nine-key numpad", Layouts.forId(LayoutId.NUMPAD, Lang.CN), Lang.CN),
        )
        for ((state, keyboardLayout, language) in states) {
            assertOrdinaryKeyRendersWithoutOutline(state, keyboardLayout, language)
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [34], qualifiers = "xxhdpi")
    @Test fun function_keys_fill_with_the_rail_background_while_space_and_enter_keep_their_fills() {
        val palette = ImePalette.STATIC_LIGHT.copy(
            keyboardBg = Color.BLACK,
            keySurface = Color.WHITE,
            railBg = Color.BLUE,
        )
        val view = keyboardView(Layouts.forId(LayoutId.ALPHA, Lang.CN), language = Lang.CN).apply { applyPalette(palette) }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        fun fillAt(bounds: RectF): Int = bitmap.getPixel(bounds.centerX().toInt(), (bounds.top + 3f * density).toInt())
        for (action in listOf(KeyAction.SHOW_SYMBOLS, KeyAction.SWITCH_NUMPAD, KeyAction.TOGGLE_LANG, KeyAction.SHIFT, KeyAction.BACKSPACE)) {
            assertEquals("$action", palette.railBg, fillAt(view.boundsOfActionForTest(action)!!))
        }
        assertEquals(palette.keySurface, fillAt(view.boundsOfActionForTest(KeyAction.SPACE)!!))
        assertEquals(palette.accentBottom, fillAt(view.boundsOfActionForTest(KeyAction.ENTER)!!))
        assertEquals(palette.keySurface, fillAt(view.boundsOfLabelForTest("q")!!))
    }


    @Test fun pressing_a_letter_shows_its_enlarged_preview_when_enabled() {
        val v = alphaView().apply { previewAlphaEnabled = true }
        val (x, y) = v.centerOfLabelForTest("q")!!
        v.down(x, y)
        assertTrue("preview armed on a letter press", v.previewActiveForTest())
        assertEquals("q", v.previewLabelForTest())
        v.up(x, y)
        assertFalse("preview retracts on release", v.previewActiveForTest())
        assertNull(v.previewLabelForTest())
    }

    @Test fun no_preview_when_the_toggle_is_off() {
        val v = alphaView().apply { previewAlphaEnabled = false }
        val (x, y) = v.centerOfLabelForTest("q")!!
        v.down(x, y)
        assertFalse("preview stays hidden when disabled", v.previewActiveForTest())
        v.up(x, y)
    }

    @Test fun functional_keys_are_exempt_from_the_preview() {
        val v = alphaView().apply { previewAlphaEnabled = true }
        for (action in listOf(KeyAction.BACKSPACE, KeyAction.SHIFT, KeyAction.ENTER, KeyAction.SPACE)) {
            val (x, y) = v.centerOfActionForTest(action) ?: continue
            v.down(x, y)
            assertFalse("$action must not show a preview", v.previewActiveForTest())
            v.up(x, y)
        }
    }

    @Test fun sliding_off_the_pressed_key_retracts_the_preview() {
        val v = alphaView().apply { previewAlphaEnabled = true }
        val (x, y) = v.centerOfLabelForTest("q")!!
        val (wx, wy) = v.centerOfLabelForTest("w")!!
        v.down(x, y)
        assertTrue(v.previewActiveForTest())
        v.move(wx, wy)
        assertFalse("preview retracts once the finger leaves the pressed key", v.previewActiveForTest())
        v.up(wx, wy)
    }


    @Test fun a_key_press_vibrates_when_haptics_are_enabled() {
        val v = alphaView().apply { hapticEnabled = true }
        val (x, y) = v.centerOfLabelForTest("q")!!
        v.down(x, y)
        assertEquals(
            "a content-key press performs KEYBOARD_TAP haptic feedback",
            HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(v).lastHapticFeedbackPerformed(),
        )
        v.up(x, y)
    }

    @Test fun a_key_press_does_not_vibrate_when_haptics_are_disabled() {
        val v = alphaView().apply { hapticEnabled = false }
        val (x, y) = v.centerOfLabelForTest("q")!!
        v.down(x, y)
        assertEquals(
            "no haptic feedback when the toggle is off",
            -1, shadowOf(v).lastHapticFeedbackPerformed(),
        )
        v.up(x, y)
    }
}
