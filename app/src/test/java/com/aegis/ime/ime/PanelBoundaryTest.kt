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
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanelBoundaryTest {

    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun symbols_empty_recents_keep_the_action_boundary() = verifyCatalog(emoji = false, count = 0)
    @Test fun symbols_one_recent_keeps_the_action_boundary() = verifyCatalog(emoji = false, count = 1)
    @Test fun symbols_eleven_recents_keep_the_action_boundary() = verifyCatalog(emoji = false, count = 11)
    @Test fun symbols_one_full_row_keeps_the_action_boundary() = verifyCatalog(emoji = false, count = 4)
    @Test fun symbols_full_grid_keeps_a_single_action_boundary() = verifyCatalog(emoji = false, count = 16)
    @Test fun symbols_scrolling_keeps_a_single_action_boundary() = verifyCatalog(emoji = false, count = 37, scroll = true)

    @Test fun emoji_empty_recents_keep_the_action_boundary() = verifyCatalog(emoji = true, count = 0)
    @Test fun emoji_one_recent_keeps_the_action_boundary() = verifyCatalog(emoji = true, count = 1)
    @Test fun emoji_eleven_recents_keep_the_action_boundary() = verifyCatalog(emoji = true, count = 11)
    @Test fun emoji_one_full_row_keeps_the_action_boundary() = verifyCatalog(emoji = true, count = 4)
    @Test fun emoji_full_grid_keeps_a_single_action_boundary() = verifyCatalog(emoji = true, count = 16)
    @Test fun emoji_scrolling_keeps_a_single_action_boundary() = verifyCatalog(emoji = true, count = 37, scroll = true)

    @Test fun sparse_candidates_and_readings_keep_both_column_boundaries() {
        val failures = mutableListOf<String>()
        forEachAppearance { label, density, line, palette ->
            for (count in listOf(0, 1, 11)) {
                for (readings in listOf(emptyList(), listOf("a"), listOf("ni", "hao"))) {
                    val panel = CandidateGridView(ctx).apply {
                        applyPalette(palette)
                        setReadings(readings, 0)
                        setCandidates(List(count) { "候${it + 1}" })
                    }
                    layout(panel, density)
                    val actionColumn = panel.returnButtonForTest().parent as View
                    val readingColumn = panel.getChildAt(0)
                    val actionBounds = bounds(panel, actionColumn)
                    val readingBounds = bounds(panel, readingColumn)
                    val bmp = draw(panel)
                    try {
                        val case = "$label candidates=$count readings=${readings.size}"
                        failures += boundaryFailures(bmp, "$case actions", actionBounds.left,
                            actionBounds, line, palette.gridLine)
                        failures += boundaryFailures(bmp, "$case readings", readingBounds.right,
                            readingBounds, line, palette.gridLine)
                    } finally {
                        bmp.recycle()
                    }
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun verifyCatalog(emoji: Boolean, count: Int, scroll: Boolean = false) {
        val failures = mutableListOf<String>()
        forEachAppearance { label, density, line, palette ->
            val panel: ViewGroup
            val actionColumn: View
            val viewport: ScrollView
            val columns: Int
            if (emoji) {
                val view = EmojiView(ctx).apply {
                    applyPalette(palette)
                    recentProvider = { List(count) { "🙂" } }
                    refresh()
                }
                layout(view, density)
                if (count > 0) assertEquals(count, view.gridCellTextsForTest().size)
                panel = view
                actionColumn = view.actionColumnForTest()
                viewport = view.gridViewportForTest() as ScrollView
                columns = view.gridColumnCountForTest()
            } else {
                val view = SymbolsView(ctx).apply {
                    applyPalette(palette)
                    recentProvider = { List(count) { "!" } }
                    refresh()
                }
                layout(view, density)
                assertEquals(count, view.gridCellTextsForTest().size)
                panel = view
                actionColumn = view.actionColumnForTest()
                viewport = view.gridViewportForTest() as ScrollView
                columns = view.gridColumnCountForTest()
            }
            if (count == 4 || count == 16) assertEquals("$label complete rows", 0, count % columns)
            val maxScroll = (viewport.getChildAt(0).height - viewport.height).coerceAtLeast(0)
            if (scroll) assertTrue("$label catalog has content to scroll", maxScroll > viewport.height)
            val positions = if (scroll) listOf(0, maxScroll / 2, maxScroll) else listOf(0)
            for (position in positions) {
                viewport.scrollTo(0, position)
                assertEquals("$label viewport reaches requested position", position, viewport.scrollY)
                val actionBounds = bounds(panel, actionColumn)
                val bmp = draw(panel)
                try {
                    failures += boundaryFailures(bmp,
                        "$label ${if (emoji) "emoji" else "symbols"} count=$count scroll=$position",
                        actionBounds.left, actionBounds, line, palette.gridLine)
                } finally {
                    bmp.recycle()
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun boundaryFailures(
        bitmap: Bitmap,
        label: String,
        seam: Int,
        column: Rect,
        line: Int,
        color: Int,
    ): List<String> {
        val missing = mutableListOf<Int>()
        val wrongWidths = mutableListOf<Pair<Int, Int>>()
        val displaced = mutableListOf<Int>()
        var expectedRun: List<Int>? = null
        var measuredRows = 0
        for (y in column.top until column.bottom) {
            val near = (seam - line until seam + line).filter { bitmap.getPixel(it, y) == color }
            if (near.isEmpty()) missing += y
            val crossing = bitmap.getPixel(seam - 3 * line, y) == color ||
                bitmap.getPixel(seam + 3 * line, y) == color
            if (crossing) continue
            measuredRows++
            val run = (seam - 2 * line until seam + 2 * line).filter { bitmap.getPixel(it, y) == color }
            if (run.size != line || run.zipWithNext().any { (a, b) -> b != a + 1 }) {
                wrongWidths += y to run.size
            } else if (expectedRun == null) {
                expectedRun = run
            } else if (run != expectedRun) {
                displaced += y
            }
        }
        return buildList {
            if (missing.isNotEmpty()) add("$label bounds=$column has ${missing.size} missing boundary rows, first y=${missing.first()}")
            if (wrongWidths.isNotEmpty()) add("$label requires one ${line}px line: ${wrongWidths.size} bad rows, first(y,width)=${wrongWidths.first()}")
            if (displaced.isNotEmpty()) add("$label boundary shifts horizontally at y=${displaced.first()}")
            if (measuredRows < column.height() / 2) add("$label has too few unobstructed rows to verify line width: $measuredRows")
        }
    }

    private fun forEachAppearance(body: (String, Float, Int, ImePalette) -> Unit) {
        for ((qualifiers, expectedLine) in listOf("w411dp-h891dp-mdpi" to 1, "w411dp-h891dp-xxhdpi" to 2)) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            try {
                val density = ctx.resources.displayMetrics.density
                val line = ImeShapes.gridLinePx(density).toInt()
                assertEquals("$qualifiers standard grid line width", expectedLine, line)
                for ((theme, palette) in listOf("light" to ImePalette.STATIC_LIGHT, "dark" to ImePalette.STATIC_DARK)) {
                    body("$qualifiers $theme", density, line, palette)
                }
            } finally {
                RuntimeEnvironment.setQualifiers("mdpi")
            }
        }
    }

    private fun layout(view: View, density: Float) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec((411 * density).roundToInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((290 * density).roundToInt(), View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun bounds(root: ViewGroup, view: View): Rect = Rect(view.left, view.top, view.right, view.bottom).also {
        root.offsetDescendantRectToMyCoords(view.parent as View, it)
    }

    private fun draw(view: View): Bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
        view.draw(Canvas(it))
    }
}
