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

package com.aegis.ime.user

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * C1 privacy: decide whether clipboard capture should be paused for the currently-focused field. Password
 * / PIN / 2FA fields (and the like) must NOT silently land in the clipboard history. The check is a pure
 * function of the EditorInfo inputType so it is unit-testable (the InputType constants inline to ints).
 */
object ClipboardPolicy {

    fun isSensitive(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER ->
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /**
     * M-3/L-3 privacy: should on-device learning (UserModel.record → plaintext userdb) be skipped for the
     * focused field? True for password/sensitive fields ([isSensitive]) AND when the field opts out of
     * personalized learning ([EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING]) — so a password or a
     * privacy-flagged field's committed words are never learned, stored in plaintext, or replayed later.
     */
    fun blocksLearning(inputType: Int, imeOptions: Int): Boolean =
        isSensitive(inputType) ||
            (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
}
