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

import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
  * Chinese IME behavior note.
  * Chinese IME behavior note.
 * the LEFT compound drawable, which anchors to its button's left edge, so the gravity-END + right-padding meant
  * Chinese IME behavior note.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BottomBarSymmetryTest {

    private val ctx = RuntimeEnvironment.getApplication()

    /** compoundDrawables = [left, top, right, bottom]. */
    private fun assertBackspaceHugsRightSymmetrically(back: TextView, backspace: TextView, name: String) {
        assertNull("$name: ⌫ must NOT be a LEFT compound drawable (that anchors it to the button's left edge)", backspace.compoundDrawables[0])
        assertNotNull("$name: ⌫ must be the END/right compound drawable so it hugs the right edge", backspace.compoundDrawables[2])
        // Chinese IME behavior note.
        assertTrue("$name: 返回 must have a left inset", back.paddingLeft > 0)
        assertEquals("$name: ⌫ right inset must mirror 返回's left inset", back.paddingLeft, backspace.paddingRight)
        // and the insets that would break the mirror must be zero.
        assertEquals("$name: 返回 must not also be right-inset", 0, back.paddingRight)
        assertEquals("$name: ⌫ must not also be left-inset", 0, backspace.paddingLeft)
    }

    @Test fun symbols_bottom_bar_is_left_right_symmetric() {
        val v = SymbolsView(ctx)
        assertBackspaceHugsRightSymmetrically(v.backBtnForTest(), v.backspaceBtnForTest(), "SymbolsView")
    }

    @Test fun emoji_bottom_bar_is_left_right_symmetric() {
        val v = EmojiView(ctx)
        assertBackspaceHugsRightSymmetrically(v.backBtnForTest(), v.backspaceBtnForTest(), "EmojiView")
    }
}
