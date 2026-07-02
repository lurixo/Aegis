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
import java.io.File

/**
 * Locks the 9-key T9 candidate-list rerank: user learning (and, when present, octagram) now order the
  * Chinese IME behavior note.
 * frequency, so context-free it legitimately leads; only learning /
 * context / ★E reading-selection can flip a same-code word).
 */
class T9RerankTest {

    private val t9File = File("src/main/assets/aegis_t9.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")

    private fun decoder(user: UserModel? = null): PinyinDecoder {
        assumeTrue("T9 + LM assets present", t9File.exists() && lmFile.exists())
        return PinyinDecoder(BinaryDict.fromFile(t9File), CharBigramLM.fromFile(lmFile), userModel = user)
    }

    @Test
    fun bareSameCodeIsFrequencyOrdered() {
        // Honest baseline: with no learning/context, the corpus-frequent word leads (model can't help).
        val list = decoder().decodeCovered("943943", 30).map { it.word }
        assertEquals("这些", list.first())
        assertTrue("谢谢 is present, just outranked by frequency", list.contains("谢谢"))
    }

    @Test
    fun listTailConsumesLearning_theFix() {
        // ★ THE FIX: previously the candidate list tail was pure frequency and ignored user learning
        // (only #1/bestSentence saw it). Now the whole top-N is model-scored, so a learned low-frequency
        // tail word climbs the visible list even while it is not yet #1.
        val before = decoder().decodeCovered("943943", 30).map { it.word }
        val user = UserModel().apply { repeat(40) { record(null, "写者", 1) } }
        val after = decoder(user).decodeCovered("943943", 30).map { it.word }
        assertTrue("写者 present before", before.contains("写者"))
        assertTrue("learned 写者 climbs the list tail (was pure-freq, now model-scored)",
            after.indexOf("写者") < before.indexOf("写者"))
    }

    @Test
    fun learningDisambiguatesSameCodeToTop1() {
        // Chinese IME behavior note.
        val user = UserModel().apply { repeat(30) { record(null, "谢谢", 1) } }
        val list = decoder(user).decodeCovered("943943", 30).map { it.word }
        assertEquals("谢谢", list.first())
        assertTrue("谢谢 now ranks before 这些", list.indexOf("谢谢") < list.indexOf("这些"))
    }

    @Test
    fun contextDisambiguatesSameCode_theFix() {
        // Chinese IME behavior note.
        // Chinese IME behavior note.
        // prior learning and no octagram needed. This is the universal same-code disambiguation.
        val d = decoder()
        assertEquals("各个", d.decodeCovered("4343", 30).firstOrNull()?.word)
        assertEquals("哥哥", d.decodeCovered("4343", 30, context = "大").firstOrNull()?.word)
    }

    @Test
    fun emptyContextIsIdentity() {
        // The context plumbing must be a strict no-op when there is no preceding Han text → zero
        // regression for a fresh buffer / start of field.
        val d = decoder()
        val none = d.decodeCovered("4343", 30).map { it.word }
        val empty = d.decodeCovered("4343", 30, context = "").map { it.word }
        val punct = d.decodeCovered("4343", 30, context = "你好。").map { it.word } // 。 breaks context
        assertEquals(none, empty)
        assertEquals(none, punct)
        assertEquals("各个", none.first())
    }

    @Test
    fun contextDoesNotOverflipWhenFreqWordIsCorrect() {
        // Chinese IME behavior note.
        assertEquals("这些", decoder().decodeCovered("943943", 30, context = "我喜欢").firstOrNull()?.word)
    }
}
