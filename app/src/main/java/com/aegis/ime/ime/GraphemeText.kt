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

import android.icu.text.BreakIterator

/**
 * Backspace-by-grapheme support. A plain `deleteSurroundingText(1, 0)` removes ONE UTF-16 code unit, so a
 * backspace over an emoji — which is almost always a surrogate pair, and for flags / keycaps / ZWJ sequences /
 * VS16 emoji several code points — deletes only half of it and leaves a lone surrogate the app renders as �.
 * Deleting a whole extended grapheme cluster removes the emoji in one press while still taking exactly one
 * character off plain ASCII / Han text (each of those is its own cluster). ICU's character break iterator
 * follows UAX #29 extended grapheme rules, so it clusters regional-indicator pairs, ZWJ sequences, keycaps
 * and modifier/VS16 emoji correctly.
 */
object GraphemeText {

    /**
     * UTF-16 code units to read before the cursor to find the last cluster's start. Comfortably larger than any
     * real emoji cluster (the longest RGI sequences — kiss/family with skin tones — are ~14 units), so the last
     * boundary the iterator reports is always the true cluster start, never a window edge.
     */
    const val WINDOW = 64

    /** Length in UTF-16 code units of the last extended grapheme cluster of [text] (0 when empty). */
    fun lastClusterLength(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        val it = BreakIterator.getCharacterInstance()
        it.setText(text.toString())
        val end = it.last()
        val start = it.previous()
        return if (start == BreakIterator.DONE) end else end - start
    }
}
