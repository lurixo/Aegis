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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UserStoreReadLaneTest {

    @get:Rule val temp = TemporaryFolder()

    private fun userDbWith(vararg entries: Pair<String, String>): File {
        val file = temp.newFile("userdb.txt")
        UserModel().apply { for ((reading, word) in entries) addManualWord(reading, word, 1L) }.save(file)
        return file
    }

    @Test fun an_undisturbed_reload_still_replaces_the_dictionary() {
        val file = userDbWith("wb" to "外部")
        val model = UserModel().apply { addManualWord("bd", "本地", 1L) }

        assertTrue("an undisturbed read must adopt what it read", model.reloadIfUnchanged(file))

        assertEquals(listOf("外部"), model.readingSnapshot()["wb"])
        assertNull("the reload replaces the store, it does not merge into it", model.readingSnapshot()["bd"])
    }
}
