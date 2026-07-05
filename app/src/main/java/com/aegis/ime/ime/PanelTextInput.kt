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
  * Chinese IME behavior note.
 * [begin] and [end]), the IME redirects ALL keyboard output here instead of the target app's InputConnection:
 * committed text / pinyin candidate picks / space / backspace / emoji / symbols. On [end] the redirect stops
 * and normal typing resumes — the host's ImeHost methods are written `if (panelInput.<op>()) return; <editor>`,
 * so when this buffer is INACTIVE every op returns false and the editor path runs UNCHANGED (the zero-regression
 * guarantee, unit-tested).
 *
 * Cursor model = end of buffer (append + backspace from the end), enough for editing a short phrase / category
 * name. [onChange] fires whenever the text changes so the host can mirror it into the visible edit bar.
 */
class PanelTextInput {

    private var buf: StringBuilder? = null
    var onChange: (String) -> Unit = {}

    /** True while capturing (between [begin] and [end]). */
    val active: Boolean get() = buf != null

    /** The current buffer text (empty when inactive). */
    fun text(): String = buf?.toString() ?: ""

    /** Start capturing, seeded with [initial] (e.g. the phrase being edited, or "" for a new category). */
    fun begin(initial: String) { buf = StringBuilder(initial); emit() }

    /** Stop capturing — keyboard output goes back to the target app. Buffer text is discarded. */
    fun end() { buf = null }

    /** @return true if consumed (capturing) — caller must NOT write to the editor; false → editor as normal. */
    fun commit(text: CharSequence): Boolean {
        val b = buf ?: return false
        b.append(text); emit(); return true
    }

    /** Delete the last grapheme cluster (so an emoji — surrogate pair, flag, keycap, ZWJ sequence or VS16
     *  form — deletes whole; a lone code-point deletion left half a cluster in the buffer, which the mirrored
     *  edit bar then rendered as �). @return true if consumed. */
    fun backspace(): Boolean {
        val b = buf ?: return false
        if (b.isNotEmpty()) { b.delete(b.length - GraphemeText.lastClusterLength(b), b.length); emit() }
        return true
    }

    /** Inline-calculator support: the buffer tail (null when inactive → host queries the editor). */
    fun textBefore(n: Int): String? = buf?.let { it.substring(maxOf(0, it.length - n)) }

    /** Inline-calculator replace within the buffer. @return true if consumed. */
    fun replaceBefore(length: Int, text: CharSequence): Boolean {
        val b = buf ?: return false
        b.delete(maxOf(0, b.length - length), b.length); b.append(text); emit(); return true
    }

    private fun emit() { onChange(text()) }
}
