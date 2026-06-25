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

import android.graphics.RectF
import android.view.View
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class KeyboardGeometryTest {

    private val context = RuntimeEnvironment.getApplication()
    private val density = context.resources.displayMetrics.density

    private fun view(id: LayoutId, widthDp: Int, lang: Lang = Lang.EN, heightPx: Int? = null): KeyboardView = KeyboardView(context).apply {
        setLayout(Layouts.forId(id, lang), false, false, lang)
        measure(
            View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
            if (heightPx == null) {
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            } else {
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
            },
        )
        layout(0, 0, measuredWidth, measuredHeight)
    }

    private fun assertSize(expected: RectF, actual: RectF) {
        assertEquals(expected.width(), actual.width(), 0.02f)
        assertEquals(expected.height(), actual.height(), 0.02f)
    }

    @Test
    fun alphabetOrdinaryFacesAndEffectiveHitsStayEqualWithCenteredHomeRow() {
        for (widthDp in listOf(320, 480)) {
            val view = view(LayoutId.ALPHA, widthDp)
            val faces = view.keyBoundsForTest().associate { it.first.label to it.second }
            val hits = view.keyHitBoundsForTest().associate { it.first.label to it.second }
            val baselineFace = faces.getValue("q")
            val baselineHit = hits.getValue("a")
            val halfGap = (faces.getValue("w").left - baselineFace.right) / 2f
            for (label in "abcdefghijklmnopqrstuvwxyz".map(Char::toString)) {
                assertSize(baselineFace, faces.getValue(label))
                val outerKey = label == "q" || label == "p"
                assertEquals(baselineHit.width() - if (outerKey) halfGap else 0f, hits.getValue(label).width(), 0.02f)
            }
            assertEquals("q's touch starts at its face", faces.getValue("q").left, hits.getValue("q").left, 0.02f)
            assertEquals("p's touch ends at its face", faces.getValue("p").right, hits.getValue("p").right, 0.02f)
            for (label in "asdfghjklzxcvbnm".map(Char::toString)) {
                assertSize(baselineHit, hits.getValue(label))
            }
            val homeFaces = "asdfghjkl".map { faces.getValue(it.toString()) }
            val homeHits = "asdfghjkl".map { hits.getValue(it.toString()) }
            val ordinaryGap = faces.getValue("w").left - baselineFace.right
            assertTrue(homeFaces.zipWithNext().all { (left, right) -> abs(right.left - left.right - ordinaryGap) <= 0.02f })
            assertEquals(homeFaces.first().left, view.width - homeFaces.last().right, 0.02f)
            assertEquals(homeHits.first().left, view.width - homeHits.last().right, 0.02f)
            assertTrue(homeFaces.first().left < baselineFace.width())
            assertEquals(view.width / 2f, (homeFaces.first().left + homeFaces.last().right) / 2f, 0.02f)
            assertEquals(view.width / 2f, (homeHits.first().left + homeHits.last().right) / 2f, 0.02f)
            val y = homeHits.first().centerY()
            assertNull(view.keyAtForTest(homeHits.first().left - 0.01f, y))
            assertEquals("a", view.keyAtForTest(homeHits.first().left, y)?.label)
            assertEquals("l", view.keyAtForTest(homeHits.last().right - 0.01f, y)?.label)
            assertNull(view.keyAtForTest(homeHits.last().right, y))
        }
    }

    @Test
    fun numberAndSymbolPagesMatchAlphabetFacesHitsAndGaps() {
        for (widthDp in listOf(250, 360, 480)) {
            val alphabet = view(LayoutId.ALPHA, widthDp)
            val baselineFace = requireNotNull(alphabet.boundsOfLabelForTest("q"))
            val baselineHit = alphabet.keyHitBoundsForTest().first { it.first.label == "a" }.second
            val alphaFaces = alphabet.keyBoundsForTest().associate { it.first.label to it.second }
            val ordinaryGap = alphaFaces.getValue("w").left - alphaFaces.getValue("q").right
            for (id in listOf(LayoutId.NUMBER, LayoutId.SYMBOL)) {
                val source = Layouts.forId(id, Lang.EN)
                val page = view(id, widthDp)
                val faces = page.keyBoundsForTest()
                val hits = page.keyHitBoundsForTest()
                val pageFaceHeight = faces[0].second.height()
                val pageHitHeight = hits[0].second.height()
                assertEquals("page ordinary face keeps the shared column width", baselineFace.width(), faces[0].second.width(), 0.02f)
                val sourceKeys = source.rows.flatMap { it.keys }
                assertEquals(sourceKeys, faces.map { it.first })
                assertEquals(sourceKeys, hits.map { it.first })
                var offset = 0
                val topRowFaces = faces.subList(0, source.rows.first().keys.size)
                for ((rowIndex, row) in source.rows.withIndex()) {
                    val rowFaces = faces.subList(offset, offset + row.keys.size)
                    val rowHits = hits.subList(offset, offset + row.keys.size)
                    val rowGaps = rowFaces.zipWithNext().map { (left, right) -> right.second.left - left.second.right }
                    if (rowIndex == source.rows.lastIndex - 1) {
                        for (gapValue in rowGaps) assertEquals(ordinaryGap * 9f / 8f, gapValue, 0.02f)
                        assertEquals(topRowFaces.first().second.left, rowFaces.first().second.left, 0.02f)
                        assertEquals(topRowFaces.last().second.right, rowFaces.last().second.right, 0.02f)
                        assertEquals(rowFaces.first().second.left, page.width - rowFaces.last().second.right, 0.02f)
                        assertEquals(1.5f * baselineFace.width(), rowFaces.first().second.width(), 0.02f)
                        assertEquals(1.5f * baselineFace.width(), rowFaces.last().second.width(), 0.02f)
                        for ((leftEntry, rightEntry) in rowHits.zipWithNext()) {
                            val seamY = leftEntry.second.centerY()
                            assertEquals(leftEntry.first, page.keyAtForTest(leftEntry.second.right - 0.02f, seamY))
                            assertEquals(rightEntry.first, page.keyAtForTest(rightEntry.second.left + 0.02f, seamY))
                            val mid = (leftEntry.second.right + rightEntry.second.left) / 2f
                            val midKey = page.keyAtForTest(mid, seamY)
                            assertTrue(midKey == leftEntry.first || midKey == rightEntry.first)
                        }
                    } else {
                        for (gapValue in rowGaps) assertEquals(ordinaryGap, gapValue, 0.02f)
                    }
                    if (rowIndex < source.rows.lastIndex) {
                        for (index in row.keys.indices.filter { row.keys[it].weight == 1f }) {
                            assertEquals(baselineFace.width(), rowFaces[index].second.width(), 0.02f)
                            assertEquals(pageFaceHeight, rowFaces[index].second.height(), 0.02f)
                            if (rowIndex == source.rows.lastIndex - 1) {
                                assertEquals(
                                    baselineHit.width() + ordinaryGap / 8f,
                                    rowHits[index].second.width(),
                                    0.02f,
                                )
                                assertEquals(pageHitHeight, rowHits[index].second.height(), 0.02f)
                            } else {
                                val outerKey = index == 0 || index == row.keys.lastIndex
                                assertEquals(
                                    baselineHit.width() - if (outerKey) ordinaryGap / 2f else 0f,
                                    rowHits[index].second.width(),
                                    0.02f,
                                )
                                assertEquals(pageHitHeight, rowHits[index].second.height(), 0.02f)
                            }
                        }
                        rowHits.zipWithNext().forEach { (leftEntry, rightEntry) ->
                            assertEquals(leftEntry.second.right, rightEntry.second.left, 0.02f)
                        }
                    } else {
                        for (index in row.keys.indices) {
                            val key = row.keys[index]
                            if (key.action == KeyAction.SPACE) {
                                assertTrue(rowFaces[index].second.width() > baselineFace.width())
                                assertTrue(rowHits[index].second.width() > baselineHit.width())
                            } else {
                                assertEquals(baselineFace.width() * key.weight, rowFaces[index].second.width(), 0.02f)
                                assertEquals(pageFaceHeight, rowFaces[index].second.height(), 0.02f)
                                val hitColumn = baselineHit.width() - ordinaryGap
                                val sharedGap = if (index == 0 || index == row.keys.lastIndex) ordinaryGap / 2f else ordinaryGap
                                assertEquals(hitColumn * key.weight + sharedGap, rowHits[index].second.width(), 0.02f)
                                assertEquals(pageHitHeight, rowHits[index].second.height(), 0.02f)
                            }
                            val hit = rowHits[index].second
                            assertEquals(key, page.keyAtForTest(hit.left + 0.01f, hit.centerY()))
                        }
                        assertTrue(rowHits.zipWithNext().all { (left, right) ->
                            abs(right.second.left - left.second.right) <= 0.02f
                        })

                        val controlWidth = rowFaces.first().second.width()
                        assertEquals(1.5f * baselineFace.width(), controlWidth, 0.02f)
                        assertEquals(controlWidth, rowFaces.last().second.width(), 0.02f)
                        assertTrue(rowFaces.first { it.first.action == KeyAction.SPACE }.second.width() < page.width * 0.55f)
                    }
                    offset += row.keys.size
                }
            }
        }
        val nineHeight = view(LayoutId.NINE, 360).measuredHeight
        assertEquals(nineHeight, view(LayoutId.NUMPAD, 360).measuredHeight)
        assertEquals(nineHeight, view(LayoutId.NUMBER, 360).measuredHeight)
        assertEquals(nineHeight, view(LayoutId.SYMBOL, 360).measuredHeight)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h200dp-land-mdpi")
    fun constrainedPageHeightsMatchAlphabetAt118Pixels() {
        val height = 118
        val alphabet = view(LayoutId.ALPHA, 320, heightPx = height)
        val baselineFace = requireNotNull(alphabet.boundsOfLabelForTest("q"))
        val baselineHit = alphabet.keyHitBoundsForTest().first { it.first.label == "q" }.second
        assertEquals(height, alphabet.measuredHeight)
        for (id in listOf(LayoutId.NUMBER, LayoutId.SYMBOL)) {
            val source = Layouts.forId(id, Lang.EN)
            val page = view(id, 320, heightPx = height)
            val faces = page.keyBoundsForTest()
            val hits = page.keyHitBoundsForTest()
            assertEquals(height, page.measuredHeight)
            var offset = 0
            for ((rowIndex, row) in source.rows.withIndex()) {
                for (index in row.keys.indices) {
                    val key = row.keys[index]
                    val ordinary = if (rowIndex < source.rows.lastIndex) key.weight == 1f else key.action != KeyAction.SPACE
                    if (ordinary) {
                        assertEquals(baselineFace.height(), faces[offset + index].second.height(), 0.02f)
                        assertEquals(baselineHit.height(), hits[offset + index].second.height(), 1.02f)
                    }
                }
                offset += row.keys.size
            }
        }
    }

    @Test
    fun outerKeyFacesAndTheirTouchAreasStopAtTheSharedEdgeInset() {
        val inset = ImeShapes.edgeInsetDp * density
        for (widthDp in listOf(250, 292, 320, 360, 411, 480)) {
            for (id in listOf(LayoutId.ALPHA, LayoutId.NINE, LayoutId.NUMPAD, LayoutId.NUMBER, LayoutId.SYMBOL)) {
                val view = view(id, widthDp, Lang.CN)
                val message = "$id at ${widthDp}dp"
                val column = view.scrollRegionForTest().takeUnless { it.isEmpty }
                val faces = view.keyBoundsForTest().map { it.second } + listOfNotNull(column)
                assertEquals("$message leftmost face", inset, faces.minOf { it.left }, 0.02f)
                assertEquals("$message rightmost face", view.width - inset, faces.maxOf { it.right }, 0.02f)
                val hits = view.keyHitBoundsForTest().map { it.second }
                val leftmost = hits.minBy { it.left }
                val rightmost = hits.maxBy { it.right }
                val touchLefts = hits.map { it.left } + listOfNotNull(column?.let { view.scrollTouchForTest().left })
                assertEquals("$message leftmost touch starts at the edge inset", inset, touchLefts.min(), 0.02f)
                assertEquals("$message rightmost touch stops at the edge inset", view.width - inset, rightmost.right, 0.02f)
                assertNull("$message a touch in the left edge inset finds no key", view.keyAtForTest(inset - 0.01f, leftmost.centerY()))
                assertNull("$message a touch in the right edge inset finds no key", view.keyAtForTest(view.width - inset, rightmost.centerY()))
                if (column != null) {
                    val touch = view.scrollTouchForTest()
                    assertEquals("$message column touch starts at the edge inset", inset, touch.left, 0.02f)
                    assertTrue("$message column touch covers its face", touch.left <= column.left && touch.right >= column.right)
                    assertTrue("$message a key starts where the column touch ends", view.keyAtForTest(touch.right + 0.01f, touch.centerY()) != null)
                }
            }
        }
    }

    @Test
    fun outerKeyFacesStayAtTheSharedEdgeInsetWhenTheKeyGapNarrows() {
        val inset = ImeShapes.edgeInsetDp * density
        val floor = 20f * density
        for (widthDp in listOf(250, 272, 280, 292, 303)) {
            for (id in listOf(LayoutId.ALPHA, LayoutId.NINE, LayoutId.NUMPAD, LayoutId.NUMBER, LayoutId.SYMBOL)) {
                val view = view(id, widthDp, Lang.CN)
                val message = "$id at ${widthDp}dp"
                val column = view.scrollRegionForTest().takeUnless { it.isEmpty }
                val faces = view.keyBoundsForTest().map { it.second } + listOfNotNull(column)
                assertEquals("$message leftmost face", inset, faces.minOf { it.left }, 0.02f)
                assertEquals("$message rightmost face", view.width - inset, faces.maxOf { it.right }, 0.02f)
                assertTrue("$message narrowest face keeps 20dp", view.minimumKeyWidthForTest() >= floor)
            }
        }
    }

    @Test
    fun alphaEdgeRowsExtendTheirHitBoxesToTheViewEdges() {
        val view = view(LayoutId.ALPHA, 360)
        val hits = view.keyHitBoundsForTest().associate { it.first.label to it.second }
        assertEquals("the top row reaches the top edge", 0f, hits.getValue("q").top, 0.01f)
        val bottomMost = view.keyHitBoundsForTest().maxOf { it.second.bottom }
        assertEquals("the bottom row reaches the bottom edge", view.height.toFloat(), bottomMost, 0.01f)
        assertEquals("a touch on the very top lands on a key", "q", view.keyAtForTest(hits.getValue("q").centerX(), 0.4f)?.label)
    }
}
