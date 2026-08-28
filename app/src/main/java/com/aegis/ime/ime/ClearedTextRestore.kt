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

object ClearedTextRestore {

    private const val BREAK = "\n"

    fun restore(text: CharSequence, measure: () -> Int, commit: (CharSequence) -> Unit) {
        val run = text.indexOf('\n')
        if (run < 0) {
            commit(text)
            return
        }
        val leading = text.subSequence(0, run)
        var end = run
        while (end < text.length && text[end] == '\n') end++
        val held = end - run

        val empty = measure()
        if (leading.isNotEmpty()) commit(leading)
        val written = measure()
        commit(BREAK)
        val broken = measure()
        val padding = if (empty == 0 && leading.isNotEmpty()) written - leading.length else 1
        val cost = maxOf(broken - written, padding).coerceIn(1, held)
        commit(BREAK.repeat(breaksFor(held, cost) - 1))
        if (end < text.length) commit(fold(text, end, cost))
    }

    private fun fold(text: CharSequence, from: Int, cost: Int): CharSequence {
        if (cost == 1) return text.subSequence(from, text.length)
        val out = StringBuilder(text.length - from)
        var at = from
        while (at < text.length) {
            val run = text.indexOf('\n', at)
            if (run < 0) {
                out.append(text, at, text.length)
                break
            }
            out.append(text, at, run)
            var end = run
            while (end < text.length && text[end] == '\n') end++
            repeat(breaksFor(end - run, cost)) { out.append(BREAK) }
            at = end
        }
        return out
    }

    private fun breaksFor(held: Int, cost: Int): Int = if (held <= 0) 0 else maxOf(1, held / cost)
}
