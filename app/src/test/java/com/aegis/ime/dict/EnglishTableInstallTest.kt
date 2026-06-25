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

package com.aegis.ime.dict

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class EnglishTableInstallTest {

    @Test
    fun the_current_external_english_table_is_parseable() {
        val configured = System.getenv("AEGIS_ENGLISH")?.takeIf { it.isNotBlank() }
        assumeTrue("AEGIS_ENGLISH set for the real-table gate", configured != null)
        val file = File(configured!!)
        assertTrue("AEGIS_ENGLISH points to a non-trivial file", file.isFile && file.length() > 1024)

        val table = BinaryDict.fromFile(file)

        assertTrue("the real English table has positive total frequency", table.totalFreq > 0L)
        assertTrue(
            "the real English table carries the known word key",
            table.exact("word").any { it.word == "word" && it.freq > 0 },
        )
        assertTrue("the real English table serves prefix completions", table.prefixByFreq("hel", 16).isNotEmpty())
    }
}
