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
import android.graphics.RectF
import com.aegis.ime.ime.theme.ImePalette
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyPreviewOverlayTest {
    private val context = RuntimeEnvironment.getApplication()
    private val density = context.resources.displayMetrics.density

    private fun input(id: LayoutId, widthDp: Int = 360, lang: Lang = Lang.CN): InputView = InputView(context).apply {
        showKeyboard(Layouts.forId(id, lang), false, false, lang)
        setKeyPreviewNine(true)
        setKeyPreviewAlpha(true)
        measure(View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        layout(0, 0, measuredWidth, measuredHeight)
    }

    private fun keyboard(view: View): KeyboardView? {
        if (view is KeyboardView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) keyboard(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun View.send(action: Int, x: Float, y: Float, time: Long = 0) {
        MotionEvent.obtain(0, time, action, x, y, 0).let { event ->
            dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun InputView.capture(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }

    @Test fun released_preview_expires_without_hiding_the_next_press_for_both_layouts() {
        for (id in listOf(LayoutId.ALPHA, LayoutId.NINE)) {
            val input = input(id)
            val keyboard = requireNotNull(keyboard(input))
            val first = requireNotNull(input.keyboardLabelBoundsForTest(if (id == LayoutId.ALPHA) "q" else "ABC"))
            val second = requireNotNull(input.keyboardLabelBoundsForTest(if (id == LayoutId.ALPHA) "w" else "DEF"))
            input.send(MotionEvent.ACTION_DOWN, first.centerX(), first.centerY())
            input.send(MotionEvent.ACTION_UP, first.centerX(), first.centerY(), 20)
            assertTrue(keyboard.previewActiveForTest())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
            assertTrue(keyboard.previewActiveForTest())
            input.send(MotionEvent.ACTION_DOWN, second.centerX(), second.centerY(), 80)
            val label = keyboard.previewLabelForTest()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
            assertEquals(label, keyboard.previewLabelForTest())
            assertTrue(keyboard.previewActiveForTest())
            input.send(MotionEvent.ACTION_UP, second.centerX(), second.centerY(), 110)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(79))
            assertTrue(keyboard.previewActiveForTest())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertFalse(keyboard.previewActiveForTest())
            input.send(MotionEvent.ACTION_DOWN, first.centerX(), first.centerY(), 200)
            input.send(MotionEvent.ACTION_UP, first.centerX(), first.centerY(), 210)
            input.setKeyPreviewNine(false)
            input.setKeyPreviewAlpha(false)
            assertFalse(keyboard.previewActiveForTest())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertFalse(keyboard.previewActiveForTest())
        }
    }

    @Test fun preview_is_opaque_at_full_size_on_the_first_frame_in_both_themes_and_layouts() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        for (palette in listOf(com.aegis.ime.ime.theme.ImePalette.STATIC_LIGHT, com.aegis.ime.ime.theme.ImePalette.STATIC_DARK)) {
            for (id in listOf(LayoutId.ALPHA, LayoutId.NINE)) {
                val input = input(id)
                activity.setContentView(input)
                val keyboard = requireNotNull(keyboard(input))
                keyboard.applyPalette(palette)
                val key = requireNotNull(input.keyboardLabelBoundsForTest(if (id == LayoutId.ALPHA) "a" else "GHI"))
                input.send(MotionEvent.ACTION_DOWN, key.centerX(), key.centerY())
                val bounds = requireNotNull(keyboard.previewBoundsForTest())
                val screenshot = input.capture()
                val x = (input.keyboardVisualLeftPx() + bounds.centerX()).toInt()
                val y = (input.keyboardVisualTopPx() + bounds.top + 3 * density).toInt()
                assertEquals(palette.floatSurface, screenshot.getPixel(x, y))
                assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(palette.keyLabel, palette.floatSurface) >= 4.5)
                input.send(MotionEvent.ACTION_CANCEL, key.centerX(), key.centerY())
                assertFalse(keyboard.previewActiveForTest())
            }
        }
        activity.finish()
    }

    @Test fun first_row_previews_draw_above_the_toolbar_and_clear_on_cancel_for_both_layouts() {
        for (id in listOf(LayoutId.ALPHA, LayoutId.NINE)) {
            val input = input(id)
            val keyboard = requireNotNull(keyboard(input))
            val label = if (id == LayoutId.ALPHA) "q" else "ABC"
            val (x, y) = requireNotNull(input.keyboardLabelBoundsForTest(label)?.let { it.centerX() to it.centerY() })
            val before = input.capture()
            input.send(MotionEvent.ACTION_DOWN, x, y)
            val shown = input.capture()
            assertTrue(keyboard.previewActiveForTest())
            var changed = 0
            for (py in 0 until input.keyboardVisualTopPx()) for (px in 0 until input.width) {
                if (before.getPixel(px, py) != shown.getPixel(px, py)) changed++
            }
            assertTrue("$id bubble must be painted over the toolbar: $changed", changed > 100)
            input.send(MotionEvent.ACTION_CANCEL, x, y)
            val cleared = input.capture()
            assertFalse(keyboard.previewActiveForTest())
            for (py in 0 until input.keyboardVisualTopPx()) for (px in 0 until input.width) {
                assertEquals(before.getPixel(px, py), cleared.getPixel(px, py))
            }
        }
    }

    @Test fun all_nine_key_long_press_choices_commit_one_literal_through_parent_dispatch() {
        for (width in listOf(280, 360)) for (block in listOf("ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ")) {
            val digit = (listOf("ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ").indexOf(block) + 2).toString()
            val choices = block.map { it.toString() } + digit + block.map { it.lowercase() }
            for ((index, choice) in choices.withIndex()) {
                val input = input(LayoutId.NINE, width)
                val keyboard = requireNotNull(keyboard(input))
                val emitted = mutableListOf<Key>()
                input.onKey = { emitted.add(it) }
                val (x, y) = requireNotNull(input.keyboardLabelBoundsForTest(block)?.let { it.centerX() to it.centerY() })
                input.send(MotionEvent.ACTION_DOWN, x, y)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
                assertEquals(choices, keyboard.caseBoxLabelsForTest())
                val box = requireNotNull(keyboard.caseBoxBoundsForTest())
                val targetX = input.keyboardVisualLeftPx() + box.left + box.width() * (index + 0.5f) / choices.size
                val targetY = input.keyboardVisualTopPx() + box.centerY()
                input.send(MotionEvent.ACTION_MOVE, targetX, targetY, 380)
                input.send(MotionEvent.ACTION_UP, targetX, targetY, 400)
                assertEquals("$block $choice at $width dp", listOf(choice), emitted.map { it.output })
                assertTrue(emitted.single().direct && emitted.single().verbatim)
                assertFalse(keyboard.caseBoxActiveForTest())
            }
        }
    }

    @Test fun long_press_is_slightly_taller_and_spans_three_normal_keys_for_both_layouts() {
        for (width in listOf(280, 360, 600)) for (id in listOf(LayoutId.NINE, LayoutId.ALPHA)) for (lang in Lang.entries) {
            val input = input(id, width, lang)
            val keyboard = requireNotNull(keyboard(input))
            val labels = if (id == LayoutId.NINE) listOf("ABC", "GHI", "WXYZ") else listOf("q", "t", "p", "a", "m")
            for (label in labels) {
                val key = requireNotNull(input.keyboardLabelBoundsForTest(label))
                input.send(MotionEvent.ACTION_DOWN, key.centerX(), key.centerY())
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
                val box = requireNotNull(keyboard.caseBoxBoundsForTest())
                val first = requireNotNull(keyboard.boundsOfLabelForTest(if (id == LayoutId.NINE) "GHI" else "q"))
                val third = requireNotNull(keyboard.boundsOfLabelForTest(if (id == LayoutId.NINE) "MNO" else "e"))
                assertEquals("$id $label height", key.height() + 4f * density, box.height(), 0.01f)
                assertEquals("$id $label width", third.right - first.left, box.width(), 0.01f)
                if (id == LayoutId.NINE) assertEquals(first.left, box.left, 0.01f)
                assertTrue(box.left >= 0 && box.right <= keyboard.width)
                assertEquals(-1, keyboard.caseBoxSelectedForTest())
                val count = requireNotNull(keyboard.caseBoxLabelsForTest()).size
                val y = input.keyboardVisualTopPx() + box.centerY()
                for (index in 0 until count) {
                    val x = input.keyboardVisualLeftPx() + box.left + box.width() * (index + 0.5f) / count
                    input.send(MotionEvent.ACTION_MOVE, x, y, 380)
                    assertEquals(index, keyboard.caseBoxSelectedForTest())
                }
                input.send(MotionEvent.ACTION_CANCEL, key.centerX(), key.centerY(), 400)
                assertFalse(keyboard.caseBoxActiveForTest())
            }
        }
    }

    @Test fun all_alpha_long_press_choices_commit_from_the_compact_panel() {
        for (lang in Lang.entries) for (label in listOf("q", "t", "p", "a", "m")) for (index in 0..2) {
            val input = input(LayoutId.ALPHA, lang = lang)
            val keyboard = requireNotNull(keyboard(input))
            val emitted = mutableListOf<Key>()
            input.onKey = { emitted.add(it) }
            val key = requireNotNull(input.keyboardLabelBoundsForTest(label))
            input.send(MotionEvent.ACTION_DOWN, key.centerX(), key.centerY())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
            val box = requireNotNull(keyboard.caseBoxBoundsForTest())
            val choice = requireNotNull(keyboard.caseBoxLabelsForTest())[index]
            val x = input.keyboardVisualLeftPx() + box.left + box.width() * (index + 0.5f) / 3
            val y = input.keyboardVisualTopPx() + box.centerY()
            input.send(MotionEvent.ACTION_MOVE, x, y, 380)
            input.send(MotionEvent.ACTION_UP, x, y, 400)
            assertEquals(listOf(choice), emitted.map { it.output })
        }
    }

    @Test fun number_pages_keep_the_preview_toggle_of_the_text_layout() {
        val keyboard = requireNotNull(keyboard(input(LayoutId.NINE)))
        keyboard.previewAlphaEnabled = false
        for (id in listOf(LayoutId.NUMBER, LayoutId.SYMBOL, LayoutId.NUMPAD)) {
            keyboard.setLayout(Layouts.forId(id, Lang.CN), false, false, Lang.CN)
            val bounds = keyboard.keyBoundsForTest().first { it.first.action == com.aegis.ime.layout.KeyAction.COMMIT }.second
            keyboard.send(MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY())
            assertTrue("$id inherits nine-key preview", keyboard.previewActiveForTest())
            keyboard.send(MotionEvent.ACTION_CANCEL, bounds.centerX(), bounds.centerY())
        }
    }

    @Test fun numpad_digits_have_slightly_wider_upright_previews_centered_above_the_key() {
        for (width in listOf(280, 360, 600)) for (digit in '0'..'9') {
            val input = input(LayoutId.NUMPAD, width)
            val keyboard = requireNotNull(keyboard(input))
            val key = requireNotNull(keyboard.boundsOfLabelForTest(digit.toString()))
            val x = key.centerX() + input.keyboardVisualLeftPx()
            val y = key.centerY() + input.keyboardVisualTopPx()
            input.send(MotionEvent.ACTION_DOWN, x, y)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
            val preview = requireNotNull(keyboard.previewBoundsForTest())
            assertEquals(key.width() * .6f, preview.width(), .01f)
            assertEquals(maxOf(key.height() + 8f * density, key.width() / 2f + 8f * density), preview.height(), .01f)
            assertEquals(key.centerX(), preview.centerX(), .01f)
            assertTrue(preview.bottom <= key.top)
            input.send(MotionEvent.ACTION_CANCEL, x, y)
            assertFalse(keyboard.previewActiveForTest())
        }
    }

    @Test fun symbol_column_preview_matches_numpad_digits_and_scroll_cancels_it() {
        for (width in listOf(280, 360, 600)) for (id in listOf(LayoutId.NINE, LayoutId.NUMPAD)) {
            val numeric = requireNotNull(keyboard(input(LayoutId.NUMPAD, width)))
            val digit = requireNotNull(numeric.boundsOfLabelForTest("5"))
            numeric.send(MotionEvent.ACTION_DOWN, digit.centerX(), digit.centerY())
            val expected = requireNotNull(numeric.previewBoundsForTest())
            numeric.send(MotionEvent.ACTION_CANCEL, digit.centerX(), digit.centerY())
            val input = input(id, width)
            val keyboard = requireNotNull(keyboard(input))
            val region = keyboard.scrollRegionForTest()
            val cellHeight = keyboard.scrollCellHeightForTest()
            val x = region.centerX() + input.keyboardVisualLeftPx()
            val y = region.top + cellHeight / 2f + input.keyboardVisualTopPx()
            input.send(MotionEvent.ACTION_DOWN, x, y)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
            val preview = requireNotNull(keyboard.previewBoundsForTest())
            assertEquals(expected.width(), preview.width(), .01f)
            assertEquals(expected.height(), preview.height(), .01f)
            assertEquals(region.centerX().coerceAtLeast(preview.width() / 2f), preview.centerX(), .01f)
            assertTrue(preview.left >= 0f && preview.right <= keyboard.width)
            if (width <= 360) assertTrue(preview.height() > preview.width())
            input.send(MotionEvent.ACTION_MOVE, x, y - 35f * density, 380)
            assertFalse(keyboard.previewActiveForTest())
            input.send(MotionEvent.ACTION_CANCEL, x, y)
        }
    }

    @Test fun retype_preview_keeps_the_middle_pinyin_frame_size() {
        for (width in listOf(280, 360, 600)) {
            val nine = requireNotNull(keyboard(input(LayoutId.NINE, width)))
            val letter = requireNotNull(nine.boundsOfLabelForTest("GHI"))
            nine.send(MotionEvent.ACTION_DOWN, letter.centerX(), letter.centerY())
            val standard = requireNotNull(nine.previewBoundsForTest())
            nine.send(MotionEvent.ACTION_CANCEL, letter.centerX(), letter.centerY())
            val retype = requireNotNull(nine.boundsOfActionForTest(com.aegis.ime.layout.KeyAction.CLEAR_COMPOSING))
            for (gesture in listOf("tap", "hold", "swipe")) {
                nine.send(MotionEvent.ACTION_DOWN, retype.centerX(), retype.centerY())
                if (gesture == "hold") shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
                if (gesture == "swipe") nine.send(MotionEvent.ACTION_MOVE, retype.centerX(), retype.centerY() - 35f * density, 20)
                val preview = requireNotNull(if (gesture == "hold") nine.caseBoxBoundsForTest() else nine.previewBoundsForTest())
                assertEquals(standard.width(), preview.width(), .01f)
                assertEquals(standard.height(), preview.height(), .01f)
                assertTrue(preview.left >= 0 && preview.right <= nine.width)
                nine.send(MotionEvent.ACTION_CANCEL, retype.centerX(), retype.centerY())
            }
        }
    }

    @Test fun retype_long_press_requires_selecting_zero_in_the_popup_and_allows_cancellation() {
        for (gesture in listOf("tap", "hold", "select", "cancel", "return")) {
            val input = input(LayoutId.NINE)
            val keyboard = requireNotNull(keyboard(input))
            val key = requireNotNull(keyboard.boundsOfActionForTest(com.aegis.ime.layout.KeyAction.CLEAR_COMPOSING))
            val x = key.centerX() + input.keyboardVisualLeftPx()
            val y = key.centerY() + input.keyboardVisualTopPx()
            val emitted = mutableListOf<Key>()
            input.onKey = { emitted.add(it) }
            input.send(MotionEvent.ACTION_DOWN, x, y)
            var releaseX = x
            var releaseY = y
            if (gesture != "tap") {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
                assertEquals(listOf("0"), keyboard.caseBoxLabelsForTest())
                if (gesture != "hold") {
                    val box = requireNotNull(keyboard.caseBoxBoundsForTest())
                    releaseX = box.centerX() + input.keyboardVisualLeftPx()
                    releaseY = box.centerY() + input.keyboardVisualTopPx()
                    input.send(MotionEvent.ACTION_MOVE, releaseX, releaseY, 370)
                    assertEquals(0, keyboard.caseBoxSelectedForTest())
                    if (gesture == "return") {
                        releaseX = x; releaseY = y
                        input.send(MotionEvent.ACTION_MOVE, x, y, 390)
                        assertEquals(-1, keyboard.caseBoxSelectedForTest())
                    }
                }
            }
            input.send(if (gesture == "cancel") MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,
                releaseX, releaseY, if (gesture == "tap") 50 else 400)
            when (gesture) {
                "tap" -> assertEquals(com.aegis.ime.layout.KeyAction.CLEAR_COMPOSING, emitted.single().action)
                "select" -> {
                    assertEquals("0", emitted.single().output)
                    assertTrue(emitted.single().direct && emitted.single().preeditLiteral)
                }
                else -> assertTrue(emitted.isEmpty())
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
            assertFalse(keyboard.previewActiveForTest())
            assertFalse(keyboard.caseBoxActiveForTest())
        }
    }

    @Test fun disabling_previews_prevents_every_long_press_popup_without_breaking_taps_or_up_swipes() {
        for (id in listOf(LayoutId.ALPHA, LayoutId.NINE)) {
            val input = input(id)
            val keyboard = requireNotNull(keyboard(input))
            input.setKeyPreviewNine(false)
            input.setKeyPreviewAlpha(false)
            val keys = if (id == LayoutId.NINE) listOf(
                requireNotNull(keyboard.boundsOfLabelForTest("ABC")),
                requireNotNull(keyboard.boundsOfActionForTest(com.aegis.ime.layout.KeyAction.CLEAR_COMPOSING)),
            ) else listOf(requireNotNull(keyboard.boundsOfLabelForTest("q")))
            for ((index, bounds) in keys.withIndex()) for (swipe in listOf(false, true)) {
                val emitted = mutableListOf<Key>()
                input.onKey = { emitted.add(it) }
                val x = bounds.centerX() + input.keyboardVisualLeftPx()
                val y = bounds.centerY() + input.keyboardVisualTopPx()
                input.send(MotionEvent.ACTION_DOWN, x, y)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
                assertFalse(keyboard.previewActiveForTest())
                assertFalse(keyboard.caseBoxActiveForTest())
                val upY = if (swipe) y - 35f * density else y
                if (swipe) input.send(MotionEvent.ACTION_MOVE, x, upY, 380)
                assertFalse(keyboard.previewActiveForTest())
                input.send(MotionEvent.ACTION_UP, x, upY, 400)
                if (id == LayoutId.NINE && index == 1 && !swipe) {
                    assertEquals(com.aegis.ime.layout.KeyAction.CLEAR_COMPOSING, emitted.single().action)
                } else {
                    assertEquals(if (id == LayoutId.ALPHA) if (swipe) "1" else "q" else if (index == 1) "0" else "2", emitted.single().output)
                    assertEquals(swipe, emitted.single().preeditLiteral)
                }
            }
        }
    }

    @Test fun switching_preview_off_during_a_pending_or_active_hold_cannot_leave_an_invisible_selection() {
        for (id in listOf(LayoutId.ALPHA, LayoutId.NINE)) for (open in listOf(false, true)) {
            val input = input(id)
            val keyboard = requireNotNull(keyboard(input))
            val emitted = mutableListOf<Key>()
            input.onKey = { emitted.add(it) }
            val bounds = requireNotNull(input.keyboardLabelBoundsForTest(if (id == LayoutId.ALPHA) "q" else "ABC"))
            val x = bounds.centerX(); val y = bounds.centerY()
            input.send(MotionEvent.ACTION_DOWN, x, y)
            if (open) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
            input.setKeyPreviewNine(false)
            input.setKeyPreviewAlpha(false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
            assertFalse(keyboard.previewActiveForTest())
            assertFalse(keyboard.caseBoxActiveForTest())
            input.send(MotionEvent.ACTION_UP, x, y, 800)
            assertEquals(if (open) 0 else 1, emitted.size)
            assertTrue(emitted.none { it.direct })
        }
    }

    @Test fun nine_key_up_swipe_previews_the_digit_and_cancels_the_long_press() {
        val keyboard = requireNotNull(keyboard(input(LayoutId.NINE)))
        val (x, y) = requireNotNull(keyboard.centerOfLabelForTest("ABC"))
        keyboard.send(MotionEvent.ACTION_DOWN, x, y)
        keyboard.send(MotionEvent.ACTION_MOVE, x, y - 35f * density, 20)
        assertEquals("2", keyboard.previewLabelForTest())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        assertFalse(keyboard.caseBoxActiveForTest())
        keyboard.send(MotionEvent.ACTION_CANCEL, x, y)
    }
    @Test fun the_right_decimal_does_not_preview_but_still_types_and_left_operators_still_preview() {
        val input = input(LayoutId.NUMPAD)
        val keyboard = requireNotNull(keyboard(input))
        val emitted = mutableListOf<Key>()
        input.onKey = { emitted.add(it) }
        val five = requireNotNull(input.keyboardLabelBoundsForTest("5"))
        input.send(MotionEvent.ACTION_DOWN, five.centerX(), five.centerY())
        input.send(MotionEvent.ACTION_UP, five.centerX(), five.centerY(), 20)
        assertTrue(keyboard.previewActiveForTest())
        val decimal = requireNotNull(input.keyboardLabelBoundsForTest("."))
        for (hold in listOf(false, true)) {
            input.send(MotionEvent.ACTION_DOWN, decimal.centerX(), decimal.centerY())
            assertFalse(keyboard.previewActiveForTest())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(if (hold) 350 else 20))
            assertFalse(keyboard.caseBoxActiveForTest())
            input.send(MotionEvent.ACTION_UP, decimal.centerX(), decimal.centerY(), 400)
            assertFalse(keyboard.previewActiveForTest())
        }
        assertEquals(listOf("5", ".", "."), emitted.map { it.output })
        keyboard.setLayout(Layouts.numpad(listOf(Key(".", direct = true))), false, false, Lang.CN)
        val region = keyboard.scrollRegionForTest()
        keyboard.send(MotionEvent.ACTION_DOWN, region.centerX(), region.top + keyboard.scrollCellHeightForTest() / 2f)
        assertEquals(".", keyboard.previewLabelForTest())
        keyboard.send(MotionEvent.ACTION_CANCEL, region.centerX(), region.centerY())
    }

    private fun popupBitmap(keyboard: KeyboardView, box: RectF): Bitmap =
        Bitmap.createBitmap(kotlin.math.ceil(box.width()).toInt(), kotlin.math.ceil(box.height()).toInt(), Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            canvas.translate(-box.left, -box.top)
            keyboard.drawPreviewOverlay(canvas)
        }

    private fun colorBounds(bitmap: Bitmap, color: Int): RectF {
        val rect = RectF(bitmap.width.toFloat(), bitmap.height.toFloat(), 0f, 0f)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (bitmap.getPixel(x, y) == color) {
                rect.left = minOf(rect.left, x.toFloat()); rect.top = minOf(rect.top, y.toFloat())
                rect.right = maxOf(rect.right, x + 1f); rect.bottom = maxOf(rect.bottom, y + 1f)
            }
        }
        assertFalse("the rendered color must be present", rect.isEmpty)
        return rect
    }

    @Test fun highlighted_letters_and_digits_use_the_enter_foreground_in_both_themes() {
        for (palette in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            for (id in listOf(LayoutId.NINE, LayoutId.ALPHA)) {
                val keyboard = requireNotNull(keyboard(input(id)))
                keyboard.applyPalette(palette)
                val key = requireNotNull(keyboard.boundsOfLabelForTest(if (id == LayoutId.NINE) "ABC" else "q"))
                keyboard.send(MotionEvent.ACTION_DOWN, key.centerX(), key.centerY())
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
                val box = requireNotNull(keyboard.caseBoxBoundsForTest())
                val count = requireNotNull(keyboard.caseBoxLabelsForTest()).size
                for (index in 0 until count) {
                    keyboard.send(MotionEvent.ACTION_MOVE, box.left + box.width() * (index + .5f) / count, box.centerY(), 380)
                    assertEquals(index, keyboard.caseBoxSelectedForTest())
                    val bitmap = popupBitmap(keyboard, box)
                    val fill = colorBounds(bitmap, palette.accentBottom)
                    val text = colorBounds(bitmap, palette.accentLabel)
                    assertTrue("Enter foreground is confined to the selected cell", fill.contains(text))
                    colorBounds(bitmap, palette.keyLabel)
                    bitmap.recycle()
                }
                keyboard.send(MotionEvent.ACTION_CANCEL, key.centerX(), key.centerY())
            }
        }
    }

    @Test fun retype_zero_uses_a_vertical_highlight_and_only_that_region_can_be_selected() {
        for (width in listOf(280, 360, 600)) for (palette in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val keyboard = requireNotNull(keyboard(input(LayoutId.NINE, width)))
            keyboard.applyPalette(palette)
            val emitted = mutableListOf<Key>()
            keyboard.onKey = { emitted.add(it) }
            val key = requireNotNull(keyboard.boundsOfActionForTest(com.aegis.ime.layout.KeyAction.CLEAR_COMPOSING))
            keyboard.send(MotionEvent.ACTION_DOWN, key.centerX(), key.centerY())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
            val box = requireNotNull(keyboard.caseBoxBoundsForTest())
            keyboard.send(MotionEvent.ACTION_MOVE, box.centerX(), box.centerY(), 380)
            assertEquals(0, keyboard.caseBoxSelectedForTest())
            val bitmap = popupBitmap(keyboard, box)
            val highlight = colorBounds(bitmap, palette.accentBottom)
            assertTrue(highlight.height() > highlight.width())
            assertEquals(box.width() / 2f, highlight.centerX(), 1f)
            assertTrue(highlight.contains(colorBounds(bitmap, palette.accentLabel)))
            bitmap.recycle()
            for (x in listOf(box.left + highlight.left - 2f * density, box.left + highlight.right + 2f * density)) {
                keyboard.send(MotionEvent.ACTION_MOVE, x, box.centerY(), 390)
                assertEquals("blank space beside zero is not selectable", -1, keyboard.caseBoxSelectedForTest())
                keyboard.send(MotionEvent.ACTION_MOVE, box.centerX(), box.centerY(), 395)
                assertEquals(0, keyboard.caseBoxSelectedForTest())
            }
            keyboard.send(MotionEvent.ACTION_UP, box.left + highlight.left - 2f * density, box.centerY(), 400)
            assertTrue(emitted.isEmpty())
        }
    }

}
