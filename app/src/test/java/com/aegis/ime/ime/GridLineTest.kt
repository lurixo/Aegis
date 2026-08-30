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
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.Layouts
import com.aegis.ime.layout.SymbolCatalog
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GridLineTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val black = 0xFF000000.toInt()
    private val screens = listOf("w360dp-h640dp-mdpi" to 1, "w411dp-h891dp-xxhdpi" to 2)

    @Test fun every_palette_rules_its_grids_in_black() {
        assertEquals(black, ImePalette.STATIC_LIGHT.gridLine)
        assertEquals(black, ImePalette.STATIC_DARK.gridLine)
        assertEquals(black, ImePalette.from(ctx, dark = false).gridLine)
        assertEquals(black, ImePalette.from(ctx, dark = true).gridLine)
    }

    @Test fun the_grid_line_is_half_a_dp_rounded_to_whole_pixels_and_never_thinner_than_one() {
        assertEquals(1f, ImeShapes.gridLinePx(1f), 0f)
        assertEquals(1f, ImeShapes.gridLinePx(2f), 0f)
        assertEquals(1f, ImeShapes.gridLinePx(2.625f), 0f)
        assertEquals(2f, ImeShapes.gridLinePx(3.5f), 0f)
    }

    @Test fun the_expanded_panel_rules_are_black_hairlines() {
        forEachScreen { label, density, line ->
            val v = CandidateGridView(ctx).apply {
                applyPalette(ImePalette.STATIC_LIGHT)
                setReadings(listOf("ni", "hao", "ma"), 0)
                setCandidates((1..40).map { "候$it" })
            }
            val bmp = render(v, (360 * density).toInt(), (250 * density).toInt())
            val rows = v.visibleCandidateRowsForTest()
            assertEquals("$label lays out the four candidate rows", CandidateGridView.ROWS, rows.size)
            for (x in v.columnRulesForTest()) assertVerticalRule(bmp, "$label column rule at $x", x, line + 1, line)
            val tile = requireNotNull(v.readingTileForTest(0))
            val seam = Rect(0, 0, tile.width, tile.height).also { v.offsetDescendantRectToMyCoords(tile, it) }
            assertHorizontalRule(bmp, "$label reading seam", seam.bottom, line + 1, line)
            rows.zipWithNext().forEach { (upper, lower) ->
                assertEquals("$label rows sit one grid line apart", line, lower.top - upper.bottom)
                assertHorizontalRule(bmp, "$label table seam under $upper", lower.top, upper.left + line + 1, line)
            }
            for (y in v.actionRulesForTest()) assertHorizontalRule(bmp, "$label action rule at $y", y, v.width - line - 2, line)
            val x = rows[0].left + line + 1
            assertHorizontalRule(bmp, "$label top outline", line, x, line)
            assertHorizontalRule(bmp, "$label bottom outline", v.height, x, line)
        }
    }

    @Test fun the_symbol_grid_rules_are_black_hairlines() {
        forEachScreen { label, density, line ->
            val en = SymbolCatalog.categories.indexOfFirst { it.id == "en" } + 1
            val sv = SymbolsView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT); openCategoryForTest(en) }
            val bmp = render(sv, (360 * density).toInt(), (250 * density).toInt())
            val cell = requireNotNull(sv.gridCellForTest(SymbolCatalog.categories[en - 1].symbols.first()))
            val r = Rect(0, 0, cell.width, cell.height).also { sv.offsetDescendantRectToMyCoords(cell, it) }
            assertVerticalRule(bmp, "$label symbol cell right rule", r.right, r.top + line + 1, line)
            assertHorizontalRule(bmp, "$label symbol cell bottom rule", r.bottom, r.left + line + 1, line)
        }
    }

    @Test fun the_nine_key_scroll_column_separators_are_black_hairlines() {
        forEachScreen { label, density, line ->
            val kv = KeyboardView(ctx).apply {
                setLayout(Layouts.nine(Layouts.ninePunctuation(), composing = false), false, false, Lang.CN)
                applyPalette(ImePalette.STATIC_LIGHT)
            }
            val bmp = render(kv, (360 * density).toInt(), (230 * density).toInt())
            val region = kv.scrollRegionForTest()
            val x = (region.left + 6 * density).roundToInt() + 1
            val firstSeam = (region.top + kv.scrollCellHeightForTest()).roundToInt()
            assertHorizontalRule(bmp, "$label first scroll seam", firstSeam, x, line)
            val runs = blackRuns((region.top.toInt() until region.bottom.toInt()).map { bmp.getPixel(x, it) })
            assertTrue("$label draws several separators, got $runs", runs.size >= 2)
            assertTrue("$label every separator is $line px thick: $runs", runs.all { it == line })
        }
    }

    @Test fun the_candidate_bar_dividers_are_black_hairlines() {
        forEachScreen { label, density, line ->
            val cv = CandidateView(ctx).apply {
                applyPalette(ImePalette.STATIC_LIGHT)
                setContent(listOf("你", "好", "吗"), "ni")
            }
            val bmp = render(cv, (360 * density).toInt(), (44 * density).toInt())
            val runs = blackRuns((0 until cv.width).map { bmp.getPixel(it, cv.height / 2) })
            assertEquals("$label two candidate dividers and the expand divider: $runs", 3, runs.size)
            assertTrue("$label every divider is $line px wide: $runs", runs.all { it == line })
        }
    }

    private fun forEachScreen(body: (String, Float, Int) -> Unit) {
        for ((qualifiers, expected) in screens) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            try {
                val density = ctx.resources.displayMetrics.density
                val line = ImeShapes.gridLinePx(density).toInt()
                assertEquals("$qualifiers rules $expected px thick", expected, line)
                body(qualifiers, density, line)
            } finally {
                RuntimeEnvironment.setQualifiers("mdpi")
            }
        }
    }

    private fun render(v: View, width: Int, height: Int): Bitmap {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    }

    private fun blackRuns(pixels: List<Int>): List<Int> {
        val runs = ArrayList<Int>()
        var run = 0
        for (p in pixels) {
            if (p == black) {
                run++
            } else if (run > 0) {
                runs.add(run)
                run = 0
            }
        }
        if (run > 0) runs.add(run)
        return runs
    }

    private fun assertVerticalRule(bmp: Bitmap, label: String, right: Int, y: Int, line: Int) =
        assertRule(bmp, label, (right - line - 1..right).map { it to y })

    private fun assertHorizontalRule(bmp: Bitmap, label: String, bottom: Int, x: Int, line: Int) =
        assertRule(bmp, label, (bottom - line - 1..bottom).map { x to it })

    private fun assertRule(bmp: Bitmap, label: String, probes: List<Pair<Int, Int>>) {
        for ((x, y) in probes.subList(1, probes.size - 1)) {
            assertEquals("$label paints black at $x,$y", black, bmp.getPixel(x, y))
        }
        for ((x, y) in listOf(probes.first(), probes.last())) {
            if (x !in 0 until bmp.width || y !in 0 until bmp.height) continue
            assertNotEquals("$label stops before $x,$y", black, bmp.getPixel(x, y))
        }
    }
}
