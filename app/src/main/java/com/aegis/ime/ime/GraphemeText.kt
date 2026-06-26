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

object GraphemeText {

    const val WINDOW = 64

    fun lastClusterLength(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        val it = BreakIterator.getCharacterInstance()
        it.setText(text.toString())
        val end = it.last()
        val start = it.previous()
        return if (start == BreakIterator.DONE) end else end - start
    }

    fun clusterStart(text: CharSequence, offset: Int): Int {
        val p = offset.coerceIn(0, text.length)
        if (p <= 0 || p >= text.length) return p
        val it = boundaries(text)
        if (it.isBoundary(p)) return p
        val previous = it.preceding(p)
        return if (previous == BreakIterator.DONE) 0 else previous
    }

    fun previousCluster(text: CharSequence, offset: Int): Int {
        val p = offset.coerceIn(0, text.length)
        if (p <= 0) return 0
        val previous = boundaries(text).preceding(p)
        return if (previous == BreakIterator.DONE) 0 else previous
    }

    fun nextCluster(text: CharSequence, offset: Int): Int {
        val p = offset.coerceIn(0, text.length)
        if (p >= text.length) return text.length
        val next = boundaries(text).following(p)
        return if (next == BreakIterator.DONE) text.length else next
    }

    private fun boundaries(text: CharSequence): BreakIterator =
        BreakIterator.getCharacterInstance().apply { setText(text.toString()) }

    fun clusterCount(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        val it = BreakIterator.getCharacterInstance()
        it.setText(text.toString())
        var count = 0
        while (it.next() != BreakIterator.DONE) count++
        return count
    }
}
