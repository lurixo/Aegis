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

/** Editor operations the controller needs from the IME service (backed by InputConnection). */
interface ImeHost {
    fun commitText(text: CharSequence)
    fun deleteBackward()
    fun performEnter()

    /** U25: up to [n] characters of editor text immediately before the cursor (for inline calculation). */
    fun textBeforeCursor(n: Int): CharSequence = ""

    /** U25: delete [length] chars before the cursor and commit [text] in their place (replace an expression). */
    fun replaceBeforeCursor(length: Int, text: CharSequence) {}

    /** U25/M-3: whether the editor currently has a non-empty selection (so a calc replace must not clobber it). */
    fun hasSelection(): Boolean = false

    /**
     * S2 (debug.12): delete the current selection ITSELF (replace it with nothing). [deleteBackward] /
     * deleteSurroundingText is selection-start-relative, so with a selection active it would instead remove
     * the character BEFORE the selection — silent data loss. commitText("") replaces the selected span.
     */
    fun deleteSelection() { commitText("") }

    /** Delete one whole code point before the cursor (so a multi-code-point emoji deletes cleanly instead of
     *  leaving half a surrogate pair). Defaults to [deleteBackward]; the IME overrides it with a
     *  code-point-aware deletion. Used by the emoji/symbol panels' ⌫ via [panelBackspace]. */
    fun deleteCodePointBackward() { deleteBackward() }

    /**
     * F2 (debug.12): the selection-aware ⌫ for the emoji / symbol panels. Their ⌫ buttons used to call
     * deleteSurroundingTextInCodePoints directly, which — like the pre-S2 main key — is selection-START
     * relative and silently ate the char BEFORE an active selection. Mirror the S2 fix: delete the SELECTION
     * if there is one, else remove one code point. Shared by both panels so there is no third divergent path.
     */
    fun panelBackspace() {
        if (hasSelection()) deleteSelection() else deleteCodePointBackward()
    }
}
