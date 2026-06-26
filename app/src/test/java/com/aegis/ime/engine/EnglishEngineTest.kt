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

package com.aegis.ime.engine

import com.aegis.ime.dict.BinaryDict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class EnglishEngineTest {

    private val enFile = File("src/main/assets/aegis_en.bin")

    private fun engine(): EnglishEngine {
        assumeTrue("en dict present", enFile.exists())
        return EnglishEngine(BinaryDict.fromFile(enFile))
    }

    @Test
    fun completes() {
        val e = engine()
        assertTrue("hel -> hello/help", e.suggest("hel", 10).any { it == "hello" || it == "help" })
        assertEquals("exact word ranks first", "hello", e.suggest("hello", 10).firstOrNull())
    }

    @Test
    fun corrects() {
        val e = engine()
        assertTrue("teh -> the", e.suggest("teh", 10).contains("the"))
        assertTrue("helo -> hello", e.suggest("helo", 10).contains("hello"))
        assertTrue("recieve -> receive", e.suggest("recieve", 10).contains("receive"))
    }

    @Test
    fun preservesCase() {
        val e = engine()
        assertTrue("Capitalized", e.suggest("Hel", 10).any { it == "Hello" || it == "Help" })
    }
}
