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

import android.view.inputmethod.InputConnection

object EditorSweep {

    const val CHUNK = 65_536
    const val MAX_ROUNDS = 256

    fun hasText(ic: InputConnection): Boolean =
        !ic.getTextBeforeCursor(1, 0).isNullOrEmpty() ||
            !ic.getTextAfterCursor(1, 0).isNullOrEmpty() ||
            !ic.getSelectedText(0).isNullOrEmpty()

    fun nearbyLength(ic: InputConnection, bound: Int = CHUNK): Int =
        (ic.getTextBeforeCursor(bound, 0)?.length ?: 0) +
            (ic.getSelectedText(0)?.length ?: 0) +
            (ic.getTextAfterCursor(bound, 0)?.length ?: 0)

    fun clearCapturing(ic: InputConnection): CharSequence {
        val ending = fieldEnding(ic)
        val body = StringBuilder()
        ic.getSelectedText(0)?.let {
            if (it.isNotEmpty()) {
                body.append(it)
                ic.commitText("", 1)
            }
        }
        val before = sweep(ic, before = true)
        val after = sweep(ic, before = false)
        if (!before.unfinished && !after.unfinished) {
            ic.performContextMenuAction(android.R.id.selectAll)
            ic.commitText("", 1)
        }
        val swept = StringBuilder(before.text).append(body).append(after.text)
        return settleTail(swept, ending)
    }

    private fun fieldEnding(ic: InputConnection): String? {
        val after = ic.getTextAfterCursor(CHUNK, 0) ?: return null
        if (after.length >= CHUNK) return null
        val ending = StringBuilder()
            .append(ic.getTextBeforeCursor(CHUNK, 0) ?: "")
            .append(ic.getSelectedText(0) ?: "")
            .append(after)
            .toString()
        val breaks = ending.takeLastWhile { it == '\n' }
        return if (breaks.length == ending.length && ending.isNotEmpty()) null else breaks
    }

    private fun settleTail(swept: CharSequence, ending: String?): CharSequence {
        if (ending == null) return swept
        return swept.toString().dropLastWhile { it == '\n' } + ending
    }

    private class Side(val text: CharSequence, val unfinished: Boolean)

    private fun sweep(ic: InputConnection, before: Boolean): Side {
        val held = StringBuilder()
        var rounds = 0
        while (rounds < MAX_ROUNDS) {
            rounds++
            val got = (if (before) ic.getTextBeforeCursor(CHUNK, 0) else ic.getTextAfterCursor(CHUNK, 0))
                ?: return Side(held, false)
            if (got.isEmpty()) return Side(held, false)
            val seen = got.toString()
            val cut =
                if (before) ic.deleteSurroundingText(seen.length, 0) else ic.deleteSurroundingText(0, seen.length)
            if (!cut) return Side(held, true)
            if (before) held.insert(0, seen) else held.append(seen)
        }
        return Side(held, true)
    }
}
