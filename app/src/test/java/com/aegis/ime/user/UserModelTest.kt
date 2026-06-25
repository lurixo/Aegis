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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserModelTest {

    @Test
    fun a_word_the_file_format_cannot_hold_is_reported_as_not_added() {
        val m = UserModel()
        assertFalse("a word carrying a delimiter can never be stored", m.addManualWord("ceshi", "测\t试", 1))
        assertFalse("nor can one longer than the format holds", m.addManualWord("ceshi", "词".repeat(257), 2))
        assertTrue("a word that was refused leaves nothing behind", m.isEmpty())
        assertFalse("and queues nothing for the next save", m.dirty)
        assertTrue("a word that does fit is still added", m.addManualWord("ceshi", "词".repeat(256), 3))
    }
}
