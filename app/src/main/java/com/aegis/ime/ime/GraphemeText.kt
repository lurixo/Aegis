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

    fun lastClusterLength(readBeforeCursor: (Int) -> CharSequence): Int {
        var requested = WINDOW
        var text = readBeforeCursor(requested).toString()
        if (text.isEmpty()) return 0
        var length = lastClusterLength(text)
        while (text.length >= requested) {
            val expandedRequest = if (requested > Int.MAX_VALUE / 2) Int.MAX_VALUE else requested * 2
            if (expandedRequest == requested) break
            val expanded = readBeforeCursor(expandedRequest).toString()
            if (expanded.length <= text.length) break
            val expandedLength = lastClusterLength(expanded)
            if (expandedLength == length && boundaryIsStable(expanded, expandedLength)) return expandedLength
            requested = expandedRequest
            text = expanded
            length = expandedLength
        }
        return length
    }

    private fun boundaryIsStable(text: String, clusterLength: Int): Boolean {
        val boundary = text.length - clusterLength
        if (boundary <= 0) return false
        val current = text.codePointAt(boundary)
        val previous = text.codePointBefore(boundary)
        if (previous == 0x200D || current == 0x200D || isGraphemeExtension(current)) return false
        if (isRegionalIndicator(previous) && isRegionalIndicator(current)) {
            var offset = boundary
            while (offset > 0 && isRegionalIndicator(text.codePointBefore(offset))) {
                offset -= Character.charCount(text.codePointBefore(offset))
            }
            if (offset == 0) return false
        }
        return true
    }

    private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

    private fun isGraphemeExtension(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0xE0100..0xE01EF
    }

    fun clusterCount(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        val it = BreakIterator.getCharacterInstance()
        it.setText(text.toString())
        var count = 0
        while (it.next() != BreakIterator.DONE) count++
        return count
    }
}
