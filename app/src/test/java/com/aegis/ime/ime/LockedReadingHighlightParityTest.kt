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
import android.view.View
import android.view.ViewGroup
import com.aegis.ime.decoder.Cand
import com.aegis.ime.decoder.Syllable
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockedReadingHighlightParityTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density
    private val palette = ImePalette.STATIC_LIGHT

    private class RecordingHost : ImeHost {
        val text = StringBuilder()
        override fun commitText(text: CharSequence) { this.text.append(text) }
        override fun deleteBackward() {
            if (text.isNotEmpty()) text.delete(text.length - 1, text.length)
        }
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = text.substring(maxOf(0, text.length - n))
        override fun replaceBeforeCursor(length: Int, text: CharSequence) {
            val start = maxOf(0, this.text.length - length)
            this.text.delete(start, this.text.length)
            this.text.append(text)
        }
    }

    private val syllabic = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> =
            candidatesCovered(composing, t9).map { it.word }
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
            if (composing.isEmpty()) emptyList() else listOf(Cand("你好", composing.length), Cand("你", 2))
        override fun candidatesForLockedReadingCovered(
            letters: String,
            cuts: Set<Int>,
            context: CharSequence,
        ): List<Cand> = candidatesCovered(letters, false, cuts, context)
        override fun syllablesForReading(letters: String): List<Syllable> = when (letters) {
            "ni" -> listOf(Syllable("ni", 0, 2))
            "nihao" -> listOf(Syllable("ni", 0, 2), Syllable("hao", 2, 5))
            "nihe" -> listOf(Syllable("ni", 0, 2), Syllable("he", 2, 4))
            "nihaoni" -> listOf(Syllable("ni", 0, 2), Syllable("hao", 2, 5), Syllable("ni", 5, 7))
            else -> emptyList()
        }
        override fun homophonesForReadingAt(letters: String, index: Int): List<String> =
            listOf("你", "泥", "拟")
    }

    private val wordsOnly = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> =
            candidatesCovered(composing, t9).map { it.word }
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
            if (composing.isEmpty()) emptyList() else listOf(Cand("你", composing.length))
        override fun candidatesForLockedReadingCovered(
            letters: String,
            cuts: Set<Int>,
            context: CharSequence,
        ): List<Cand> = candidatesCovered(letters, false, cuts, context)
    }

    private fun out(s: String) = Key(s, output = s)
    private fun act(a: KeyAction) = Key("", action = a)

    private fun session(engine: CandidateEngine): Pair<KeyboardController, InputView> {
        val iv = InputView(ctx).apply { applyPalette(palette) }
        val c = KeyboardController(RecordingHost(), engine)
        iv.onPickReading = { i -> c.onPickReadingIndex(i) }
        iv.onPickCandidate = { i -> c.onPickCandidate(i) }
        iv.onExpandClosed = { c.clearDrill() }
        c.attachView(iv)
        return c to iv
    }

    private fun nineSession(engine: CandidateEngine, digits: String): Triple<KeyboardController, InputView, KeyboardView> {
        val (c, iv) = session(engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        digits.forEach { c.onKey(out(it.toString())) }
        val kb = keyboardOf(iv)
        laidOut(kb)
        return Triple(c, iv, kb)
    }

    private fun keyboardOf(root: View): KeyboardView {
        fun walk(v: View): List<View> = listOf(v) + when (v) {
            is ViewGroup -> (0 until v.childCount).flatMap { walk(v.getChildAt(it)) }
            else -> emptyList()
        }
        return walk(root).filterIsInstance<KeyboardView>().single()
    }

    private fun laidOut(kb: KeyboardView): KeyboardView {
        kb.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((250 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        kb.layout(0, 0, kb.measuredWidth, kb.measuredHeight)
        return kb
    }

    private fun frame(kb: KeyboardView): Bitmap {
        val bitmap = Bitmap.createBitmap(kb.width, kb.height, Bitmap.Config.ARGB_8888)
        kb.draw(Canvas(bitmap))
        return bitmap
    }

    private fun cellRect(kb: KeyboardView, index: Int): RectF {
        val region = kb.scrollRegionForTest()
        val cell = kb.scrollCellHeightForTest()
        val top = region.top - kb.scrollOffsetForTest() + index * cell
        return RectF(
            region.left,
            maxOf(top, region.top),
            region.right,
            minOf(top + cell, region.bottom),
        )
    }

    private fun pixels(bitmap: Bitmap, rect: RectF, color: Int): Int {
        var found = 0
        for (y in rect.top.toInt() until rect.bottom.toInt()) {
            for (x in rect.left.toInt() until rect.right.toInt()) {
                if (bitmap.getPixel(x, y) == color) found++
            }
        }
        return found
    }

    private fun markedCells(kb: KeyboardView): List<Int> =
        kb.scrollColumnKeysForTest().withIndex().filter { it.value.accent }.map { it.index }

    private fun cellIsOnScreen(kb: KeyboardView, index: Int): Boolean {
        val region = kb.scrollRegionForTest()
        val cell = kb.scrollCellHeightForTest()
        val top = region.top - kb.scrollOffsetForTest() + index * cell
        return top >= region.top - 0.5f && top + cell <= region.bottom + 0.5f
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun nine_key_left_column_paints_the_locked_reading_with_the_accent_color() {
        val (c, _, kb) = nineSession(syllabic, "64")
        assertTrue(
            "precondition: ni is offered before locking, was ${kb.scrollColumnKeysForTest().map { it.label }}",
            "ni" in kb.scrollColumnKeysForTest().map { it.label },
        )
        assertEquals("nothing is marked while nothing is locked", emptyList<Int>(), markedCells(kb))

        c.onPickReadingIndex(c.expandedReadings().indexOf("ni"))

        val persisted = markedCells(kb)
        assertEquals("the persisted locked reading is the only marked cell", 1, persisted.size)
        assertEquals("and it is the locked reading", "ni", kb.scrollColumnKeysForTest()[persisted.single()].label)

        "43".forEach { c.onKey(out(it.toString())) }

        val keys = kb.scrollColumnKeysForTest()
        assertEquals("the locked reading heads the column while typing continues", listOf(0), markedCells(kb))
        assertEquals("and that head cell is the locked reading, was ${keys.map { it.label }}", "ni", keys[0].label)
        assertTrue(
            "the unexpanded left column paints the locked cell with the theme accent color",
            pixels(frame(kb), cellRect(kb, 0), palette.accentBottom) > 0,
        )
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun nine_key_left_column_leaves_unlocked_readings_in_the_plain_key_label_color() {
        val readings = listOf("ni", "nu", "ne", "na")
        val column = readings.mapIndexed { i, r ->
            Key(r, output = r, action = KeyAction.PICK_READING, weight = 0.85f, accent = i == readings.lastIndex)
        }
        val kb = laidOut(
            KeyboardView(ctx).apply {
                applyPalette(palette)
                setLayout(Layouts.nine(Lang.CN, column, composing = true), false, false, Lang.CN)
            },
        )

        repeat(2) { pass ->
            val bitmap = frame(kb)
            assertTrue(
                "pass $pass: the marked reading keeps the accent color",
                pixels(bitmap, cellRect(kb, readings.lastIndex), palette.accentBottom) > 0,
            )
            for (i in 0 until readings.lastIndex) {
                assertTrue(
                    "pass $pass: plain reading ${readings[i]} still renders its label",
                    pixels(bitmap, cellRect(kb, i), palette.keyLabel) > 0,
                )
                assertEquals(
                    "pass $pass: plain reading ${readings[i]} must not borrow the accent color",
                    0,
                    pixels(bitmap, cellRect(kb, i), palette.accentBottom),
                )
            }
        }
    }

    @Test fun both_views_highlight_the_same_reading_before_and_after_expanding() {
        val (c, iv, kb) = nineSession(syllabic, "64")
        c.onPickReadingIndex(c.expandedReadings().indexOf("ni"))

        val unexpanded = markedCells(kb)
        assertEquals("the unexpanded column marks exactly one reading", 1, unexpanded.size)
        assertEquals("and it is the locked reading", "ni", kb.scrollColumnKeysForTest()[unexpanded.single()].label)

        iv.showExpandedCandidates()
        val readings = c.expandedReadings()
        val expanded = readings.indexOf("ni")
        assertTrue("precondition: the locked reading survives into the expanded grid, was $readings", expanded >= 0)
        assertEquals(
            "the expanded grid marks the very same reading with the very same color",
            palette.accentBottom,
            iv.expandedReadingTextColorForTest(expanded),
        )

        iv.showPanel(null)

        val collapsed = markedCells(kb)
        assertEquals("collapsing leaves exactly one marked reading", 1, collapsed.size)
        assertEquals(
            "and it is still the same reading",
            "ni",
            kb.scrollColumnKeysForTest()[collapsed.single()].label,
        )
    }

    @Test fun a_repeated_reading_is_highlighted_only_once_in_the_left_column() {
        val (c, _, kb) = nineSession(syllabic, "64")
        c.onPickReadingIndex(c.expandedReadings().indexOf("ni"))
        "64".forEach { c.onKey(out(it.toString())) }

        val labels = kb.scrollColumnKeysForTest().map { it.label }
        assertTrue("precondition: the locked reading spells out twice in the column, was $labels", labels.count { it == "ni" } >= 2)
        assertEquals("only the locked cell is marked, never every cell that reads the same", listOf(0), markedCells(kb))
    }

    @Test fun a_drill_pointing_at_no_syllable_highlights_nothing_in_either_view() {
        val (c, iv, kb) = nineSession(wordsOnly, "64")
        c.onPickReadingIndex(c.expandedReadings().indexOf("ni"))
        iv.showExpandedCandidates()
        assertEquals(
            "precondition: the plain lock marks the reading in the expanded grid",
            palette.accentBottom,
            iv.expandedReadingTextColorForTest(c.expandedReadings().indexOf("ni")),
        )
        assertEquals("precondition: and in the unexpanded column too", 1, markedCells(kb).size)

        c.onPickReadingIndex(c.expandedReadings().indexOf("ni"))

        assertTrue("precondition: a drill is open", c.drilledSyllableForTest() >= 0)
        assertNull("the single highlight source resolves to no reading", c.lockedHighlightReading())
        assertEquals(
            "the expanded grid marks nothing",
            palette.candidateText,
            iv.expandedReadingTextColorForTest(0),
        )
        assertEquals("and the unexpanded column marks nothing either", emptyList<Int>(), markedCells(kb))
    }

    @Test fun a_drill_on_an_earlier_syllable_marks_that_syllable_in_both_views() {
        val (c, iv, kb) = nineSession(syllabic, "6443")
        c.onPickReadingIndex(c.expandedReadings().indexOf("ni"))
        c.onPickReadingIndex(c.expandedReadings().indexOf("he"))
        c.onPickReadingIndex(0)

        assertEquals("precondition: the first syllable is the drilled one", 0, c.drilledSyllableForTest())
        assertEquals("the shared source follows the drilled syllable, not the last lock", "ni", c.lockedHighlightReading())

        iv.showExpandedCandidates()
        val readings = c.expandedReadings()
        assertEquals(
            "the expanded grid marks the drilled reading, was $readings",
            palette.accentBottom,
            iv.expandedReadingTextColorForTest(readings.indexOf("ni")),
        )

        val labels = kb.scrollColumnKeysForTest().map { it.label }
        assertTrue("precondition: the column offers the last lock's alternatives, was $labels", "he" in labels)
        assertEquals("none of which the column may mark while an earlier syllable is drilled", emptyList<Int>(), markedCells(kb))
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun the_locked_reading_is_scrolled_into_view_wherever_it_sits_in_the_column() {
        val cases = listOf("64" to "ni", "586" to "jun", "7426" to "shan")
        for ((digits, reading) in cases) {
            val (c, _, kb) = nineSession(syllabic, digits)
            c.onPickReadingIndex(c.expandedReadings().indexOf(reading))

            val marked = markedCells(kb)
            assertEquals("$reading is the only marked cell", 1, marked.size)
            assertEquals("$reading is the marked reading", reading, kb.scrollColumnKeysForTest()[marked.single()].label)
            assertTrue(
                "$reading sits at cell ${marked.single()} of ${kb.scrollColumnKeysForTest().size} and must be brought on screen",
                cellIsOnScreen(kb, marked.single()),
            )
            assertTrue(
                "$reading is painted with the accent color where the user can see it",
                pixels(frame(kb), cellRect(kb, marked.single()), palette.accentBottom) > 0,
            )
        }
    }

    @Test fun a_drill_marks_its_own_syllable_at_every_reachable_position() {
        val first = session(syllabic)
        first.first.onKey(act(KeyAction.SWITCH_ALPHA))
        "nihao".forEach { first.first.onKey(out(it.toString())) }
        first.first.onPickReadingIndex(first.first.expandedReadings().indexOf("ni"))
        first.first.onPickReadingIndex(first.first.expandedReadings().indexOf("ni"))
        assertEquals("the first syllable of two is drilled", 0, first.first.drilledSyllableForTest())
        assertEquals("and the source names that syllable", "ni", first.first.lockedHighlightReading())
        first.second.showExpandedCandidates()
        assertEquals(
            "which the expanded grid marks",
            palette.accentBottom,
            first.second.expandedReadingTextColorForTest(first.first.expandedReadings().indexOf("ni")),
        )

        val middle = session(syllabic)
        middle.first.onKey(act(KeyAction.SWITCH_ALPHA))
        "nihaoni".forEach { middle.first.onKey(out(it.toString())) }
        middle.first.onPickReadingIndex(middle.first.expandedReadings().indexOf("ni"))
        middle.first.onPickReadingIndex(middle.first.expandedReadings().indexOf("hao"))
        middle.first.onPickReadingIndex(middle.first.expandedReadings().indexOf("hao"))
        assertEquals("the middle syllable of three is drilled", 1, middle.first.drilledSyllableForTest())
        assertEquals("and the source names that syllable", "hao", middle.first.lockedHighlightReading())
        middle.second.showExpandedCandidates()
        assertEquals(
            "which the expanded grid marks",
            palette.accentBottom,
            middle.second.expandedReadingTextColorForTest(middle.first.expandedReadings().indexOf("hao")),
        )

        val (last, lastView, _) = nineSession(syllabic, "64")
        last.onPickReadingIndex(last.expandedReadings().indexOf("ni"))
        last.onPickReadingIndex(last.expandedReadings().indexOf("ni"))
        assertEquals("the last syllable is drilled", 0, last.drilledSyllableForTest())
        assertEquals("and the source names that syllable", "ni", last.lockedHighlightReading())
        lastView.showExpandedCandidates()
        assertEquals(
            "which the expanded grid marks",
            palette.accentBottom,
            lastView.expandedReadingTextColorForTest(last.expandedReadings().indexOf("ni")),
        )
    }

    @Test fun the_reading_column_never_faces_the_user_while_a_drill_is_open() {
        val (c, iv, kb) = nineSession(syllabic, "7426")
        c.onPickReadingIndex(c.expandedReadings().indexOf("shan"))
        iv.showExpandedCandidates()
        c.onPickReadingIndex(c.expandedReadings().indexOf("shan"))

        assertTrue("precondition: a drill is open", c.drilledSyllableForTest() >= 0)
        assertEquals("the keyboard, and with it the reading column, is off screen", View.GONE, kb.visibility)

        iv.showPanel(null)

        assertEquals("closing the grid ends the drill", -1, c.drilledSyllableForTest())
        assertEquals("the keyboard comes back", View.VISIBLE, kb.visibility)
        val marked = markedCells(kb)
        assertEquals("with the lock marked again", 1, marked.size)
        assertEquals("and it is the locked reading", "shan", kb.scrollColumnKeysForTest()[marked.single()].label)
        assertTrue("brought on screen", cellIsOnScreen(kb, marked.single()))
    }

    @Test fun alpha_layout_keeps_the_expanded_locked_reading_highlight() {
        val (c, iv) = session(syllabic)
        c.onKey(act(KeyAction.SWITCH_ALPHA))
        "nihao".forEach { c.onKey(out(it.toString())) }
        iv.showExpandedCandidates()

        val before = c.expandedReadings().indexOf("ni")
        assertTrue("precondition: ni is offered on the 26-key face, was ${c.expandedReadings()}", before >= 0)
        assertEquals(
            "an unlocked 26-key reading starts in the plain candidate color",
            palette.candidateText,
            iv.expandedReadingTextColorForTest(before),
        )

        c.onPickReadingIndex(before)

        val after = c.expandedReadings().indexOf("ni")
        assertTrue("the locked reading stays visible, was ${c.expandedReadings()}", after >= 0)
        assertEquals(
            "the 26-key expanded grid still marks the locked reading with the theme accent color",
            palette.accentBottom,
            iv.expandedReadingTextColorForTest(after),
        )
    }

    @Test fun alpha_layout_exposes_no_left_reading_column() {
        assertNull(
            "the 26-key face carries no scrolling reading column, so it has no unexpanded highlight to render",
            Layouts.forId(LayoutId.ALPHA, Lang.CN, true).scrollColumn,
        )

        val (c, iv) = session(syllabic)
        c.onKey(act(KeyAction.SWITCH_ALPHA))
        "nihao".forEach { c.onKey(out(it.toString())) }
        c.onPickReadingIndex(c.expandedReadings().indexOf("ni"))

        assertEquals(
            "and a locked 26-key reading therefore reaches no keyboard cell",
            emptyList<Key>(),
            keyboardOf(iv).scrollColumnKeysForTest(),
        )
    }
}
