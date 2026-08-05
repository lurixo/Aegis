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

package com.aegis.ime.decoder

import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class StoredWordReadingTest {

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)

    private val clock = 1_700_000_000_000L

    private fun assets() = assumeTrue(
        "production dictionary, T9 table, language model and jianpin table present",
        FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile),
    )

    private fun letters(um: UserModel? = null) = PinyinDecoder(
        BinaryDict.fromFile(dictFile),
        CharBigramLM.fromFile(lmFile),
        userModel = um,
        initialsDict = BinaryDict.fromFile(jianpinFile),
    )

    private fun digits(um: UserModel? = null) = PinyinDecoder(
        BinaryDict.fromFile(t9File),
        CharBigramLM.fromFile(lmFile),
        userModel = um,
        aliasDict = BinaryDict.fromFile(dictFile),
    )


    private fun taught(reading: String, word: String): UserModel =
        UserModel { clock }.apply { repeat(500) { recordWord(reading, word, clock, incrementCount = true) } }

    private fun words(cands: List<Cand>) = cands.map { it.word }

    private fun paths(d: PinyinDecoder, key: String, cuts: Set<Int>): List<Pair<String, List<String>>> = listOf(
        "free" to words(d.decodeCovered(key, 80)),
        "cut" to words(d.decodeCovered(key, 80, cuts)),
        "locked" to words(d.decodeCoveredAtomic(key, 80, cuts)),
    )


    @Test fun aStoredWordThatCannotBeReadAsItsReadingIsNeverOffered() {
        assets()
        val junk = "随让啊你的啊你是"
        val um = taught("suirandanshi", junk)
        for ((path, got) in paths(letters(um = um), "suirandanshi", setOf(3, 6, 9))) {
            assertEquals("26-key/$path: 虽然但是 leads, was ${got.take(6)}", "虽然但是", got.first())
            assertTrue("26-key/$path: a word that cannot be read as the reading is dropped", junk !in got)
        }
        for ((path, got) in paths(digits(um = um), "784726326744", setOf(3, 6, 9))) {
            assertTrue("9-key/$path: a word that cannot be read as the reading is dropped", junk !in got)
        }
    }
}
