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
import org.junit.Assert.assertEquals
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

    // ---- BUG3-1: onSystemClipChanged must not read the clipboard when gated AND nothing is pending ----

    @Test fun no_clip_read_in_a_password_field_or_history_off_unless_a_self_write_is_pending() {
        // gated + no self-write → short-circuit (no getPrimaryClip IPC), restoring the debug.13 behaviour.
        assertFalse("secure field, nothing pending → skip read", ClipboardPolicy.shouldReadSystemClip(false, true, true))
        assertFalse("history off, nothing pending → skip read", ClipboardPolicy.shouldReadSystemClip(false, false, false))
        // a pending self-write must still be read so the guard gets consumed/reset, even while gated.
        assertTrue("secure field but self-write pending → read to consume guard", ClipboardPolicy.shouldReadSystemClip(true, true, true))
        assertTrue("history off but self-write pending → read", ClipboardPolicy.shouldReadSystemClip(true, false, false))
        // ungated → read normally.
        assertTrue("normal field, history on → read", ClipboardPolicy.shouldReadSystemClip(false, false, true))
    }

    // ---- debug.15: clearSystemClipboard must tell the truth (clearPrimaryClip is a no-op on some OEMs) ----

    @Test fun clear_result_reports_success_only_when_the_clipboard_is_actually_empty() {
        // truly cleared: no clip at all, or a clip whose text is empty/blank (our empty overwrite).
        assertEquals(ClipboardPolicy.ClearResult.CLEARED, ClipboardPolicy.clearResult(false, null))
        assertEquals(ClipboardPolicy.ClearResult.CLEARED, ClipboardPolicy.clearResult(true, ""))
        assertEquals(ClipboardPolicy.ClearResult.CLEARED, ClipboardPolicy.clearResult(true, "   "))
        assertEquals("no clip wins even if a stale text is passed", ClipboardPolicy.ClearResult.CLEARED, ClipboardPolicy.clearResult(false, "x"))
        // OEM blocked removal: real content remains → must NOT report success.
        assertEquals(ClipboardPolicy.ClearResult.CONTENT_REMAINS, ClipboardPolicy.clearResult(true, "secret-token"))
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

    @Test fun capture_still_gated_a_visible_password_field_is_still_sensitive() {
        // DISPLAY is decoupled, CAPTURE is not: the terminal field stays "sensitive" so onSystemClipChanged /
        // captureClip (which early-return on secureField) still never harvest what's typed into it.
        assertTrue(ClipboardPolicy.isSensitive(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertTrue(ClipboardPolicy.isSensitive(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
    }
}
