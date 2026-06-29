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

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GraphemeDeleteTest {

    private class FakeEditor(text: String) {
        val buf = StringBuilder(text)
        private fun before(n: Int) = buf.substring(maxOf(0, buf.length - n))
        private fun deleteLastCluster() {
            val n = GraphemeText.lastClusterLength(before(GraphemeText.WINDOW))
            val take = if (n > 0) n else 1
            buf.delete(buf.length - take, buf.length)
        }
        fun mainKeyBackspace() = deleteLastCluster()
    }

    @Test fun plain_text_still_deletes_exactly_one_character() {
        for (s in listOf("ab", "中文", "a中", "1中2", "hello世界")) {
            val e = FakeEditor(s)
            e.mainKeyBackspace()
            assertEquals("plain '$s' must lose exactly its last char", s.substring(0, s.length - 1), e.buf.toString())
        }
        assertEquals(1, GraphemeText.lastClusterLength("好"))
        assertEquals(1, GraphemeText.lastClusterLength("a"))
        assertEquals(0, GraphemeText.lastClusterLength(""))
    }
}
