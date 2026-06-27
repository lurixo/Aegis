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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardPolicyTest {

    @Test fun text_password_variations_are_sensitive() {
        assertTrue(ClipboardPolicy.isSensitive(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(ClipboardPolicy.isSensitive(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertTrue(ClipboardPolicy.isSensitive(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
    }

    @Test fun number_password_is_sensitive() {
        assertTrue(ClipboardPolicy.isSensitive(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test fun ordinary_fields_are_not_sensitive() {
        assertFalse(ClipboardPolicy.isSensitive(InputType.TYPE_CLASS_TEXT))
        assertFalse(ClipboardPolicy.isSensitive(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(ClipboardPolicy.isSensitive(InputType.TYPE_CLASS_NUMBER))
        assertFalse(ClipboardPolicy.isSensitive(0))
    }


    @Test fun learning_blocked_for_password_fields() {
        val pw = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertTrue(ClipboardPolicy.blocksLearning(pw, 0))
        assertTrue(ClipboardPolicy.blocksLearning(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0))
    }

    @Test fun learning_blocked_when_field_opts_out_of_personalized_learning() {
        assertTrue(ClipboardPolicy.blocksLearning(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
    }

    @Test fun learning_allowed_for_ordinary_fields() {
        assertFalse(ClipboardPolicy.blocksLearning(InputType.TYPE_CLASS_TEXT, 0))
        assertFalse(ClipboardPolicy.blocksLearning(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, EditorInfo.IME_ACTION_DONE))
    }
}
