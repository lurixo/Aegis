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

class ChunkedRead(
    private val from: Int,
    private val to: Int,
    private val select: (Int) -> Unit,
    private val before: (Int) -> CharSequence?,
    private val done: (CharSequence, Boolean) -> Unit,
) {

    private val out = StringBuilder()
    private var at = from
    private var chunk = CHUNK
    private var awaiting = false
    private var finished = false

    val pending: Boolean get() = awaiting && !finished

    fun begin() {
        if (finished) return
        if (to <= from) { finish(true); return }
        step()
    }

    fun onCaret(caret: Int) {
        if (finished || !awaiting) return
        awaiting = false
        val span = caret - at
        if (span <= 0) { narrow(); return }
        val got = before(span)
        if (got == null || got.length != span) { narrow(); return }
        out.append(got, 0, minOf(caret, to) - at)
        at = caret
        if (at >= to) finish(true) else step()
    }

    fun giveUp() {
        if (!finished) finish(false)
    }

    private fun narrow() {
        if (chunk <= FLOOR) { finish(false); return }
        chunk /= 2
        step()
    }

    private fun step() {
        val target = minOf(at + chunk, to)
        if (target <= at) { finish(false); return }
        awaiting = true
        select(target)
    }

    private fun finish(whole: Boolean) {
        finished = true
        awaiting = false
        done(out, whole)
    }

    companion object {
        const val CHUNK = 65_536
        const val FLOOR = 1_024
        const val DIRECT_MAX = 131_072
    }
}
