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

/**
 * E5: commit a possibly-huge clip (e.g. a million-char paste) to the editor in binder-safe pieces. A single
 * InputConnection.commitText carries the whole CharSequence across the ~1 MB binder transaction limit, so a
 * large clip throws TransactionTooLargeException. Splitting it into ≤[CHUNK]-char ordered pieces stays well
 * under that limit while reproducing the exact same text. Pure + side-effect-free (the sink does the IPC), so
 * the chunking is unit-testable without an InputConnection.
 */
object LargeCommit {
    /** ~200 KB worst case (UTF-16, 2 bytes/char) — comfortably below the ~1 MB binder limit. */
    const val CHUNK = 100_000

    /** Feed [text] to [sink] in order, in ≤[CHUNK]-char pieces (one piece if it already fits). */
    inline fun commit(text: CharSequence, sink: (CharSequence) -> Unit) {
        if (text.length <= CHUNK) { sink(text); return }
        var i = 0
        while (i < text.length) {
            val end = minOf(i + CHUNK, text.length)
            sink(text.subSequence(i, end))
            i = end
        }
    }
}
