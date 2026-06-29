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

/** C1: clipboard capture must pause for password / PIN fields (InputType constants inline to ints). */
class ClipboardPolicyTest {

    @Test fun text_password_variations_are_sensitive() {
        assertTrue(ClipboardPolicy.isSensitive(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(ClipboardPolicy.isSensitive(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertTrue(ClipboardPolicy.isSensitive(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
    }

    @Test fun number_password_is_sensitive() {
        assertTrue(ClipboardPolicy.isSensitive(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    // ---- BUG3-1: onSystemClipChanged must not read the clipboard when gated (secure field / history off) ----

    @Test fun no_clip_read_when_gated_by_secure_field_or_history_off() {
        // gated → short-circuit (no getPrimaryClip IPC), restoring the debug.13 behaviour.
        assertFalse("secure field → skip read", ClipboardPolicy.shouldReadSystemClip(true, true))
        assertFalse("history off → skip read", ClipboardPolicy.shouldReadSystemClip(false, false))
        // ungated → read normally.
        assertTrue("normal field, history on → read", ClipboardPolicy.shouldReadSystemClip(false, true))
    }

    @Test fun ordinary_fields_are_not_sensitive() {
        assertFalse(ClipboardPolicy.isSensitive(InputType.TYPE_CLASS_TEXT))
        assertFalse(ClipboardPolicy.isSensitive(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(ClipboardPolicy.isSensitive(InputType.TYPE_CLASS_NUMBER))
        assertFalse(ClipboardPolicy.isSensitive(0))
    }

    // ---- M-3/L-3: on-device learning must be blocked for password + NO_PERSONALIZED_LEARNING fields ----

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

    // ---- copy-bar DISPLAY is decoupled from secure status (the Termius textVisiblePassword bug) ----

    @Test fun copy_bar_restored_for_any_secure_status_when_a_clip_is_pending() {
        // THE REGRESSION: a textVisiblePassword / password field (secureField == true) must NOT hide a clip
        // captured elsewhere. Mutation guard: revert shouldRestoreCopyBar to `lastCopy != null && !secureField`
        // (the old onStartInputView :233 condition) and the secureField == true case flips to false -> RED.
        assertTrue(ClipboardPolicy.shouldRestoreCopyBar("copied text", secureField = true))
        assertTrue(ClipboardPolicy.shouldRestoreCopyBar("copied text", secureField = false))
    }

    @Test fun copy_bar_not_restored_when_no_clip_pending() {
        // No pending clip -> nothing to restore, in either field kind (also pins the call-site `!!` contract).
        assertFalse(ClipboardPolicy.shouldRestoreCopyBar(null, secureField = false))
        assertFalse(ClipboardPolicy.shouldRestoreCopyBar(null, secureField = true))
    }

    @Test fun password_fields_are_still_classified_sensitive_for_the_learning_gate() {
        // isSensitive still flags password / visible-password fields. debug.17: this NO LONGER gates clipboard
        // CAPTURE (secure-field copies are now recorded — only the history switch gates, see
        // ClipboardStore.shouldCapture); isSensitive still gates LEARNING (passwords are never learned).
        assertTrue(ClipboardPolicy.isSensitive(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertTrue(ClipboardPolicy.isSensitive(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
    }
}
