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

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardPolicyTest {

    @Test fun learning_blocked_only_when_the_field_opts_out_of_personalized_learning() {
        assertTrue(ClipboardPolicy.blocksLearning(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
    }

    @Test fun a_password_field_that_did_not_opt_out_is_learned_from_like_any_other() {
        assertFalse(ClipboardPolicy.blocksLearning(0))
        assertFalse(ClipboardPolicy.blocksLearning(EditorInfo.IME_ACTION_DONE))
    }

    @Test fun copy_bar_restored_when_a_clip_is_pending() {
        assertTrue(ClipboardPolicy.shouldRestoreCopyBar("copied text"))
    }

    @Test fun copy_bar_not_restored_when_no_clip_pending() {
        assertFalse(ClipboardPolicy.shouldRestoreCopyBar(null))
    }
}
