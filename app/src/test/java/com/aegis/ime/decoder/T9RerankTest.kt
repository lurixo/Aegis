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
 * whole top-N candidate list, not just slot #1. 943943 = 谢谢/这些 collision (这些 has ~21× the corpus
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
        // The legitimate path for the 谢谢/这些 case: enough commits of 谢谢 flip it to #1.
        val user = UserModel().apply { repeat(30) { record(null, "谢谢", 1) } }
        val list = decoder(user).decodeCovered("943943", 30).map { it.word }
        assertEquals("谢谢", list.first())
        assertTrue("谢谢 now ranks before 这些", list.indexOf("谢谢") < list.indexOf("这些"))
    }
}
