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

    @Test fun the_four_actions_sit_on_the_four_candidate_rows() {
        forEachPortraitDock { grid, label ->
            val rowHeight = grid.candidateRowHeightForTest()
            val separator = grid.tableDividerHeightForTest()
            val rows = grid.visibleCandidateRowsForTest()
            val actions = (0..3).map { grid.actionBoundsForTest(it) }

            assertTrue("$label must keep a separator between rows, got $separator", separator >= 1)
            assertEquals("$label the row pitch carries the separator", rowHeight + separator, grid.candidateRowStrideForTest())
            val content = grid.height - (CandidateGridView.ROWS - 1) * separator
            assertEquals(
                "$label four rows divide the panel height",
                (content + CandidateGridView.ROWS - 1) / CandidateGridView.ROWS,
                rowHeight,
            )
            assertEquals("$label exactly four candidate rows are laid out: $rows", CandidateGridView.ROWS, rows.size)
            rows.forEach { assertEquals("$label every visible row is one row tall: $rows", rowHeight, it.height()) }
            rows.zipWithNext().forEach { (upper, lower) ->
                assertEquals("$label rows sit one separator apart: $rows", separator, lower.top - upper.bottom)
            }

            actions.forEachIndexed { index, action ->
                assertEquals("$label action $index starts on candidate row $index: $actions vs $rows", rows[index].top, action.top)
            }
            actions.zipWithNext().forEach { (upper, lower) ->
                assertEquals("$label the actions tile the column with no gap: $actions", upper.bottom, lower.top)
            }
            rows.zipWithNext().forEachIndexed { index, (_, lower) ->
                assertEquals("$label the rule under action $index lands on the next row's top: $actions vs $rows", lower.top, actions[index].bottom)
            }
            assertEquals("$label the last action ends at the bottom of the column: $actions", grid.height, actions[3].bottom)
        }
    }

    @Test fun taps_reach_each_action_across_its_whole_row() {
        forEachPortraitDock { grid, label ->
            val fired = IntArray(4)
            grid.onClose = { fired[0]++ }
            grid.onBackspace = { fired[1]++ }
            grid.onClear = { fired[2]++ }
            var singles = grid.singlesOnlyForTest()
            val actions = (0..3).map { grid.actionBoundsForTest(it) }

            actions.forEachIndexed { slot, action ->
                for (y in listOf(action.top + 1f, action.exactCenterY(), action.bottom - 1f)) {
                    val before = fired.copyOf()
                    assertTrue("$label a tap at ${action.exactCenterX()},$y must be handled", tap(grid, action.exactCenterX(), y))
                    if (grid.singlesOnlyForTest() != singles) {
                        singles = grid.singlesOnlyForTest()
                        fired[3]++
                    }
                    fired.forEachIndexed { other, count ->
                        assertEquals(
                            "$label a tap at ${action.exactCenterX()},$y on action $slot fired action $other",
                            before[other] + if (other == slot) 1 else 0,
                            count,
                        )
                    }
                }
            }
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
            assertTrue("$label must paint a separator under every candidate row, got $seams", seams >= CandidateGridView.ROWS - 1)
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
            if (nine) Layouts.nine(readout, composing = true)
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
