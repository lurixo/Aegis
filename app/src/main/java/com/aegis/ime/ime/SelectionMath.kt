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

object SelectionMath {

    enum class Move { LEFT, RIGHT, UP, DOWN, HOME, END }

    fun step(text: CharSequence, moving: Int, move: Move): Int {
        val n = text.length
        val p = moving.coerceIn(0, n)
        return when (move) {
            Move.LEFT -> (p - 1).coerceAtLeast(0)
            Move.RIGHT -> (p + 1).coerceAtMost(n)
            Move.HOME -> lineStart(text, p)
            Move.END -> lineEnd(text, p)
            Move.UP -> verticalMove(text, p, up = true)
            Move.DOWN -> verticalMove(text, p, up = false)
        }
    }

    private fun lineStart(text: CharSequence, p: Int): Int {
        var i = p - 1
        while (i >= 0) { if (text[i] == '\n') return i + 1; i-- }
        return 0
    }

    private fun lineEnd(text: CharSequence, p: Int): Int {
        var i = p
        while (i < text.length) { if (text[i] == '\n') return i; i++ }
        return text.length
    }

    private fun verticalMove(text: CharSequence, p: Int, up: Boolean): Int {
        val ls = lineStart(text, p)
        val col = p - ls
        return if (up) {
            if (ls == 0) 0
            else {
                val prevStart = lineStart(text, ls - 1)
                (prevStart + col).coerceAtMost(ls - 1)
            }
        } else {
            val le = lineEnd(text, p)
            if (le == text.length) text.length
            else {
                val nextStart = le + 1
                (nextStart + col).coerceAtMost(lineEnd(text, nextStart))
            }
        }
    }
}
