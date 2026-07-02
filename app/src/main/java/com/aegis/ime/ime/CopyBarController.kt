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

import com.aegis.ime.user.ClipSplitter

/**
  * Chinese IME behavior note.
  * Chinese IME behavior note.
  * Chinese IME behavior note.
  * Chinese IME behavior note.
 *                    (never setPrimaryClip) and NOT the editor (never commitText).
 *  - [dismiss]     ④ tap × → leave the copy-bar state (back to the normal taskbar).
 */
class CopyBarController(
    private val commit: (String) -> Unit,
    private val copyToAegis: (String) -> Unit,
    private val dismiss: () -> Unit,
) {
    var content: String? = null
        private set
    var splitMode: Boolean = false
        private set
    var blocks: List<String> = emptyList()
        private set

    val active: Boolean get() = content != null

    /** ① enter the copy-bar with [text] (trimmed; blank is ignored). Resets any prior split. */
    fun show(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        content = t
        splitMode = false
        blocks = emptyList()
    }

    /** Chinese IME behavior note. */
    fun toggleSplit() {
        val c = content ?: return
        if (splitMode) { splitMode = false; blocks = emptyList() } else { blocks = ClipSplitter.blocks(c); splitMode = true }
    }

    /** Chinese IME behavior note. */
    fun tapContent() {
        content?.let { commit(it) }
        close()
    }

    /** Chinese IME behavior note. */
    fun tapBlock(block: String) {
        if (block.isNotEmpty()) copyToAegis(block)
    }

    /** ④ × → leave the copy-bar state. */
    fun close() {
        clear()
        dismiss()
    }

    private fun clear() { content = null; splitMode = false; blocks = emptyList() }
}
