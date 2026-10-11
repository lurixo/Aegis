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
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CandidateGridWideRowTest {

    private enum class Entry { ALPHA, NINE }

    private val ctx = RuntimeEnvironment.getApplication()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val wide = "很长的自造词".repeat(12)
    private val otherWide = "另一个很长的词".repeat(12)
    private val singles = (0 until 60).map { String(Character.toChars(0x4E00 + it)) }
    private val wideSourceIndex = 2

    private fun words(wideWord: String = wide) = listOf("你", "你好", wideWord, "你们") + singles

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

    private fun dp(value: Float): Float = value * ctx.resources.displayMetrics.density

    private fun frames(iv: InputView, row: HorizontalScrollView, count: Int) {
        val grid = iv.expandedGridForTest()
        val canvas = Canvas(Bitmap.createBitmap(grid.width, grid.height, Bitmap.Config.ARGB_8888))
        val end = chipOf(row).width - row.width
        repeat(count) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            grid.draw(canvas)
            assertTrue("the row stays between its two ends: ${row.scrollX} of $end", row.scrollX in 0..end)
        }
    }

    private fun rest(iv: InputView, row: HorizontalScrollView) = frames(iv, row, 200)

    private fun layout(iv: InputView) {
        val metrics = ctx.resources.displayMetrics
        iv.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.AT_MOST),
        )
        iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)
    }

    private fun show(iv: InputView, entry: Entry, candidates: List<String>) {
        iv.showCandidates(
            candidates,
            "nihao",
            if (entry == Entry.NINE) listOf("ni", "mi", "ne") else emptyList(),
            candidateProjection = CandidateProjectionPolicy.PINYIN,
        )
    }

    private fun expand(iv: InputView) {
        iv.showExpandedCandidates()
        settle()
        layout(iv)
        assertTrue(iv.isPanelShowing(iv.expandedGridForTest()))
    }

    private fun open(entry: Entry, picked: MutableList<Int>): InputView {
        val iv = InputView(ctx).apply {
            val layout = when (entry) {
                Entry.ALPHA -> Layouts.forId(LayoutId.ALPHA, Lang.CN)
                Entry.NINE -> Layouts.nine(Layouts.ninePunctuation(), composing = true)
            }
            showKeyboard(layout, false, false, Lang.CN)
            onPickCandidate = { picked += it }
        }
        show(iv, entry, words())
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(iv)
        layout(iv)
        expand(iv)
        return iv
    }

    private fun wideRow(iv: InputView, text: String = wide): HorizontalScrollView {
        val grid = iv.expandedGridForTest()
        val row = grid.rowTextsForTest().indexOf(listOf(text))
        assertTrue("the wide candidate has a row to itself: ${grid.rowTextsForTest().take(4)}", row >= 0)
        val view = grid.candidateRowViewForTest(row) as HorizontalScrollView
        assertTrue("the wide row is on screen", view.isAttachedToWindow && view.width > 0)
        return view
    }

    private fun chipOf(row: HorizontalScrollView) = row.getChildAt(0) as TextView

    private fun boundsInRoot(iv: InputView, view: View): Rect =
        Rect().also {
            view.getDrawingRect(it)
            iv.offsetDescendantRectToMyCoords(view, it)
        }

    private inner class Gesture(private val iv: InputView, private var x: Float, private var y: Float) {
        private val down = SystemClock.uptimeMillis()
        private var time = down

        init {
            send(MotionEvent.ACTION_DOWN)
        }

        fun moveBy(dx: Float, dy: Float, ms: Long = 16) {
            x += dx
            y += dy
            time += ms
            send(MotionEvent.ACTION_MOVE)
        }

        fun hold(ms: Long) {
            time += ms
        }

        fun release() {
            time += 16
            send(MotionEvent.ACTION_UP)
        }

        fun up() {
            release()
            settle()
        }

        private fun send(action: Int) {
            val event = MotionEvent.obtain(down, time, action, x, y, 0)
            iv.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun press(iv: InputView, row: HorizontalScrollView): Gesture {
        val bounds = boundsInRoot(iv, row)
        return Gesture(iv, bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun dragToEnd(gesture: Gesture, row: HorizontalScrollView) {
        var moves = 0
        while (row.canScrollHorizontally(1) && moves < 400) {
            gesture.moveBy(-row.width / 4f, 0f)
            moves++
        }
    }

    private fun staysWhole(entry: Entry) {
        val iv = open(entry, ArrayList())
        val grid = iv.expandedGridForTest()
        val row = wideRow(iv)
        val chip = chipOf(row)
        val base = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ImeType.candidate, ctx.resources.displayMetrics)

        assertEquals("$entry: the wide candidate keeps the candidate size", base, chip.textSize, 0.01f)
        assertNull("$entry: the wide candidate is not ellipsized", chip.ellipsize)
        assertEquals("$entry: the wide candidate stays on one line", 1, chip.layout.lineCount)
        assertEquals("$entry: no character is elided", 0, chip.layout.getEllipsisCount(0))
        assertEquals("$entry: the whole text is shown", wide, chip.text.toString())
        assertEquals(
            "$entry: the cell is as wide as its whole text",
            chip.paint.measureText(wide) + chip.paddingLeft + chip.paddingRight,
            chip.width.toFloat(),
            1f,
        )
        assertTrue("$entry: the text is wider than the row", chip.width > row.width)
        assertEquals("$entry: the row keeps the common row height", grid.candidateRowHeightForTest(), row.height)
        assertEquals("$entry: the row opens on the start of the candidate", 0, row.scrollX)
        val neighbour = grid.candidateRowViewForTest(0).getChildAt(0) as TextView
        assertEquals("$entry: its neighbours keep the same size", base, neighbour.textSize, 0.01f)
    }

    private fun sidewaysDragRevealsTheEnd(entry: Entry) {
        val picked = ArrayList<Int>()
        val iv = open(entry, picked)
        val grid = iv.expandedGridForTest()
        val row = wideRow(iv)
        val chip = chipOf(row)
        val firstRow = grid.firstVisibleCandidateRowForTest()
        val firstTop = grid.firstVisibleCandidateTopForTest()

        val gesture = press(iv, row)
        assertTrue("$entry: pressing the wide candidate shows the press", chip.isPressed)
        gesture.moveBy(-(slop - 1f), 0f)
        assertEquals("$entry: a move inside the touch slop does not scroll", 0, row.scrollX)
        assertTrue("$entry: a move inside the touch slop keeps the press", chip.isPressed)
        dragToEnd(gesture, row)

        assertFalse("$entry: dragging releases the press", chip.isPressed)
        assertEquals("$entry: the row scrolls by what did not fit", chip.width - row.width, row.scrollX)
        assertEquals("$entry: the end of the candidate reaches the row edge", row.width, chip.right - row.scrollX)
        assertFalse("$entry: nothing is left beyond the end", row.canScrollHorizontally(1))
        assertEquals("$entry: the panel did not move", firstRow, grid.firstVisibleCandidateRowForTest())
        assertEquals("$entry: the panel did not move", firstTop, grid.firstVisibleCandidateTopForTest())
        gesture.up()
        assertEquals("$entry: a drag does not pick the candidate", emptyList<Int>(), picked)
        rest(iv, row)
        assertEquals("$entry: a flick against the end leaves the row at its end", chip.width - row.width, row.scrollX)

        press(iv, row).up()
        assertEquals("$entry: a tap on the scrolled candidate still picks it", listOf(wideSourceIndex), picked)
    }

    private fun flick(iv: InputView, row: HorizontalScrollView, step: Float = -dp(20f)) {
        val gesture = press(iv, row)
        repeat(4) { gesture.moveBy(step, 0f) }
        gesture.release()
    }

    private fun coast(iv: InputView, row: HorizontalScrollView): Int {
        flick(iv, row)
        frames(iv, row, 2)
        val before = row.scrollX
        frames(iv, row, 1)
        assertTrue("precondition: the row is coasting", row.scrollX > before)
        assertTrue("precondition: the row is short of its end", row.canScrollHorizontally(1))
        return row.scrollX
    }

    private fun flickCoastsAndStops(entry: Entry) {
        val picked = ArrayList<Int>()
        val iv = open(entry, picked)
        val grid = iv.expandedGridForTest()
        val row = wideRow(iv)
        val end = chipOf(row).width - row.width
        val firstRow = grid.firstVisibleCandidateRowForTest()
        val firstTop = grid.firstVisibleCandidateTopForTest()

        flick(iv, row)
        val released = row.scrollX
        assertEquals("$entry: the row follows the finger until it lifts", dp(80f).toInt(), released)
        frames(iv, row, 1)
        val early = row.scrollX - released
        assertTrue("$entry: the row keeps moving after a flick", early > 0)
        frames(iv, row, 14)
        val coasting = row.scrollX
        frames(iv, row, 1)
        val late = row.scrollX - coasting
        assertTrue("$entry: the row slows down as it coasts: $early then $late", late in 0 until early)
        rest(iv, row)
        val stopped = row.scrollX
        assertTrue("$entry: the row coasts further before it stops", stopped > coasting)
        assertTrue("$entry: a moderate flick stops short of the end: $stopped of $end", stopped < end)
        frames(iv, row, 10)
        assertEquals("$entry: the row stays where it stopped", stopped, row.scrollX)
        assertEquals("$entry: a flick does not pick the candidate", emptyList<Int>(), picked)
        assertEquals("$entry: the panel did not move", firstRow, grid.firstVisibleCandidateRowForTest())
        assertEquals("$entry: the panel did not move", firstTop, grid.firstVisibleCandidateTopForTest())

        flick(iv, row, -row.width / 2f)
        rest(iv, row)
        assertEquals("$entry: a hard flick stops at the end of the candidate", end, row.scrollX)
        flick(iv, row, row.width / 2f)
        rest(iv, row)
        assertEquals("$entry: a hard flick back stops at the start of the candidate", 0, row.scrollX)
        assertEquals("$entry: flicks do not pick the candidate", emptyList<Int>(), picked)

        press(iv, row).up()
        assertEquals("$entry: a tap after the row has stopped picks the candidate", listOf(wideSourceIndex), picked)
    }

    private fun slowDragStopsOnRelease(entry: Entry) {
        val picked = ArrayList<Int>()
        val iv = open(entry, picked)
        val row = wideRow(iv)

        val gesture = press(iv, row)
        repeat(8) { gesture.moveBy(-slop / 4f, 0f, ms = 200) }
        gesture.release()
        val released = row.scrollX
        assertEquals("$entry: the row follows the slow drag", 2 * slop, released)
        frames(iv, row, 20)
        assertEquals("$entry: a slow drag does not coast after release", released, row.scrollX)
        assertEquals("$entry: a slow drag does not pick the candidate", emptyList<Int>(), picked)

        press(iv, row).up()
        assertEquals("$entry: a tap right after a slow drag picks the candidate", listOf(wideSourceIndex), picked)
    }

    private fun flickHeldBeforeLiftingStopsOnRelease(entry: Entry) {
        val picked = ArrayList<Int>()
        val iv = open(entry, picked)
        val row = wideRow(iv)
        var before = 0

        for (ms in listOf(600L, 300L)) {
            val gesture = press(iv, row)
            repeat(4) { gesture.moveBy(-dp(20f), 0f) }
            gesture.hold(ms)
            gesture.release()
            val released = row.scrollX
            assertEquals("$entry hold=$ms: the row follows the finger until it lifts", before + dp(80f).toInt(), released)
            frames(iv, row, 20)
            assertEquals("$entry hold=$ms: a finger held still before lifting leaves no coast", released, row.scrollX)
            assertEquals("$entry hold=$ms: a held flick does not pick the candidate", emptyList<Int>(), picked)
            before = released
        }

        press(iv, row).up()
        assertEquals("$entry: a tap right after a held flick picks the candidate", listOf(wideSourceIndex), picked)
        assertEquals("$entry: that tap does not move the row", before, row.scrollX)

        flick(iv, row)
        val released = row.scrollX
        frames(iv, row, 1)
        assertTrue("$entry: the same flick lifted while moving still coasts", row.scrollX > released)
    }

    private fun pressStopsTheCoast(entry: Entry) {
        val picked = ArrayList<Int>()
        val iv = open(entry, picked)
        val row = wideRow(iv)
        val chip = chipOf(row)

        val held = coast(iv, row)
        val stop = press(iv, row)
        assertTrue("$entry: the press that stops the row is shown", chip.isPressed)
        frames(iv, row, 20)
        assertEquals("$entry: pressing the row stops it", held, row.scrollX)
        stop.up()
        assertFalse("$entry: the press is released", chip.isPressed)
        assertEquals("$entry: the touch that stops the row does not pick the candidate", emptyList<Int>(), picked)
        frames(iv, row, 20)
        assertEquals("$entry: the row stays stopped after the touch", held, row.scrollX)

        press(iv, row).up()
        assertEquals("$entry: the next tap picks the candidate", listOf(wideSourceIndex), picked)

        val again = coast(iv, row)
        val drag = press(iv, row)
        repeat(3) { drag.moveBy(-slop.toFloat(), 0f, ms = 1000) }
        assertEquals("$entry: the touch that stops the row can drag it on", again + 3 * slop, row.scrollX)
        drag.release()
        frames(iv, row, 20)
        assertEquals(again + 3 * slop, row.scrollX)
        assertEquals("$entry: dragging on does not pick the candidate", listOf(wideSourceIndex), picked)
    }

    private fun verticalDragDuringTheCoastScrollsThePanel(entry: Entry) {
        val picked = ArrayList<Int>()
        val iv = open(entry, picked)
        val grid = iv.expandedGridForTest()
        val row = wideRow(iv)
        assertEquals(0, grid.firstVisibleCandidateRowForTest())
        assertEquals(0, grid.firstVisibleCandidateTopForTest())

        val held = coast(iv, row)
        val gesture = press(iv, row)
        repeat(8) { gesture.moveBy(0f, -grid.candidateRowStrideForTest() / 2f) }

        assertTrue(
            "$entry: an upward drag that starts on the coasting row scrolls the panel",
            grid.firstVisibleCandidateRowForTest() > 0 || grid.firstVisibleCandidateTopForTest()!! < 0,
        )
        assertEquals("$entry: the drag stops the row where it was", held, row.scrollX)
        gesture.up()
        assertEquals("$entry: a vertical drag does not pick the candidate", emptyList<Int>(), picked)
    }

    private fun startsOverAtOnce(iv: InputView, row: HorizontalScrollView, what: String) {
        assertEquals("$what starts the coasting row over", 0, row.scrollX)
        frames(iv, row, 20)
        assertEquals("$what leaves no coast behind", 0, row.scrollX)
    }

    private fun coastingRowStartsOver(entry: Entry) {
        val picked = ArrayList<Int>()
        val iv = open(entry, picked)
        val grid = iv.expandedGridForTest()
        val row = wideRow(iv)

        coast(iv, row)
        show(iv, entry, words(otherWide))
        layout(iv)
        assertSame("$entry: the row view is reused for the new wide candidate", row, wideRow(iv, otherWide))
        startsOverAtOnce(iv, row, "$entry: new candidates")
        press(iv, row).up()
        assertEquals("$entry: a tap right after new candidates picks", listOf(wideSourceIndex), picked)

        coast(iv, row)
        grid.scrollForTest(grid.candidateRowStrideForTest() * 8)
        layout(iv)
        assertTrue("precondition: the wide row scrolled out of the panel", grid.firstVisibleCandidateRowForTest() > 1)
        grid.scrollForTest(0)
        layout(iv)
        val back = wideRow(iv, otherWide)
        startsOverAtOnce(iv, back, "$entry: scrolling the row out and back")
        press(iv, back).up()
        assertEquals("$entry: a tap right after the row is back picks", List(2) { wideSourceIndex }, picked)

        coast(iv, back)
        iv.showPanel(null)
        settle()
        layout(iv)
        assertFalse(iv.isPanelShowing(grid))
        expand(iv)
        val reopened = wideRow(iv, otherWide)
        startsOverAtOnce(iv, reopened, "$entry: reopening the panel")
        press(iv, reopened).up()
        assertEquals("$entry: a tap right after reopening picks", List(3) { wideSourceIndex }, picked)

        coast(iv, reopened)
        show(iv, entry, words())
        layout(iv)
        val rebound = wideRow(iv)
        startsOverAtOnce(iv, rebound, "$entry: new candidates")
        val gesture = press(iv, rebound)
        repeat(8) { gesture.moveBy(0f, -grid.candidateRowStrideForTest() / 2f) }
        assertTrue(
            "$entry: an upward drag right after new candidates scrolls the panel",
            grid.firstVisibleCandidateRowForTest() > 0 || grid.firstVisibleCandidateTopForTest()!! < 0,
        )
        gesture.up()
        assertEquals("$entry: that drag does not pick", List(3) { wideSourceIndex }, picked)
    }

    private fun tapPicksTheSourceIndex(entry: Entry) {
        val picked = ArrayList<Int>()
        val iv = open(entry, picked)
        val grid = iv.expandedGridForTest()
        val row = wideRow(iv)

        assertEquals(
            "$entry: the projection moved the wide candidate ahead of its source position",
            1,
            grid.renderedCandidateTextsForTest().indexOf(wide),
        )
        val gesture = press(iv, row)
        gesture.moveBy(slop / 2f, slop / 2f)
        gesture.up()

        assertEquals("$entry: a tap picks the wide candidate by its source index", listOf(wideSourceIndex), picked)
        assertEquals("$entry: a tap does not scroll the row", 0, row.scrollX)
        assertFalse("$entry: the press is released after the tap", chipOf(row).isPressed)
    }

    private fun verticalDragScrollsThePanel(entry: Entry) {
        val picked = ArrayList<Int>()
        val iv = open(entry, picked)
        val grid = iv.expandedGridForTest()
        val row = wideRow(iv)
        assertEquals(0, grid.firstVisibleCandidateRowForTest())
        assertEquals(0, grid.firstVisibleCandidateTopForTest())

        val gesture = press(iv, row)
        repeat(8) { gesture.moveBy(0f, -grid.candidateRowStrideForTest() / 2f) }

        assertTrue(
            "$entry: an upward drag that starts on the wide candidate scrolls the panel",
            grid.firstVisibleCandidateRowForTest() > 0 || grid.firstVisibleCandidateTopForTest()!! < 0,
        )
        assertEquals("$entry: a vertical drag does not scroll the row sideways", 0, row.scrollX)
        gesture.up()
        assertEquals("$entry: a vertical drag does not pick the candidate", emptyList<Int>(), picked)
    }

    private fun scrollSome(iv: InputView, row: HorizontalScrollView) {
        val gesture = press(iv, row)
        repeat(3) { gesture.moveBy(-row.width / 4f, 0f) }
        gesture.up()
        assertTrue("precondition: the row is scrolled away from its start", row.scrollX > 0)
    }

    private fun returnsToTheStart(entry: Entry) {
        val iv = open(entry, ArrayList())
        val grid = iv.expandedGridForTest()
        val row = wideRow(iv)

        scrollSome(iv, row)
        show(iv, entry, words(otherWide))
        layout(iv)
        val rebound = wideRow(iv, otherWide)
        assertSame("$entry: the row view is reused for the new wide candidate", row, rebound)
        assertEquals(otherWide, chipOf(rebound).text.toString())
        assertEquals("$entry: new candidates start the row over", 0, rebound.scrollX)
        settle()
        assertEquals("$entry: the rebound row stays at its start", 0, rebound.scrollX)

        scrollSome(iv, rebound)
        grid.scrollForTest(grid.candidateRowStrideForTest() * 8)
        layout(iv)
        assertTrue("precondition: the wide row scrolled out of the panel", grid.firstVisibleCandidateRowForTest() > 1)
        grid.scrollForTest(0)
        layout(iv)
        assertEquals("$entry: a row scrolled back into the panel starts over", 0, wideRow(iv, otherWide).scrollX)

        scrollSome(iv, wideRow(iv, otherWide))
        iv.showPanel(null)
        settle()
        layout(iv)
        assertFalse(iv.isPanelShowing(grid))
        expand(iv)
        assertEquals("$entry: reopening the panel starts the row over", 0, wideRow(iv, otherWide).scrollX)
    }

    @Test fun a_candidate_wider_than_the_row_keeps_the_candidate_size_and_is_not_cut() {
        Entry.entries.forEach(::staysWhole)
    }

    @Test fun dragging_a_wide_candidate_sideways_reveals_its_end_without_picking_it() {
        Entry.entries.forEach(::sidewaysDragRevealsTheEnd)
    }

    @Test fun a_flicked_wide_candidate_coasts_and_stops_between_its_ends() {
        Entry.entries.forEach(::flickCoastsAndStops)
    }

    @Test fun a_slowly_dragged_wide_candidate_stops_when_released() {
        Entry.entries.forEach(::slowDragStopsOnRelease)
    }

    @Test fun a_flicked_wide_candidate_held_still_before_lifting_stops_when_released() {
        Entry.entries.forEach(::flickHeldBeforeLiftingStopsOnRelease)
    }

    @Test fun pressing_a_coasting_wide_candidate_stops_it_without_picking() {
        Entry.entries.forEach(::pressStopsTheCoast)
    }

    @Test fun dragging_up_from_a_coasting_wide_candidate_still_scrolls_the_panel() {
        Entry.entries.forEach(::verticalDragDuringTheCoastScrollsThePanel)
    }

    @Test fun a_coasting_wide_candidate_starts_over_when_its_row_is_rebound() {
        Entry.entries.forEach(::coastingRowStartsOver)
    }

    @Test fun tapping_a_wide_candidate_picks_it_by_its_source_index() {
        Entry.entries.forEach(::tapPicksTheSourceIndex)
    }

    @Test fun dragging_up_from_a_wide_candidate_still_scrolls_the_panel() {
        Entry.entries.forEach(::verticalDragScrollsThePanel)
    }

    @Test fun a_wide_candidate_returns_to_its_start_when_its_row_is_rebound() {
        Entry.entries.forEach(::returnsToTheStart)
    }

    @Config(qualifiers = "w853dp-h388dp-land-hdpi")
    @Test fun a_wide_candidate_scrolls_and_is_picked_the_same_way_in_landscape() {
        for (entry in Entry.entries) {
            staysWhole(entry)
            sidewaysDragRevealsTheEnd(entry)
            tapPicksTheSourceIndex(entry)
            verticalDragScrollsThePanel(entry)
            returnsToTheStart(entry)
        }
    }

    @Config(qualifiers = "w853dp-h388dp-land-hdpi")
    @Test fun a_wide_candidate_coasts_and_stops_the_same_way_in_landscape() {
        for (entry in Entry.entries) {
            flickCoastsAndStops(entry)
            slowDragStopsOnRelease(entry)
            flickHeldBeforeLiftingStopsOnRelease(entry)
            pressStopsTheCoast(entry)
            verticalDragDuringTheCoastScrollsThePanel(entry)
            coastingRowStartsOver(entry)
        }
    }
}
