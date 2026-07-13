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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PinyinDecoderUserWordTest {

    private val rows = listOf(
        EngineFixture.Row("ci", "次", 900), EngineFixture.Row("ci", "此", 850), EngineFixture.Row("ci", "词", 800),
        EngineFixture.Row("ci", "佽", 3),
        EngineFixture.Row("shi", "是", 950), EngineFixture.Row("shi", "时", 920), EngineFixture.Row("shi", "试", 680),
        EngineFixture.Row("ku", "库", 900), EngineFixture.Row("ku", "哭", 800),
        EngineFixture.Row("hao", "好", 950),
    )
    private val dict: BinaryDict = EngineFixture.build(rows)
    private val t9Dict: BinaryDict = EngineFixture.build(rows.map { EngineFixture.Row(T9Pinyin.toT9(it.key), it.word, it.freq) })

    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val lm: CharBigramLM by lazy { CharBigramLM.fromFile(lmFile) }
    private val productionDict: BinaryDict by lazy { BinaryDict.fromFile(File("src/main/assets/aegis_dict.bin")) }
    private val productionT9Dict: BinaryDict by lazy { BinaryDict.fromFile(File("src/main/assets/aegis_t9.bin")) }

    @Before fun requireLm() {
        org.junit.Assume.assumeTrue("real LM asset present (production decoder wiring)", lmFile.exists())
    }

    private fun words(c: List<Cand>) = c.map { it.word }
    private fun um(vararg entries: Pair<String, String>): UserModel =
        UserModel().apply { for ((r, w) in entries) recordWord(r, w, 1L, incrementCount = true) }

    private fun letter(um: UserModel? = null) = PinyinDecoder(dict, lm, userModel = um)
    private fun t9(um: UserModel? = null) = PinyinDecoder(t9Dict, lm, userModel = um, aliasDict = dict)

    @Test fun selfCreatedWord_recalled_on_letters() {
        val m = um("cishi" to "此是")
        assertTrue("self-created 此是 recalled for its exact reading", "此是" in words(letter(m).decodeCovered("cishi", 30)))
        assertFalse("without a user model nothing extra appears", "此是" in words(letter().decodeCovered("cishi", 30)))
    }

    @Test fun selfCreatedWord_recalled_on_t9() {
        val m = um("cishi" to "此是")
        val digits = T9Pinyin.toT9("cishi")
        assertTrue("此是 recalled on the 9-key digit reading", "此是" in words(t9(m).decodeCovered(digits, 30)))
        assertFalse("inert without a user model on 9-key too", "此是" in words(t9().decodeCovered(digits, 30)))
    }

    @Test fun selfCreatedWord_recalled_on_locked_path() {
        val m = um("cishi" to "此是")
        val withUser = words(letter(m).decodeCoveredAtomic("cishi", 30, setOf(2)))
        val withoutUser = words(letter().decodeCoveredAtomic("cishi", 30, setOf(2)))
        assertTrue("此是 recalled on the locked path", "此是" in withUser)
        val withRank = withUser.indexOf("此是")
        val withoutRank = withoutUser.indexOf("此是")
        assertTrue("the user model makes 此是 at least as prominent on the locked path ($withRank vs $withoutRank)",
            withoutRank < 0 || withRank <= withoutRank)
    }

    @Test fun differentWordLengthsAndReadings_allRecalled() {
        val m = um("cishi" to "此是", "kushi" to "哭是", "cikuhao" to "词库好")
        assertTrue("2-char reading cishi", "此是" in words(letter(m).decodeCovered("cishi", 30)))
        assertTrue("2-char reading kushi", "哭是" in words(letter(m).decodeCovered("kushi", 30)))
        assertTrue("3-char reading cikuhao", "词库好" in words(letter(m).decodeCovered("cikuhao", 30)))
        assertFalse("cishi word does not pollute the bare 'ci' input", "此是" in words(letter(m).decodeCovered("ci", 30)))
    }

    @Test fun rareCharacterWord_ranks_below_a_common_candidate_not_at_the_front() {
        val m = um("cishi" to "佽是")
        val list = words(letter(m).decodeCovered("cishi", 30))
        assertTrue("rare-character self-created word still recalled", "佽是" in list)
        assertTrue("a common candidate exists ahead of the rare-character self-created word",
            list.indexOf("佽是") > 0)
    }

    @Test fun freshUserWord_doesNotHijackPosition0_whenNaturalBestIsASentence() {
        val natural = words(letter().decodeCovered("cishi", 30)).first()
        val m = um("cishi" to "此是")
        val withUser = words(letter(m).decodeCovered("cishi", 30))
        assertEquals("a fresh user word must not hijack the commit default", natural, withUser.first())
        assertTrue("the natural best is a multi-character sentence (the hijack-prone case)", natural.codePointCount(0, natural.length) >= 2)
        assertTrue("but the user word is still recalled", "此是" in withUser)
    }

    @Test fun heavilyUsedUserWord_mayFairlyReachPosition0() {
        val natural = words(letter().decodeCovered("cishi", 30)).first()
        assertTrue("(sanity) the natural best differs from the user word", natural != "此是")
        var minCount = -1
        var prevRank = Int.MAX_VALUE
        var n = 1
        while (n <= 8192) {
            val m = UserModel().apply { repeat(n) { recordWord("cishi", "此是", it.toLong(), incrementCount = true) } }
            val rank = words(letter(m).decodeCovered("cishi", 30)).indexOf("此是")
            assertTrue("recalled at every count (n=$n rank=$rank)", rank >= 0)
            assertTrue("rank never worsens as use accumulates (n=$n: $rank > $prevRank)", rank <= prevRank)
            prevRank = rank
            if (rank == 0 && minCount < 0) minCount = n
            n *= 2
        }
        assertTrue("with enough accumulated use the word becomes the commit default (minCount=$minCount)", minCount > 0)
    }

    @Test fun usage_boost_lifts_a_reused_self_created_word() {
        val light = um("cishi" to "此是")
        val heavy = UserModel().apply { repeat(2048) { recordWord("cishi", "此是", it.toLong(), incrementCount = true) } }
        val lightRank = words(letter(light).decodeCovered("cishi", 30)).indexOf("此是")
        val heavyRank = words(letter(heavy).decodeCovered("cishi", 30)).indexOf("此是")
        assertTrue("both recall the word", lightRank >= 0 && heavyRank >= 0)
        assertTrue("a fresh word is not the commit default (a natural candidate precedes it)", lightRank > 0)
        assertTrue("heavy use lifts the self-created word strictly higher ($heavyRank < $lightRank)", heavyRank < lightRank)
    }

    @Test fun exact_conflict_dedup_uses_learning_without_permanently_pinning_the_word() {
        val target = "自词"
        val competitor = "常词"
        val anchor = "优词"
        val reading = "zici"
        val controlledDict = EngineFixture.build(
            listOf(
                EngineFixture.Row(reading, target, 10),
                EngineFixture.Row(reading, competitor, 1_000),
                EngineFixture.Row(reading, anchor, 1_000_000_000),
            ),
        )
        val cp = listOf('前', '自', '常', '优', '词').associate { it.code to 1_000L }
        val controlledLm = EngineFixture.buildLm(
            cp,
            mapOf(
                ('前'.code to '自'.code) to 1L,
                ('前'.code to '常'.code) to 1_000_000L,
            ),
        )
        fun learned(count: Int) = UserModel().apply {
            repeat(count) { recordWord(reading, target, it.toLong(), incrementCount = true) }
        }
        fun decoded(model: UserModel, context: String = "") =
            PinyinDecoder(controlledDict, controlledLm, userModel = model)
                .decodeCovered(reading, 30, context = context)
                .map { it.word }

        val light = decoded(learned(1))
        assertTrue(light.indexOf(competitor) < light.indexOf(target))
        val learned = learned(3)
        val stronger = decoded(learned)
        assertEquals(anchor, stronger.first())
        assertTrue(stronger.indexOf(target) < stronger.indexOf(competitor))
        assertEquals(1, stronger.count { it == target })

        val contextual = decoded(learned, "前")
        assertEquals(anchor, contextual.first())
        assertTrue(contextual.indexOf(competitor) < contextual.indexOf(target))

        val atomic = PinyinDecoder(controlledDict, controlledLm, userModel = learned)
        val atomicPlain = atomic.decodeCoveredAtomic(reading, 30, setOf(2)).map { it.word }
        val atomicContext = atomic.decodeCoveredAtomic(reading, 30, setOf(2), "前").map { it.word }
        assertEquals(anchor, atomicPlain.first())
        assertEquals(anchor, atomicContext.first())
        assertTrue(atomicPlain.indexOf(target) < atomicPlain.indexOf(competitor))
        assertTrue(atomicContext.indexOf(competitor) < atomicContext.indexOf(target))
        assertEquals(1, atomicPlain.count { it == target })
    }

    @Test fun exact_pair_beyond_free_edge_cap_uses_dictionary_frequency_on_letters_and_t9() {
        val reading = "yizhi"
        val digits = T9Pinyin.toT9(reading)
        val target = "义肢"
        val competitor = "一直"
        assertTrue(productionDict.exact(reading).indexOfFirst { it.word == target } >= PinyinDecoder.EDGE_N)
        assertTrue(productionT9Dict.exact(digits).indexOfFirst { it.word == target } >= PinyinDecoder.EDGE_N)

        fun learned(count: Int) = UserModel().apply {
            repeat(count) { recordWord(reading, target, it.toLong(), incrementCount = true) }
        }
        fun letterBest(count: Int) =
            PinyinDecoder(productionDict, lm, userModel = learned(count)).decodeCovered(reading, 30).first().word
        fun t9Best(count: Int) =
            PinyinDecoder(productionT9Dict, lm, userModel = learned(count), aliasDict = productionDict)
                .decodeCovered(digits, 30).first().word

        assertEquals(competitor, letterBest(4))
        assertEquals(competitor, t9Best(4))
        assertEquals(target, letterBest(5))
        assertEquals(target, t9Best(5))
    }

    @Test fun exact_pair_beyond_atomic_edge_cap_uses_dictionary_frequency_before_boost() {
        val reading = "zici"
        val target = "自词"
        val blocker = "常词"
        val controlledDict = EngineFixture.build(
            listOf(
                EngineFixture.Row(reading, blocker, 5_000),
                EngineFixture.Row(reading, "高词", 4_500),
                EngineFixture.Row(reading, "强词", 4_000),
                EngineFixture.Row(reading, "前词", 3_500),
                EngineFixture.Row(reading, "先词", 3_000),
                EngineFixture.Row(reading, "首词", 2_500),
                EngineFixture.Row(reading, target, 100),
                EngineFixture.Row("zi", "自", 1),
                EngineFixture.Row("ci", "词", 1),
            ),
        )
        val exactWords = controlledDict.exact(reading).filter { it.word.codePointCount(0, it.word.length) > 1 }
        assertTrue(exactWords.indexOfFirst { it.word == target } >= PinyinDecoder.SENTENCE_EDGE_N)

        fun best(count: Int): String {
            val model = UserModel().apply {
                repeat(count) { recordWord(reading, target, it.toLong(), incrementCount = true) }
            }
            return PinyinDecoder(controlledDict, null, userModel = model)
                .decodeCoveredAtomic(reading, 30, setOf(2)).first().word
        }

        assertEquals(blocker, best(2))
        assertEquals(target, best(3))
    }
}
