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
import com.aegis.ime.dict.Fuzzy
import com.aegis.ime.dict.OctagramReader
import com.aegis.ime.engine.T9_FUZZY_PENALTY
import com.aegis.ime.engine.T9_GRAMMAR_CHAR_PENALTY
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class IncrementalLatticeTest {

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)
    private val gramFile = System.getenv("AEGIS_GRAM")?.takeIf { it.isNotEmpty() }?.let { File(it) }

    private val dict by lazy { BinaryDict.fromFile(dictFile) }
    private val t9 by lazy { BinaryDict.fromFile(t9File) }
    private val lm by lazy { CharBigramLM.fromFile(lmFile) }
    private val jianpin by lazy { BinaryDict.fromFile(jianpinFile) }
    private val gram by lazy { OctagramReader.fromFile(gramFile!!) }

    private val bucketStart = 1_700_000_000_000L

    private var now = bucketStart + 1_000L

    private val model = UserModel { now }

    private val learning = UserLearning { now }

    private fun assumeAssets() = assumeTrue(
        "full dictionaries and the grammar are present",
        FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile) && gramFile?.exists() == true,
    )

    private fun decoder(nine: Boolean, chained: Boolean): PinyinDecoder =
        if (nine) {
            PinyinDecoder(
                t9,
                lm,
                userModel = model,
                octagram = gram,
                aliasDict = dict,
                userLearning = learning,
                fuzzyVariants = { s, rules -> T9Pinyin.fuzzyVariants(s, rules) },
                fuzzyPenalty = T9_FUZZY_PENALTY,
                grammarCharPenalty = T9_GRAMMAR_CHAR_PENALTY,
                digitInitialsDict = jianpin,
            ).also { it.reuseLatticeColumns = chained }
        } else {
            PinyinDecoder(
                dict,
                lm,
                userModel = model,
                initialsDict = jianpin,
                octagram = gram,
                userLearning = learning,
            ).also { it.reuseLatticeColumns = chained }
        }

    private fun decode(decoder: PinyinDecoder, nine: Boolean, input: String, context: String, limit: Int): String {
        val sink = PinyinDecoder.PathSink(limit)
        val covered = decoder.decodeCovered(input, CANDIDATES, context, sink)
        val initials = if (nine) decoder.decodeCoveredWithInitials(input, CANDIDATES, context) else emptyList()
        return "$covered|${sink.paths}|$initials"
    }

    private fun keys(nine: Boolean, reading: String) = if (nine) T9Pinyin.toT9(reading) else reading

    @Test fun typingAndErasingEveryPrefixMatchesAFullRecompute() {
        assumeAssets()
        for (nine in listOf(false, true)) for (limit in listOf(3, 20)) for (context in CONTEXTS) {
            val warm = decoder(nine, chained = true)
            val reference = decoder(nine, chained = false)
            for (reading in SENTENCES) {
                val full = keys(nine, reading)
                val lengths = (1..full.length) + (full.length - 1 downTo 1) + (2..full.length)
                for (length in lengths) {
                    val input = full.substring(0, length)
                    assertEquals(
                        "nine=$nine limit=$limit context=$context input=$input",
                        decode(reference, nine, input, context, limit),
                        decode(warm, nine, input, context, limit),
                    )
                }
            }
        }
    }

    @Test fun randomEditsMatchAFullRecompute() {
        assumeAssets()
        for (nine in listOf(false, true)) {
            val random = Random(if (nine) 9 else 26)
            val warm = decoder(nine, chained = true)
            val reference = decoder(nine, chained = false)
            val alphabet = if (nine) "23456789" else "abcdefghijklmnopqrstuvwxyz"
            var target = keys(nine, SENTENCES.random(random))
            var input = ""
            var context = CONTEXTS[0]
            var limit = 20
            var rules = emptySet<String>()
            var previous: String? = null
            val log = ArrayList<String>()
            repeat(STEPS) { step ->
                val roll = random.nextInt(100)
                when {
                    roll < 40 -> {
                        val count = 1 + random.nextInt(3)
                        input += if (target.startsWith(input) && input.length < target.length) {
                            target.substring(input.length, minOf(target.length, input.length + count))
                        } else {
                            String(CharArray(count) { alphabet[random.nextInt(alphabet.length)] })
                        }
                        log.add("type $input")
                    }
                    roll < 55 -> {
                        input = input.dropLast(1 + random.nextInt(3))
                        log.add("erase $input")
                    }
                    roll < 65 && input.isNotEmpty() -> {
                        val at = random.nextInt(input.length)
                        input = input.substring(0, at) + alphabet[random.nextInt(alphabet.length)] + input.substring(at + 1)
                        log.add("edit $input")
                    }
                    roll < 70 -> {
                        target = keys(nine, SENTENCES.random(random))
                        input = target.substring(0, 1 + random.nextInt(target.length))
                        log.add("restart $input")
                    }
                    roll < 75 -> {
                        context = CONTEXTS.random(random)
                        log.add("context $context")
                    }
                    roll < 80 -> {
                        limit = if (limit == 3) 20 else 3
                        log.add("limit $limit")
                    }
                    roll < 85 -> {
                        rules = RULES.random(random)
                        warm.setFuzzyRules(rules)
                        reference.setFuzzyRules(rules)
                        log.add("rules $rules")
                    }
                    roll < 91 -> {
                        val (word, reading) = WORDS.random(random)
                        learning.observeCommit(previous, word, reading, now)
                        previous = word
                        log.add("learn $word")
                    }
                    roll < 94 -> {
                        val (word, reading) = WORDS.random(random)
                        model.recordWord(reading, word, now, incrementCount = true)
                        log.add("use $word")
                    }
                    roll < 97 -> {
                        now += 1_000L
                        log.add("clock $now")
                    }
                    else -> {
                        now += 10_000L
                        log.add("bucket $now")
                    }
                }
                if (input.isNotEmpty()) {
                    assertEquals(
                        "nine=$nine step=$step limit=$limit context=$context rules=$rules ${log.takeLast(6)}",
                        decode(reference, nine, input, context, limit),
                        decode(warm, nine, input, context, limit),
                    )
                }
            }
        }
    }

    private companion object {
        const val CANDIDATES = 30
        const val STEPS = 400
        val CONTEXTS = listOf("", "昨天开会的时候他说", "我们")
        val RULES = listOf(emptySet(), Fuzzy.DEFAULT_RULE_KEYS, setOf("zh"))
        val SENTENCES = listOf(
            "zhegewentizenmejiejue",
            "jintiantianqizhenbucuo",
            "tashiwozuihaodepengyou",
            "womenmingtianqukandianying",
            "nichifanlemawohaimeichi",
        )
        val WORDS = listOf(
            "这个" to "zhege",
            "问题" to "wenti",
            "怎么" to "zenme",
            "解决" to "jiejue",
            "今天" to "jintian",
            "天气" to "tianqi",
            "朋友" to "pengyou",
            "电影" to "dianying",
            "我" to "wo",
            "们" to "men",
        )
    }
}
