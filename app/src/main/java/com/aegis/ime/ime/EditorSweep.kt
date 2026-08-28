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

    fun clearCapturing(ic: InputConnection): CharSequence {
        val body = StringBuilder()
        ic.getSelectedText(0)?.let {
            if (it.isNotEmpty()) {
                body.append(it)
                ic.commitText("", 1)
            }
        }
        val before = sweep(ic, before = true)
        val after = sweep(ic, before = false)
        if (!before.outOfRounds && !after.outOfRounds) {
            ic.performContextMenuAction(android.R.id.selectAll)
            ic.commitText("", 1)
        }
        return StringBuilder(before.text).append(body).append(after.text)
    }

    private class Side(val text: CharSequence, val outOfRounds: Boolean)

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
            if (!cut) return Side(held, false)
            if (before) held.insert(0, seen) else held.append(seen)
        }
        return Side(held, true)
    }
}
