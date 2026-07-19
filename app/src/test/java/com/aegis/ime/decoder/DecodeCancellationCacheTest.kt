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
import com.aegis.ime.dict.DecodeCancellation
import com.aegis.ime.dict.Fuzzy
import com.aegis.ime.dict.OctagramReader
import com.aegis.ime.engine.T9_FUZZY_PENALTY
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DecodeCancellationCacheTest {

    private val clock = 1_789_000_000_000L
    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)

    private val dict by lazy { BinaryDict.fromFile(dictFile) }
    private val t9 by lazy { BinaryDict.fromFile(t9File) }
    private val lm by lazy { CharBigramLM.fromFile(lmFile) }
    private val jianpin by lazy { BinaryDict.fromFile(jianpinFile) }
    private val gram by lazy {
        System.getenv("AEGIS_GRAM")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }?.let(OctagramReader::fromFile)
    }

    private fun stores(): Pair<UserModel, UserLearning> {
        val um = UserModel { clock }
        um.recordWord("nihaoya", "你好呀", clock, incrementCount = true)
        um.recordWord("shijie", "诗界", clock, incrementCount = true)
        um.addManualWord("wodemingzi", "我的名字", clock)
        val learning = UserLearning { clock }
        repeat(4) {
            learning.observeCommit(null, "世", "shi", clock)
            learning.observeCommit("世", "界", "jie", clock)
            learning.observeBreak()
        }
        return um to learning
    }

    private fun letters(): PinyinDecoder {
        val (um, learning) = stores()
        return PinyinDecoder(
            dict, lm, userModel = um, fuzzyRules = Fuzzy.DEFAULT_RULE_KEYS, initialsDict = jianpin,
            octagram = gram, userLearning = learning,
        )
    }

    private fun digits(): PinyinDecoder {
        val (um, learning) = stores()
        return PinyinDecoder(
            t9, lm, userModel = um, fuzzyRules = Fuzzy.DEFAULT_RULE_KEYS, octagram = gram, aliasDict = dict,
            userLearning = learning, fuzzyVariants = { s, rules -> T9Pinyin.fuzzyVariants(s, rules) },
            fuzzyPenalty = T9_FUZZY_PENALTY,
        )
    }

    private fun assumeAssets() = assumeTrue(
        "full dictionary assets present",
        FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile),
    )

    private fun checkpointsIn(block: () -> Unit): Int {
        var polls = 0
        DecodeCancellation.attempt({ polls++; false }, block)
        return polls
    }

    private fun <T> cancellingAnywhereLeavesNoTrace(fresh: () -> PinyinDecoder, decode: (PinyinDecoder) -> T) {
        val expected = decode(fresh())
        val total = checkpointsIn { decode(fresh()) }
        assertTrue("the decode passes through checkpoints: $total", total > 3)
        val stops = ((1..minOf(total, 48)) + (49..total step maxOf(1, total / 48))).distinct()
        for (stop in stops) {
            val decoder = fresh()
            var polls = 0
            val cancelled = DecodeCancellation.attempt({ ++polls >= stop }) { decode(decoder) }
            assertNull("stopping at checkpoint $stop of $total cancels the decode", cancelled)
            assertEquals("after stopping at checkpoint $stop of $total the same decoder decodes as a fresh one", expected, decode(decoder))
            assertEquals("and keeps doing so", expected, decode(decoder))
        }
    }

    @Test fun cancelling_a_26key_decode_at_any_checkpoint_leaves_every_cache_clean() {
        assumeAssets()
        for (input in listOf("nihaoya", "shijie", "zhongguoren", "wodemingzi", "zhognguo")) {
            cancellingAnywhereLeavesNoTrace(::letters) { it.decodeCovered(input, 30, emptySet(), "今天") }
        }
    }

    @Test fun cancelling_a_9key_decode_at_any_checkpoint_leaves_every_cache_clean() {
        assumeAssets()
        for (input in listOf("nihaoya", "shijie", "zhongguoren")) {
            val keys = T9Pinyin.toT9(input)
            cancellingAnywhereLeavesNoTrace(::digits) { it.decodeCovered(keys, 30, emptySet(), "我们") }
        }
    }

    @Test fun cancelling_locked_and_guessed_decodes_at_any_checkpoint_leaves_every_cache_clean() {
        assumeAssets()
        cancellingAnywhereLeavesNoTrace(::letters) { it.decodeCoveredAtomic("nihaoshijie", 30, setOf(2, 5, 8), "") }
        cancellingAnywhereLeavesNoTrace(::letters) { it.guessLockedWords("ni", "hoa", false, setOf(2), "", 3) }
        cancellingAnywhereLeavesNoTrace(::letters) { it.guessLockedWords("ni", "462", true, setOf(2), "", 3) }
    }
}
