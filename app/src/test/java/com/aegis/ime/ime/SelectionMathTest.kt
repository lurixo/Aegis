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

import com.aegis.ime.ime.SelectionMath.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
  * Chinese IME behavior note.
 * The host keeps an anchor fixed and steps the moving end via [SelectionMath.step]; the span between them is
 * what gets selected. These lock the pure math (host-side setSelection(anchor, moving) is the thin wrapper).
 */
class SelectionMathTest {

    /** Mirror the host: hold [anchor] fixed, fold each move into the moving end, return the spanned substring. */
    private fun span(text: String, anchor: Int, vararg moves: Move): String {
        var moving = anchor
        for (m in moves) moving = SelectionMath.step(text, moving, m)
        return text.substring(minOf(anchor, moving), maxOf(anchor, moving))
    }

    @Test fun startSelectThenRight_actuallySelects() {
        // Chinese IME behavior note.
        val t = "abcdef"
        assertTrue("一次 → 后必须有选区 (非空)", span(t, 2, Move.RIGHT).isNotEmpty())
        assertEquals("cd", span(t, 2, Move.RIGHT, Move.RIGHT))
    }

    @Test fun startSelectThenLeft_selectsTowardTheStart() {
        assertEquals("bc", span("abcdef", 3, Move.LEFT, Move.LEFT))
    }

    @Test fun rightThenLeft_shrinksBackToEmpty() {
        // Walking the moving end back onto the anchor collapses the selection (no runaway one-directional growth).
        assertEquals("", span("abcdef", 2, Move.RIGHT, Move.RIGHT, Move.LEFT, Move.LEFT))
    }

    @Test fun horizontalMovesClampAtTheEdges() {
        assertEquals(0, SelectionMath.step("abc", 0, Move.LEFT))    // can't go past the start
        assertEquals(3, SelectionMath.step("abc", 3, Move.RIGHT))   // can't go past the end
    }

    @Test fun homeEndJumpToParagraphEdges() {
        // Chinese IME behavior note.
        val t = "abc\ndefgh\nij" //  abc | defgh | ij   (\n at 3 and 9)
        val mid = 6 // 'f' inside "defgh"
        assertEquals("段首 → start of THIS paragraph", 4, SelectionMath.step(t, mid, Move.HOME))
        assertEquals("段尾 → end of THIS paragraph", 9, SelectionMath.step(t, mid, Move.END))
        // …and used as a selection extension they paint the run from the caret to that edge.
        assertEquals("段首 selects back to 'd'", "de", span(t, mid, Move.HOME))
        assertEquals("段尾 selects forward through 'h'", "fgh", span(t, mid, Move.END))
    }

    @Test fun verticalMovesKeepTheColumnAcrossParagraphs() {
        val t = "abc\ndefgh\nij"
        // From col 2 of "defgh" (index 6) → UP lands on col 2 of "abc" (index 2 = 'c').
        assertEquals(2, SelectionMath.step(t, 6, Move.UP))
        // …DOWN from there lands on col 2 of "ij", which is shorter → clamps to its end (index 12).
        assertEquals(12, SelectionMath.step(t, 6, Move.DOWN))
    }

    @Test fun verticalMovesOffTheEndsReachTheVeryStartOrEnd() {
        val t = "abc\ndef"
        assertEquals("UP from the first paragraph → very start", 0, SelectionMath.step(t, 1, Move.UP))
        assertEquals("DOWN from the last paragraph → very end", t.length, SelectionMath.step(t, 5, Move.DOWN))
    }

    @Test fun stepIsBoundsSafeForOutOfRangePositions() {
        // The host may hand a stale moving index across editor swaps — must clamp, never throw.
        assertEquals(0, SelectionMath.step("abc", -5, Move.LEFT))
        assertEquals(3, SelectionMath.step("abc", 99, Move.RIGHT))
    }
}
