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
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StoredWordReadingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)

    private val clock = 1_700_000_000_000L

    private fun assets() = assumeTrue(
        "production dictionary, T9 table, language model and jianpin table present",
        FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile),
    )

    private fun letters(um: UserModel? = null, ul: UserLearning? = null) = PinyinDecoder(
        BinaryDict.fromFile(dictFile),
        CharBigramLM.fromFile(lmFile),
        userModel = um,
        initialsDict = BinaryDict.fromFile(jianpinFile),
        userLearning = ul,
    )

    private fun digits(um: UserModel? = null, ul: UserLearning? = null) = PinyinDecoder(
        BinaryDict.fromFile(t9File),
        CharBigramLM.fromFile(lmFile),
        userModel = um,
        aliasDict = BinaryDict.fromFile(dictFile),
        userLearning = ul,
    )

    private fun taught(reading: String, word: String): UserModel =
        UserModel { clock }.apply { repeat(500) { recordWord(reading, word, clock, incrementCount = true) } }

    private fun glued(reading: String, word: String): UserLearning {
        val file = File(tmp.root, "userlearn-$reading.txt")
        file.writeText("aegis-userlearn 1\nF\t$reading\t$word\t9.0\t$clock\n")
        return UserLearning { clock }.apply { load(file) }
    }

    private fun words(cands: List<Cand>) = cands.map { it.word }

    private fun paths(d: PinyinDecoder, key: String, cuts: Set<Int>): List<Pair<String, List<String>>> = listOf(
        "free" to words(d.decodeCovered(key, 80)),
        "cut" to words(d.decodeCovered(key, 80, cuts)),
        "locked" to words(d.decodeCoveredAtomic(key, 80, cuts)),
    )

    @Test fun aGluedWordThatCannotBeReadAsItsReadingIsNeverOffered() {
        assets()
        val junk = "你呢嗯"
        val ul = glued("suirandanshi", junk)
        assertEquals("the store really holds it", listOf(junk), ul.formedWordsFor("suirandanshi"))
        for ((path, got) in paths(letters(ul = ul), "suirandanshi", setOf(3, 6, 9))) {
            assertEquals("26-key/$path: 虽然但是 leads, was ${got.take(6)}", "虽然但是", got.first())
            assertTrue("26-key/$path: a glued word that cannot be read as the reading is dropped", junk !in got)
        }
        for ((path, got) in paths(digits(ul = ul), "784726326744", setOf(3, 6, 9))) {
            assertTrue("9-key/$path: a glued word that cannot be read as the reading is dropped", junk !in got)
        }
    }

    @Test fun aGluedWordThatDoesReadAsItsReadingIsStillOffered() {
        assets()
        val ul = glued("ninen", "你呢嗯")
        val got = words(digits(ul = ul).decodeCovered("64636", 80))
        assertEquals("9-key: 你们 still leads, was ${got.take(6)}", "你们", got.first())
        assertTrue("9-key: a glued word whose reading fits stays reachable, was ${got.take(8)}", "你呢嗯" in got)
    }

    @Test fun everyKindOfWordTheUserMayAddByHandStaysReachable() {
        assets()
        val entries = listOf(
            "yx" to "我的邮箱",
            "zwm" to "张伟明",
            "nar" to "哪儿",
            "huar" to "花儿",
            "sifen" to "十分",
            "cesi" to "测试",
            "yyds" to "永远的神",
            "hhh" to "哈哈哈",
            "dcall" to "打call",
            "biaoqing" to "😀",
            "email" to "abc@example.com",
        )
        for ((reading, word) in entries) {
            val got = words(letters(um = taught(reading, word)).decodeCovered(reading, 80))
            assertTrue(
                "the user added $word under $reading by hand and must be able to type it, list was ${got.take(8)}",
                word in got,
            )
        }
    }

    @Test fun aWordTheUserAddedByHandSurvivesOnBothKeyboards() {
        assets()
        val um = taught("nar", "哪儿")
        assertTrue("26-key", "哪儿" in words(letters(um = um).decodeCovered("nar", 80)))
        assertTrue("9-key", "哪儿" in words(digits(um = um).decodeCovered("627", 80)))
    }
}
