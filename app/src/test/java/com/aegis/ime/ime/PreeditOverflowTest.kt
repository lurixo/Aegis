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

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-port-xhdpi")
class PreeditOverflowTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density
    private val pad = 6f * density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private fun dp(v: Int) = (v * density).toInt()

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)

    private val base = sp(16f)
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(16f) }
    private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(20f) }

    private val syllables = listOf(
        "wo", "men", "ming", "tian", "yi", "qi", "qu", "bei", "jing", "kan",
        "dian", "ying", "ran", "hou", "hui", "jia", "chi", "fan", "shui", "jiao",
        "zhuang", "shuang", "chuang", "xiang", "qiong", "liang", "niang", "guang", "kuang", "huang",
    )
    private val all = syllables.size

    private fun label(count: Int): String = syllables.take(count).joinToString("'")

    private fun digit(c: Char): Char = when (c) {
        in 'a'..'c' -> '2'
        in 'd'..'f' -> '3'
        in 'g'..'i' -> '4'
        in 'j'..'l' -> '5'
        in 'm'..'o' -> '6'
        in 'p'..'s' -> '7'
        in 't'..'v' -> '8'
        else -> '9'
    }

    private fun raw(count: Int, nine: Boolean): String {
        val letters = syllables.take(count).joinToString("")
        return if (nine) letters.map(::digit).joinToString("") else letters
    }

    private fun model(count: Int, nine: Boolean, caret: Int): PreeditModel =
        PreeditModel.align(label(count), 0, raw(count, nine), emptyList(), emptyList(), caret)

    private fun input(nine: Boolean): InputView = InputView(ctx).apply {
        val id = if (nine) LayoutId.NINE else LayoutId.ALPHA
        showKeyboard(Layouts.forId(id, Lang.CN, true), false, false, Lang.CN)
    }

    private fun show(iv: InputView, widthDp: Int, count: Int, model: PreeditModel? = null) {
        iv.showCandidates(listOf("我"), label(count), listOf("wo"), preeditModel = model)
        iv.measure(
            View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)
    }

    private fun send(iv: InputView, events: List<MotionEvent>) {
        try {
            events.forEach { iv.dispatchTouchEvent(it) }
        } finally {
            events.forEach { it.recycle() }
        }
    }

    private fun rowY(iv: InputView): Float = (iv.preeditVisualTopPx() + iv.preeditVisualBottomPx()) / 2f

    private fun tap(iv: InputView, x: Float) {
        val at = iv.preeditVisualLeftPx() + x
        val y = rowY(iv)
        send(
            iv,
            listOf(
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, at, y, 0),
                MotionEvent.obtain(0L, 16L, MotionEvent.ACTION_UP, at, y, 0),
            ),
        )
    }

    private fun swipe(iv: InputView, fromX: Float, byX: Float, rest: Int) {
        val from = iv.preeditVisualLeftPx() + fromX
        val y = rowY(iv)
        val steps = 8
        val events = ArrayList<MotionEvent>()
        events += MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, from, y, 0)
        for (i in 1..steps) events += MotionEvent.obtain(0L, 16L * i, MotionEvent.ACTION_MOVE, from + byX * i / steps, y, 0)
        for (i in 1..rest) events += MotionEvent.obtain(0L, 16L * (steps + i), MotionEvent.ACTION_MOVE, from + byX, y, 0)
        events += MotionEvent.obtain(0L, 16L * (steps + rest + 1), MotionEvent.ACTION_UP, from + byX, y, 0)
        send(iv, events)
    }

    private fun drag(iv: InputView, fromX: Float, byX: Float) = swipe(iv, fromX, byX, 8)

    private fun flick(iv: InputView, fromX: Float, byX: Float) = swipe(iv, fromX, byX, 0)

    private fun flickThenHold(iv: InputView, fromX: Float, byX: Float, holdMs: Long) {
        val from = iv.preeditVisualLeftPx() + fromX
        val y = rowY(iv)
        val steps = 8
        val events = ArrayList<MotionEvent>()
        events += MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, from, y, 0)
        for (i in 1..steps) events += MotionEvent.obtain(0L, 16L * i, MotionEvent.ACTION_MOVE, from + byX * i / steps, y, 0)
        events += MotionEvent.obtain(0L, 16L * steps + holdMs, MotionEvent.ACTION_UP, from + byX, y, 0)
        send(iv, events)
    }

    private fun frame(iv: InputView): Float {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        capture(iv).recycle()
        return iv.preeditTextStartForTest()
    }

    private fun settle(iv: InputView, at: String): List<Float> {
        val starts = ArrayList<Float>()
        while (iv.preeditFlingingForTest()) {
            assertTrue("$at the fling comes to rest", starts.size < 600)
            starts += frame(iv)
        }
        return starts
    }

    private fun capture(iv: InputView): Bitmap =
        Bitmap.createBitmap(iv.width, iv.height, Bitmap.Config.ARGB_8888).also { iv.draw(Canvas(it)) }

    private fun fits(count: Int, origin: Float, cap: Float): Boolean =
        origin + idlePaint.measureText(label(count)) + pad <= cap

    private fun forEachLayout(widthDp: Int, block: (iv: InputView, nine: Boolean, origin: Float, cap: Float, where: String) -> Unit) {
        for (nine in listOf(false, true)) {
            val iv = input(nine)
            show(iv, widthDp, all)
            val cap = iv.preeditTabForTest().right
            show(iv, widthDp, 1)
            val origin = iv.preeditTextStartForTest()
            val where = "nine=$nine width=$widthDp"
            assertFalse("$where precondition: the longest label outgrows the row", fits(all, origin, cap))
            assertTrue("$where precondition: the row ends inside the view", cap <= iv.preeditVisualRightPx() - iv.preeditVisualLeftPx())
            block(iv, nine, origin, cap, where)
        }
    }

    private fun assertFittingLabelDoesNotScroll(widthDp: Int) = forEachLayout(widthDp) { iv, _, origin, cap, where ->
        var taps = 0
        iv.onPreeditTap = { taps++ }
        var fitting = 0
        for (count in 1..all) {
            if (!fits(count, origin, cap)) break
            fitting++
            show(iv, widthDp, count)
            val tab = iv.preeditTabForTest()
            val at = "$where syllables=$count"
            assertEquals("$at the text keeps its size", base, iv.preeditTextSizeForTest(), 0f)
            assertEquals("$at the text starts at its origin", origin, iv.preeditTextStartForTest(), 0f)
            assertEquals("$at the tab hugs the text", origin + idlePaint.measureText(label(count)) + pad, tab.right, 0.01f)
            drag(iv, tab.centerX(), dp(200).toFloat())
            drag(iv, tab.centerX(), -dp(200).toFloat())
            assertEquals("$at a drag moves nothing", origin, iv.preeditTextStartForTest(), 0f)
            assertEquals("$at a drag leaves the tab alone", tab, iv.preeditTabForTest())
        }
        assertTrue("$where several labels fit", fitting > 1)
        assertEquals("$where a drag is not a tap", 0, taps)
        tap(iv, iv.preeditTabForTest().centerX())
        assertEquals("$where a fitting label still takes a tap", 1, taps)
    }

    @Test fun a_label_that_fits_does_not_scroll_in_portrait() = assertFittingLabelDoesNotScroll(360)

    @Config(qualifiers = "w740dp-h360dp-land-xhdpi")
    @Test fun a_label_that_fits_does_not_scroll_in_landscape() = assertFittingLabelDoesNotScroll(740)

    private fun assertTypingKeepsTheNewestLetterInView(widthDp: Int) = forEachLayout(widthDp) { iv, _, origin, cap, where ->
        var previous = capture(iv)
        var overflowing = 0
        for (count in 2..all) {
            show(iv, widthDp, count)
            val at = "$where syllables=$count"
            val current = capture(iv)
            assertFalse("$at the typed syllable changes what is drawn", current.sameAs(previous))
            previous.recycle()
            previous = current
            val tab = iv.preeditTabForTest()
            val start = iv.preeditTextStartForTest()
            val end = start + idlePaint.measureText(label(count))
            assertEquals("$at the text keeps its size", base, iv.preeditTextSizeForTest(), 0f)
            if (fits(count, origin, cap)) {
                assertEquals("$at a fitting label starts at its origin", origin, start, 0f)
            } else {
                overflowing++
                assertEquals("$at the tab stops at the row limit", cap, tab.right, 0f)
                assertEquals("$at the last letter meets the inner edge of the tab", cap - pad, end, 0.5f)
                assertTrue("$at the start has slid out of the tab", start < origin)
            }
        }
        previous.recycle()
        assertTrue("$where several labels outgrow the row", overflowing > 1)
    }

    @Test fun typing_past_the_row_keeps_the_newest_letter_in_view_in_portrait() = assertTypingKeepsTheNewestLetterInView(360)

    @Config(qualifiers = "w740dp-h360dp-land-xhdpi")
    @Test fun typing_past_the_row_keeps_the_newest_letter_in_view_in_landscape() = assertTypingKeepsTheNewestLetterInView(740)

    private fun assertDraggingRevealsTheStart(widthDp: Int) = forEachLayout(widthDp) { iv, _, origin, _, where ->
        var taps = 0
        iv.onPreeditTap = { taps++ }
        show(iv, widthDp, all)
        val tab = iv.preeditTabForTest()
        val window = iv.preeditTabBoundsInWindow()
        val followed = iv.preeditTextStartForTest()
        val range = origin - followed
        assertTrue("$where precondition: the label follows its end", range > slop + dp(40))
        drag(iv, tab.centerX(), (slop + dp(20)).toFloat())
        val partial = iv.preeditTextStartForTest()
        assertTrue("$where a short drag reveals part of the start", partial > followed && partial < origin)
        drag(iv, tab.centerX(), range + dp(400))
        assertEquals("$where a long drag stops at the start", origin, iv.preeditTextStartForTest(), 0f)
        drag(iv, tab.centerX(), -range - dp(400))
        assertEquals("$where a long drag back stops at the end", followed, iv.preeditTextStartForTest(), 0.5f)
        assertEquals("$where the text keeps its size", base, iv.preeditTextSizeForTest(), 0f)
        assertEquals("$where dragging leaves the tab alone", tab, iv.preeditTabForTest())
        assertEquals("$where dragging leaves the touchable tab alone", window, iv.preeditTabBoundsInWindow())
        assertEquals("$where a drag is not a tap", 0, taps)
    }

    @Test fun dragging_reveals_the_start_and_stops_at_both_ends_in_portrait() = assertDraggingRevealsTheStart(360)

    @Config(qualifiers = "w740dp-h360dp-land-xhdpi")
    @Test fun dragging_reveals_the_start_and_stops_at_both_ends_in_landscape() = assertDraggingRevealsTheStart(740)

    private fun assertTypingAfterADragReturnsToTheEnd(widthDp: Int) = forEachLayout(widthDp) { iv, _, origin, cap, where ->
        show(iv, widthDp, all - 1)
        val tab = iv.preeditTabForTest()
        drag(iv, tab.centerX(), origin - iv.preeditTextStartForTest() + dp(400))
        assertEquals("$where the drag shows the start", origin, iv.preeditTextStartForTest(), 0f)
        show(iv, widthDp, all - 1)
        assertEquals("$where showing the same label again keeps the dragged position", origin, iv.preeditTextStartForTest(), 0f)
        show(iv, widthDp, all)
        val end = iv.preeditTextStartForTest() + idlePaint.measureText(label(all))
        assertEquals("$where the next key returns to the newest letter", cap - pad, end, 0.5f)
    }

    @Test fun typing_after_a_drag_returns_to_the_newest_letter_in_portrait() = assertTypingAfterADragReturnsToTheEnd(360)

    @Config(qualifiers = "w740dp-h360dp-land-xhdpi")
    @Test fun typing_after_a_drag_returns_to_the_newest_letter_in_landscape() = assertTypingAfterADragReturnsToTheEnd(740)

    private fun assertTapsHitAScrolledLabel(widthDp: Int) = forEachLayout(widthDp) { iv, nine, origin, _, where ->
        var taps = 0
        val carets = mutableListOf<Int>()
        iv.onPreeditTap = { taps++ }
        iv.onPreeditCaret = { carets.add(it) }
        show(iv, widthDp, all)
        val idle = iv.preeditTabForTest()
        val followed = iv.preeditTextStartForTest()
        drag(iv, idle.centerX(), (slop + dp(20)).toFloat())
        assertTrue("$where precondition: the idle label is scrolled", iv.preeditTextStartForTest() > followed)
        tap(iv, idle.centerX())
        assertEquals("$where a tap on a scrolled label enters editing", 1, taps)

        val text = label(all)
        val editing = model(all, nine, raw(all, nine).length)
        show(iv, widthDp, all, editing)
        assertTrue("$where the label is being edited", iv.isPreeditEditing())
        assertTrue("$where precondition: editing follows the caret at the end", iv.preeditTextStartForTest() < origin)
        val grip = (origin + iv.preeditDoneLeftForTest()) / 2f
        drag(iv, grip, editPaint.measureText(text) + dp(400))
        assertEquals("$where a long drag stops at the start", origin, iv.preeditTextStartForTest(), 0f)
        show(iv, widthDp, all, model(all, nine, raw(all, nine).length))
        assertEquals("$where showing the same caret again keeps the dragged position", origin, iv.preeditTextStartForTest(), 0f)
        tap(iv, origin + editPaint.measureText(text, 0, 5))
        assertEquals("$where a tap lands on the boundary under the finger", listOf(editing.rawIndexForDisplay(5)), carets)
        assertEquals("$where a tap while editing does not report an idle tap", 1, taps)
    }

    @Test fun taps_hit_a_scrolled_label_in_portrait() = assertTapsHitAScrolledLabel(360)

    @Config(qualifiers = "w740dp-h360dp-land-xhdpi")
    @Test fun taps_hit_a_scrolled_label_in_landscape() = assertTapsHitAScrolledLabel(740)

    private fun assertCaretInView(iv: InputView, m: PreeditModel, origin: Float, at: String) {
        val x = iv.preeditBoundaryXForTest(m.displayCaret())
        assertTrue("$at the caret at $x stays between the text origin and the back label", x >= origin - 0.5f && x <= iv.preeditDoneLeftForTest())
    }

    private fun assertEditingKeepsTheCaretInView(widthDp: Int) = forEachLayout(widthDp) { iv, nine, origin, _, where ->
        val length = raw(all, nine).length
        for (caret in listOf(length, length / 2, 3, 0, length)) {
            val m = model(all, nine, caret)
            show(iv, widthDp, all, m)
            assertCaretInView(iv, m, origin, "$where caret=$caret")
        }
        val near = model(all, nine, 3)
        show(iv, widthDp, all, near)
        val grip = (origin + iv.preeditDoneLeftForTest()) / 2f
        drag(iv, grip, -editPaint.measureText(label(all)) - dp(400))
        assertTrue(
            "$where precondition: the drag slid the caret out of the tab",
            iv.preeditBoundaryXForTest(near.displayCaret()) < iv.preeditTabForTest().left,
        )
        val moved = model(all, nine, 4)
        show(iv, widthDp, all, moved)
        assertCaretInView(iv, moved, origin, "$where caret=4 after a drag")
    }

    @Test fun editing_keeps_the_caret_in_view_in_portrait() = assertEditingKeepsTheCaretInView(360)

    @Config(qualifiers = "w740dp-h360dp-land-xhdpi")
    @Test fun editing_keeps_the_caret_in_view_in_landscape() = assertEditingKeepsTheCaretInView(740)

    private fun assertAFlickCoasts(widthDp: Int) = forEachLayout(widthDp) { iv, _, origin, _, where ->
        var taps = 0
        iv.onPreeditTap = { taps++ }
        show(iv, widthDp, all)
        val tab = iv.preeditTabForTest()
        val window = iv.preeditTabBoundsInWindow()
        val followed = iv.preeditTextStartForTest()
        assertTrue("$where precondition: the label has room to coast", origin - followed > dp(300))
        assertFalse("$where nothing moves before a touch", iv.preeditFlingingForTest())

        flick(iv, tab.centerX(), dp(60).toFloat())
        val released = iv.preeditTextStartForTest()
        assertTrue("$where the flick itself reveals part of the start", released > followed && released < origin)
        assertTrue("$where lifting a moving finger starts a fling", iv.preeditFlingingForTest())
        val coast = settle(iv, where)
        assertTrue("$where the fling takes several frames", coast.size > 3)
        assertEquals("$where every frame moves the same way", coast.sorted(), coast)
        assertTrue("$where the first frame already moves on", coast.first() > released)
        assertTrue("$where the label coasts past where the finger left it", coast.last() > released + dp(10))
        assertTrue("$where it comes to rest before the start", coast.last() < origin)
        assertEquals("$where it stays at rest", coast.last(), frame(iv), 0f)

        drag(iv, tab.centerX(), origin - followed + dp(400))
        drag(iv, tab.centerX(), -dp(150).toFloat())
        flick(iv, tab.centerX(), dp(100).toFloat())
        assertTrue("$where precondition: the finger left the label short of the start", iv.preeditTextStartForTest() < origin)
        val toStart = settle(iv, where)
        assertTrue("$where the fling reaches the start over several frames", toStart.size > 1)
        assertTrue("$where no frame passes the start", toStart.all { it <= origin })
        assertEquals("$where the fling stops at the start", origin, toStart.last(), 0f)
        assertEquals("$where it stays at the start", origin, frame(iv), 0f)

        drag(iv, tab.centerX(), -(origin - followed) - dp(400))
        drag(iv, tab.centerX(), dp(150).toFloat())
        flick(iv, tab.centerX(), -dp(100).toFloat())
        assertTrue("$where precondition: the finger left the label short of the end", iv.preeditTextStartForTest() > followed + 1f)
        val toEnd = settle(iv, where)
        assertTrue("$where the fling reaches the end over several frames", toEnd.size > 1)
        assertTrue("$where no frame passes the end", toEnd.all { it >= followed })
        assertEquals("$where the fling stops at the end", followed, toEnd.last(), 1f)
        assertEquals("$where it stays at the end", toEnd.last(), frame(iv), 0f)

        assertEquals("$where the text keeps its size", base, iv.preeditTextSizeForTest(), 0f)
        assertEquals("$where a fling leaves the tab alone", tab, iv.preeditTabForTest())
        assertEquals("$where a fling leaves the touchable tab alone", window, iv.preeditTabBoundsInWindow())
        assertEquals("$where a flick is not a tap", 0, taps)
    }

    @Test fun a_flick_coasts_and_stops_inside_the_label_in_portrait() = assertAFlickCoasts(360)

    @Config(qualifiers = "w740dp-h360dp-land-xhdpi")
    @Test fun a_flick_coasts_and_stops_inside_the_label_in_landscape() = assertAFlickCoasts(740)

    private fun assertADragReleasedAtRestDoesNotCoast(widthDp: Int) = forEachLayout(widthDp) { iv, _, origin, _, where ->
        show(iv, widthDp, all)
        val followed = iv.preeditTextStartForTest()
        drag(iv, iv.preeditTabForTest().centerX(), dp(60).toFloat())
        val released = iv.preeditTextStartForTest()
        assertTrue("$where precondition: the drag moved the label", released > followed && released < origin)
        assertFalse("$where a finger that rests before lifting starts no fling", iv.preeditFlingingForTest())
        repeat(3) { assertEquals("$where the label stays where the finger left it", released, frame(iv), 0f) }
    }

    @Test fun a_drag_released_at_rest_does_not_coast_in_portrait() = assertADragReleasedAtRestDoesNotCoast(360)

    @Config(qualifiers = "w740dp-h360dp-land-xhdpi")
    @Test fun a_drag_released_at_rest_does_not_coast_in_landscape() = assertADragReleasedAtRestDoesNotCoast(740)

    private fun assertAFlickHeldBeforeLiftingDoesNotCoast(widthDp: Int) = forEachLayout(widthDp) { iv, nine, origin, _, where ->
        var taps = 0
        val carets = mutableListOf<Int>()
        iv.onPreeditTap = { taps++ }
        iv.onPreeditCaret = { carets.add(it) }
        show(iv, widthDp, all)
        val tab = iv.preeditTabForTest()
        val followed = iv.preeditTextStartForTest()
        assertTrue("$where precondition: the label has room to coast", origin - followed > dp(300))
        var before = followed
        for (hold in listOf(600L, 300L)) {
            val at = "$where hold=$hold"
            flickThenHold(iv, tab.centerX(), dp(60).toFloat(), hold)
            val released = iv.preeditTextStartForTest()
            assertEquals("$at the label follows the finger until it lifts", before + dp(60), released, 0.5f)
            assertFalse("$at a finger held still before lifting starts no fling", iv.preeditFlingingForTest())
            repeat(20) { assertEquals("$at the label stays where the finger left it", released, frame(iv), 0f) }
            before = released
        }
        assertEquals("$where a held flick is not a tap", 0, taps)

        flick(iv, tab.centerX(), dp(60).toFloat())
        assertTrue("$where the same flick lifted while moving still starts a fling", iv.preeditFlingingForTest())
        val released = iv.preeditTextStartForTest()
        assertTrue("$where it still coasts past where the finger left it", settle(iv, where).last() > released + dp(10))

        show(iv, widthDp, all, model(all, nine, raw(all, nine).length))
        assertTrue("$where the label is being edited", iv.isPreeditEditing())
        val edited = iv.preeditTextStartForTest()
        assertTrue("$where precondition: the edited label has room to coast", origin - edited > dp(300))
        flickThenHold(iv, (origin + iv.preeditDoneLeftForTest()) / 2f, dp(100).toFloat(), 600L)
        val held = iv.preeditTextStartForTest()
        assertEquals("$where the edited label follows the finger until it lifts", edited + dp(100), held, 0.5f)
        assertFalse("$where a finger held still before lifting starts no fling while editing", iv.preeditFlingingForTest())
        repeat(20) { assertEquals("$where the edited label stays where the finger left it", held, frame(iv), 0f) }
        assertEquals("$where a held flick moves no caret", emptyList<Int>(), carets)
        assertEquals("$where a held flick is not a tap", 0, taps)
    }

    @Test fun a_flick_held_still_before_lifting_does_not_coast_in_portrait() = assertAFlickHeldBeforeLiftingDoesNotCoast(360)

    @Config(qualifiers = "w740dp-h360dp-land-xhdpi")
    @Test fun a_flick_held_still_before_lifting_does_not_coast_in_landscape() = assertAFlickHeldBeforeLiftingDoesNotCoast(740)

    private fun assertAPressStopsAFling(widthDp: Int) = forEachLayout(widthDp) { iv, _, _, _, where ->
        var taps = 0
        iv.onPreeditTap = { taps++ }
        show(iv, widthDp, all)
        val tab = iv.preeditTabForTest()
        flick(iv, tab.centerX(), dp(100).toFloat())
        val first = frame(iv)
        val second = frame(iv)
        assertTrue("$where precondition: the label is coasting", second > first && iv.preeditFlingingForTest())
        tap(iv, tab.centerX())
        assertFalse("$where a press stops the fling", iv.preeditFlingingForTest())
        assertEquals("$where the press that stops a fling is not a tap", 0, taps)
        repeat(3) { assertEquals("$where the label stays where it was caught", second, frame(iv), 0f) }
        tap(iv, tab.centerX())
        assertEquals("$where the next tap counts", 1, taps)
    }

    @Test fun a_press_stops_a_fling_without_tapping_in_portrait() = assertAPressStopsAFling(360)

    @Config(qualifiers = "w740dp-h360dp-land-xhdpi")
    @Test fun a_press_stops_a_fling_without_tapping_in_landscape() = assertAPressStopsAFling(740)

    private fun assertTextChangesEndAFling(widthDp: Int) = forEachLayout(widthDp) { iv, _, _, cap, where ->
        show(iv, widthDp, all - 1)
        val tab = iv.preeditTabForTest()
        for (count in listOf(all, all - 1)) {
            flick(iv, tab.centerX(), dp(100).toFloat())
            val first = frame(iv)
            assertTrue("$where precondition: the label is coasting", frame(iv) > first && iv.preeditFlingingForTest())
            show(iv, widthDp, count)
            val at = "$where syllables=$count"
            assertFalse("$at a changed label ends the fling", iv.preeditFlingingForTest())
            val start = iv.preeditTextStartForTest()
            assertEquals("$at the label returns to its newest letter", cap - pad, start + idlePaint.measureText(label(count)), 0.5f)
            repeat(3) { assertEquals("$at the label stays on its newest letter", start, frame(iv), 0f) }
        }
        flick(iv, tab.centerX(), dp(100).toFloat())
        frame(iv)
        assertTrue("$where precondition: the label is coasting", iv.preeditFlingingForTest())
        iv.showCandidates(emptyList(), "", emptyList())
        assertFalse("$where clearing the label ends the fling", iv.preeditFlingingForTest())
    }

    @Test fun a_text_change_ends_a_fling_and_follows_the_newest_letter_in_portrait() = assertTextChangesEndAFling(360)

    @Config(qualifiers = "w740dp-h360dp-land-xhdpi")
    @Test fun a_text_change_ends_a_fling_and_follows_the_newest_letter_in_landscape() = assertTextChangesEndAFling(740)

    private fun assertEditingCoastsAndFollowsTheCaret(widthDp: Int) = forEachLayout(widthDp) { iv, nine, origin, _, where ->
        val carets = mutableListOf<Int>()
        iv.onPreeditCaret = { carets.add(it) }
        val text = label(all)
        val length = raw(all, nine).length
        val editing = model(all, nine, length)
        show(iv, widthDp, all, editing)
        val followed = iv.preeditTextStartForTest()
        assertTrue("$where precondition: the edited label has room to coast", origin - followed > dp(300))
        val grip = (origin + iv.preeditDoneLeftForTest()) / 2f

        flick(iv, grip, dp(100).toFloat())
        val released = iv.preeditTextStartForTest()
        assertTrue("$where lifting a moving finger starts a fling", iv.preeditFlingingForTest())
        val coast = settle(iv, where)
        assertEquals("$where every frame moves the same way", coast.sorted(), coast)
        assertTrue("$where the edited label coasts past where the finger left it", coast.last() > released + dp(10))
        assertTrue("$where it comes to rest before the start", coast.last() < origin)

        val boundary = (0..text.length).minByOrNull { kotlin.math.abs(iv.preeditBoundaryXForTest(it) - grip) }!!
        tap(iv, iv.preeditBoundaryXForTest(boundary))
        assertEquals("$where a tap lands on the boundary under the finger", listOf(editing.rawIndexForDisplay(boundary)), carets)

        val near = model(all, nine, 3)
        show(iv, widthDp, all, near)
        assertCaretInView(iv, near, origin, "$where caret=3 after a fling")

        show(iv, widthDp, all, model(all, nine, length))
        assertEquals("$where the caret at the end is followed again", followed, iv.preeditTextStartForTest(), 0f)
        flick(iv, grip, dp(100).toFloat())
        val first = frame(iv)
        assertTrue("$where precondition: the edited label is coasting", frame(iv) > first && iv.preeditFlingingForTest())
        val moved = model(all, nine, length - 1)
        show(iv, widthDp, all, moved)
        assertFalse("$where a moved caret ends the fling", iv.preeditFlingingForTest())
        assertCaretInView(iv, moved, origin, "$where caret=${length - 1} during a fling")
        val start = iv.preeditTextStartForTest()
        repeat(3) { assertEquals("$where the label stays on its caret", start, frame(iv), 0f) }
    }

    @Test fun editing_coasts_and_still_follows_the_caret_in_portrait() = assertEditingCoastsAndFollowsTheCaret(360)

    @Config(qualifiers = "w740dp-h360dp-land-xhdpi")
    @Test fun editing_coasts_and_still_follows_the_caret_in_landscape() = assertEditingCoastsAndFollowsTheCaret(740)

    private fun assertAFittingLabelDoesNotCoast(widthDp: Int) = forEachLayout(widthDp) { iv, _, origin, cap, where ->
        var taps = 0
        iv.onPreeditTap = { taps++ }
        var fitting = 0
        for (count in 1..all) {
            if (!fits(count, origin, cap)) break
            fitting++
            show(iv, widthDp, count)
            val tab = iv.preeditTabForTest()
            val at = "$where syllables=$count"
            for (by in listOf(dp(200), -dp(200))) {
                flick(iv, tab.centerX(), by.toFloat())
                assertFalse("$at a flick starts no fling", iv.preeditFlingingForTest())
                assertEquals("$at a flick moves nothing", origin, frame(iv), 0f)
            }
            assertEquals("$at a flick leaves the tab alone", tab, iv.preeditTabForTest())
        }
        assertTrue("$where several labels fit", fitting > 1)
        assertEquals("$where a flick is not a tap", 0, taps)
        tap(iv, iv.preeditTabForTest().centerX())
        assertEquals("$where a fitting label still takes a tap", 1, taps)
    }

    @Test fun a_label_that_fits_does_not_coast_in_portrait() = assertAFittingLabelDoesNotCoast(360)

    @Config(qualifiers = "w740dp-h360dp-land-xhdpi")
    @Test fun a_label_that_fits_does_not_coast_in_landscape() = assertAFittingLabelDoesNotCoast(740)

    @Test fun detaching_ends_a_fling() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            for (nine in listOf(false, true)) {
                val iv = input(nine)
                val host = FrameLayout(controller.get())
                host.addView(iv)
                controller.get().setContentView(host)
                show(iv, 360, all)
                flick(iv, iv.preeditTabForTest().centerX(), dp(100).toFloat())
                assertTrue("nine=$nine precondition: a fling is running", iv.preeditFlingingForTest())
                host.removeView(iv)
                assertFalse("nine=$nine a detached label ends its fling", iv.preeditFlingingForTest())
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
