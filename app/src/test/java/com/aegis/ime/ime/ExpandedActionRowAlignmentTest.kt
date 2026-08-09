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
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ExpandedActionRowAlignmentTest {

    private val portrait = listOf(
        "w411dp-h891dp-xxhdpi",
        "w360dp-h740dp-hdpi",
        "w320dp-h640dp-xhdpi",
    )

    @Test fun the_collapse_covers_row_one_and_the_other_two_centre_on_the_seams() {
        forEachPortraitDock { grid, label ->
            val density = grid.resources.displayMetrics.density
            val rowHeight = grid.candidateRowHeightForTest()
            val separator = grid.tableDividerHeightForTest()
            val rows = grid.visibleCandidateRowsForTest()
            val actions = (0..2).map { grid.actionBoundsForTest(it) }

            assertTrue("$label row height $rowHeight must be at least 48dp", rowHeight >= (48 * density).toInt())
            assertTrue("$label must keep a separator between rows, got $separator", separator >= 1)
            assertEquals("$label the row pitch carries the separator", rowHeight + separator, grid.candidateRowStrideForTest())
            assertTrue("$label must lay out at least five candidate rows: $rows", rows.size >= 5)
            rows.forEach { assertEquals("$label every visible row is one row tall: $rows", rowHeight, it.height()) }
            rows.zipWithNext().forEach { (upper, lower) ->
                assertEquals("$label rows sit one separator apart: $rows", separator, lower.top - upper.bottom)
            }

            val collapse = actions[0]
            val firstRow = rows[0]
            assertEquals("$label the collapse top vs row 1: $collapse vs $firstRow", firstRow.top, collapse.top)
            assertEquals("$label the collapse bottom vs row 1: $collapse vs $firstRow", firstRow.bottom, collapse.bottom)
            assertEquals("$label the collapse centre vs row 1", firstRow.centerY(), collapse.centerY())

            listOf(1 to 2, 2 to 4).forEach { (slot, rowIndex) ->
                val action = actions[slot]
                val seam = rows[rowIndex].bottom + separator / 2
                assertEquals(
                    "$label action $slot centre vs the seam under row ${rowIndex + 1}: $action vs ${rows[rowIndex]}",
                    seam,
                    action.centerY(),
                )
                assertEquals("$label action $slot height", rowHeight, action.height())
                assertTrue("$label action $slot stays inside ${grid.height}: $action", action.bottom <= grid.height)
            }
            assertFalse(
                "$label candidate row 2 must stay clear of every action: ${rows[1]} vs $actions",
                actions.any { it.top < rows[1].bottom && rows[1].top < it.bottom },
            )
        }
    }

    @Test fun taps_reach_each_action_across_its_whole_row_and_the_gaps_stay_dead() {
        forEachPortraitDock { grid, label ->
            val fired = IntArray(3)
            grid.onClose = { fired[0]++ }
            grid.onBackspace = { fired[1]++ }
            grid.onClear = { fired[2]++ }
            val actions = (0..2).map { grid.actionBoundsForTest(it) }
            val rows = grid.visibleCandidateRowsForTest()

            actions.forEachIndexed { slot, action ->
                for (y in listOf(action.top + 1f, action.exactCenterY(), action.bottom - 1f)) {
                    val before = fired.copyOf()
                    assertTrue("$label a tap at ${action.exactCenterX()},$y must be handled", tap(grid, action.exactCenterX(), y))
                    fired.forEachIndexed { other, count ->
                        assertEquals(
                            "$label a tap at ${action.exactCenterX()},$y on action $slot fired action $other",
                            before[other] + if (other == slot) 1 else 0,
                            count,
                        )
                    }
                }
            }

            val quiet = fired.copyOf()
            tap(grid, actions[0].exactCenterX(), rows[1].exactCenterY())
            assertTrue(
                "$label a tap beside candidate row 2 must fire nothing: ${fired.toList()}",
                fired.contentEquals(quiet),
            )
        }
    }

    @Test fun every_candidate_row_seam_still_paints_the_separator() {
        forEachPortraitDock { grid, label ->
            val separatorColor = grid.tableSeparatorColorForTest()
            val rows = grid.visibleCandidateRowsForTest()
            val bitmap = Bitmap.createBitmap(grid.width, grid.height, Bitmap.Config.ARGB_8888)
            grid.draw(Canvas(bitmap))

            var seams = 0
            rows.zipWithNext().forEach { (upper, lower) ->
                val from = upper.left + upper.width() / 4
                val to = upper.right - upper.width() / 4
                for (y in upper.bottom until lower.top) {
                    for (x in from until to) {
                        assertEquals(
                            "$label seam pixel $x,$y between $upper and $lower must paint the separator",
                            separatorColor,
                            bitmap.getPixel(x, y),
                        )
                    }
                    seams++
                }
                var stained = 0
                for (x in from until to) if (bitmap.getPixel(x, upper.centerY()) == separatorColor) stained++
                assertTrue(
                    "$label the row body at ${upper.centerY()} must not be separator-coloured: $stained of ${to - from}",
                    stained * 2 < to - from,
                )
            }
            assertTrue("$label must paint a separator under every candidate row, got $seams", seams >= 4)
        }
    }

    private fun forEachPortraitDock(body: (CandidateGridView, String) -> Unit) {
        for (qualifiers in portrait) {
            for (nine in listOf(false, true)) {
                RuntimeEnvironment.setQualifiers(qualifiers)
                try {
                    val grid = openExpandedPanel(nine)
                    body(grid, "$qualifiers nine=$nine")
                } finally {
                    RuntimeEnvironment.setQualifiers("w411dp-h891dp-xxhdpi")
                }
            }
        }
    }

    private fun openExpandedPanel(nine: Boolean): CandidateGridView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val iv = InputView(activity)
        root.addView(iv)
        activity.setContentView(root)
        val readout = listOf("ni", "nu", "ne").map { Key(it, output = it, action = KeyAction.PICK_READING) }
        val layout =
            if (nine) Layouts.nine(Lang.CN, readout, composing = true)
            else Layouts.forId(LayoutId.ALPHA, Lang.CN)
        iv.showKeyboard(layout, false, false, Lang.CN)
        iv.showCandidates(List(60) { "候选$it" }, "ni'hao", listOf("ni", "hao"), 0)
        layoutRoot(root)
        iv.showExpandedCandidates()
        layoutRoot(root)
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        layoutRoot(root)
        assertTrue("the expanded panel must be open", iv.isPanelShowing(iv.expandedGridForTest()))
        return iv.expandedGridForTest()
    }

    private fun layoutRoot(root: FrameLayout) {
        val metrics = root.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
    }

    private fun tap(target: View, x: Float, y: Float): Boolean {
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(0, 16, MotionEvent.ACTION_UP, x, y, 0)
        return try {
            val accepted = target.dispatchTouchEvent(down)
            val released = target.dispatchTouchEvent(up)
            shadowOf(Looper.getMainLooper()).idle()
            accepted && released
        } finally {
            down.recycle()
            up.recycle()
        }
    }
}
