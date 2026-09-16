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

import com.aegis.ime.decoder.FullDictTestAssets
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class LockedSyllableUserWordTest {

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)

    private fun assets() = assumeTrue(
        "production dictionary, T9 table, language model and jianpin table present",
        FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile),
    )

    private fun engine(um: UserModel) = DictEngine(
        BinaryDict.fromFile(dictFile),
        BinaryDict.fromFile(t9File),
        CharBigramLM.fromFile(lmFile),
        um,
        emptySet(),
        BinaryDict.fromFile(jianpinFile),
        null,
        UserLearning(),
    )

    @Test fun storedReadingsAreSpelledFromTheWordItself() {
        assets()
        val e = engine(UserModel())
        assertEquals("", e.spelledReading("就是", "jiu"))
        assertEquals("", e.spelledReading("天气", "tian"))
        assertEquals("jiushi", e.spelledReading("就是", "jiushi"))
        assertEquals("xian", e.spelledReading("西安", "xian"))
        assertEquals("the 9-key letter guess is replaced by the spelling with the same keys", "pianmian", e.spelledReading("片面", "qianmian"))
    }
}
