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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectionMathTest {

    private fun span(text: String, anchor: Int, vararg moves: Move): String {
        var moving = anchor
        for (m in moves) moving = SelectionMath.step(text, moving, m)
        return text.substring(minOf(anchor, moving), maxOf(anchor, moving))
    }

    @Test fun startSelectThenRight_actuallySelects() {
        val t = "abcdef"
        assertTrue("一次 → 后必须有选区 (非空)", span(t, 2, Move.RIGHT).isNotEmpty())
        assertEquals("cd", span(t, 2, Move.RIGHT, Move.RIGHT))
    }

    @Test fun startSelectThenLeft_selectsTowardTheStart() {
        assertEquals("bc", span("abcdef", 3, Move.LEFT, Move.LEFT))
    }

    @Test fun rightThenLeft_shrinksBackToEmpty() {
        assertEquals("", span("abcdef", 2, Move.RIGHT, Move.RIGHT, Move.LEFT, Move.LEFT))
    }

    @Test fun horizontalMovesClampAtTheEdges() {
        assertEquals(0, SelectionMath.step("abc", 0, Move.LEFT))
        assertEquals(3, SelectionMath.step("abc", 3, Move.RIGHT))
    }

    @Test fun homeEndJumpToParagraphEdges() {
        val t = "abc\ndefgh\nij"
        val mid = 6
        assertEquals("段首 → start of THIS paragraph", 4, SelectionMath.step(t, mid, Move.HOME))
        assertEquals("段尾 → end of THIS paragraph", 9, SelectionMath.step(t, mid, Move.END))
        assertEquals("段首 selects back to 'd'", "de", span(t, mid, Move.HOME))
        assertEquals("段尾 selects forward through 'h'", "fgh", span(t, mid, Move.END))
    }

    @Test fun verticalMovesKeepTheColumnAcrossParagraphs() {
        val t = "abc\ndefgh\nij"
        assertEquals(2, SelectionMath.step(t, 6, Move.UP))
        assertEquals(12, SelectionMath.step(t, 6, Move.DOWN))
    }

    @Test fun verticalMovesOffTheEndsReachTheVeryStartOrEnd() {
        val t = "abc\ndef"
        assertEquals("UP from the first paragraph → very start", 0, SelectionMath.step(t, 1, Move.UP))
        assertEquals("DOWN from the last paragraph → very end", t.length, SelectionMath.step(t, 5, Move.DOWN))
    }

    @Test fun stepIsBoundsSafeForOutOfRangePositions() {
        assertEquals(0, SelectionMath.step("abc", -5, Move.LEFT))
        assertEquals(3, SelectionMath.step("abc", 99, Move.RIGHT))
    }

    @Test fun horizontalMovesStepOverWholeGraphemeClusters() {
        val clusters = listOf("😀", "❤️", "🇨🇳", "👨‍👩‍👧‍👦", "👋🏽", "é")
        for (cluster in clusters) {
            val t = "a${cluster}b"
            val end = 1 + cluster.length
            assertEquals("RIGHT over '$cluster'", end, SelectionMath.step(t, 1, Move.RIGHT))
            assertEquals("LEFT over '$cluster'", 1, SelectionMath.step(t, end, Move.LEFT))
            assertEquals("'$cluster' selects whole", cluster, span(t, 1, Move.RIGHT))
            assertEquals("'$cluster' selects whole backwards", cluster, span(t, end, Move.LEFT))
        }
    }

    @Test fun horizontalMovesNeverLandInsideASurrogatePair() {
        val t = "😀😀"
        assertEquals(2, SelectionMath.step(t, 0, Move.RIGHT))
        assertEquals(4, SelectionMath.step(t, 2, Move.RIGHT))
        assertEquals(2, SelectionMath.step(t, 4, Move.LEFT))
        assertEquals(0, SelectionMath.step(t, 2, Move.LEFT))
    }

    @Test fun verticalMovesSnapOntoAClusterBoundary() {
        val t = "abc\n😀d"
        assertEquals("DOWN keeps the column but not a split surrogate", 4, SelectionMath.step(t, 1, Move.DOWN))
        assertEquals("UP from after the emoji", 2, SelectionMath.step(t, 6, Move.UP))
    }

    @Test fun paragraphEndStopsBeforeACarriageReturnPair() {
        val t = "ab\r\ncd"
        assertEquals(2, SelectionMath.step(t, 1, Move.END))
        assertEquals(4, SelectionMath.step(t, 5, Move.HOME))
    }
}
