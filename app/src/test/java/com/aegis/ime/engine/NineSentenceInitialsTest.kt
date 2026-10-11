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

import com.aegis.ime.decoder.Cand
import com.aegis.ime.decoder.EngineFixture
import com.aegis.ime.decoder.FullDictTestAssets
import com.aegis.ime.decoder.PinyinCorrection
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.dict.OctagramFixture
import com.aegis.ime.dict.OctagramReader
import com.aegis.ime.ime.ImeHost
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.LayoutId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NineSentenceInitialsTest {
    private class Host : ImeHost {
        val commits = ArrayList<String>()
        override fun commitText(text: CharSequence) { commits.add(text.toString()) }
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    private val rows = listOf(
        EngineFixture.Row("jin", "今", 500), EngineFixture.Row("tian", "天", 600),
        EngineFixture.Row("jintian", "今天", 900), EngineFixture.Row("xiang", "乡", 100),
        EngineFixture.Row("zhen", "镇", 100), EngineFixture.Row("wo", "我", 950),
        EngineFixture.Row("ni", "你", 800),
    )

    private val initials = listOf(
        EngineFixture.Row("tq", "天气", 700), EngineFixture.Row("xhangzhen", "想着你", 1_000_000),
        EngineFixture.Row("wsm", "为什么", 800), EngineFixture.Row("jttq", "今天天气", 300),
        EngineFixture.Row("j", "就", 400),
    )

    private fun engine(initialRows: List<EngineFixture.Row>? = initials, grammar: OctagramReader? = null) = DictEngine(
        EngineFixture.build(rows),
        EngineFixture.build(rows.map { it.copy(key = T9Pinyin.toT9(it.key)) }),
        null,
        initialsDict = initialRows?.let(EngineFixture::build),
        octagram = grammar,
    )

    private fun grammar() = OctagramFixture.reader(mapOf("无关" to 10.0))

    private fun spelledOnly(engine: CandidateEngine): CandidateEngine = object : CandidateEngine by engine {
        override fun candidatesWithInitials(digits: String, context: CharSequence): List<Cand> = emptyList()
    }

    private fun nine(engine: CandidateEngine, host: Host = Host()) = KeyboardController(host, engine).apply {
        setCnDefaultLayout(LayoutId.NINE)
        reset()
    }

    private val segment = Key("'", action = KeyAction.SEGMENT)

    private fun KeyboardController.type(input: String) {
        for (ch in input) onKey(if (ch == '\'') segment else Key(ch.toString(), output = ch.toString()))
    }

    private fun assertEveryTypedPrefixIsUntouched(engine: DictEngine, letters: String): Int {
        val digits = T9Pinyin.toT9(letters)
        val with = nine(engine)
        val without = nine(spelledOnly(engine))
        for (end in 1..digits.length) {
            val typed = digits.substring(0, end)
            assertTrue("$letters $typed", PinyinCorrection.acceptable(typed))
            assertEquals("$letters $typed", emptyList<Cand>(), engine.candidatesWithInitials(typed, ""))
            with.type(digits.substring(end - 1, end))
            without.type(digits.substring(end - 1, end))
            assertEquals("$letters $typed", without.candidateWords(), with.candidateWords())
            assertEquals("$letters $typed", without.preeditForTest(), with.preeditForTest())
        }
        return digits.length
    }

    private fun assertOlderCandidatesFollow(engine: DictEngine, digits: String): List<String> {
        val spelled = engine.candidatesWithInitials(digits, "")
        val older = engine.candidatesCovered(digits, true)
        val shown = nine(engine).apply { type(digits) }.candidateWords()
        assertTrue("$digits $spelled", spelled.isNotEmpty())
        assertTrue("$digits $older", older.isNotEmpty())
        assertEquals(digits, CandidateEngine.withInitialWords(spelled, engine.initialWords(digits)).map { it.word }, shown)
        assertTrue("$digits ${older.map { it.word } - shown.toSet()}", shown.containsAll(older.map { it.word }))
        return shown
    }

    @Test fun every_typed_prefix_of_spelled_syllables_is_decoded_as_before() {
        assertEquals("942649436", T9Pinyin.toT9("xhangzhen"))
        for (model in listOf(false, true)) {
            val e = engine(grammar = if (model) grammar() else null)
            for (letters in listOf("jintian", "wo", "woni", "jintianwo", "xiangzhen", "nijintian")) {
                assertEveryTypedPrefixIsUntouched(e, letters)
            }
            val plain = nine(engine(null, if (model) grammar() else null))
            val c = nine(e)
            c.type("942649436")
            plain.type("942649436")
            assertEquals("$model", "乡镇", c.candidateWords().first())
            assertEquals("$model", plain.candidateWords().first(), c.candidateWords().first())
        }
    }

    @Test fun an_unfinished_last_syllable_keeps_the_initial_words_out_of_the_sentence() {
        val e = engine()
        for (digits in listOf("54684265", "546842687", "54684268", "5468")) {
            assertTrue(digits, PinyinCorrection.acceptable(digits))
            assertEquals(digits, emptyList<Cand>(), e.candidatesWithInitials(digits, ""))
            assertEquals(
                digits,
                nine(spelledOnly(e)).apply { type(digits) }.candidateWords(),
                nine(e).apply { type(digits) }.candidateWords(),
            )
        }
        assertEquals(listOf("jin", "ti", "cou"), T9Pinyin.segment("54684268"))
        assertFalse(nine(e).apply { type("546842687") }.candidateWords().contains("今天天气"))
    }

    @Test fun initials_alone_make_a_sentence_ahead_of_the_older_candidates() {
        assertFalse(PinyinCorrection.acceptable("5887"))
        val e = engine()
        assertEquals(Cand("今天天气", 4), e.candidatesWithInitials("5887", "").first())
        val shown = assertOlderCandidatesFollow(e, "5887")
        assertEquals("今天天气", shown.first())

        val digits = T9Pinyin.toT9("jttq") + T9Pinyin.toT9("wsm")
        assertFalse(PinyinCorrection.acceptable(digits))
        assertEquals(Cand("今天天气为什么", 7), e.candidatesWithInitials(digits, "").first())
        assertOlderCandidatesFollow(e, digits)
        assertEquals(emptyList<Cand>(), engine(null).candidatesWithInitials(digits, ""))
    }

    @Test fun spelled_syllables_and_initials_make_one_sentence() {
        val digits = T9Pinyin.toT9("jintian") + T9Pinyin.toT9("wsm")
        assertEquals("5468426976", digits)
        assertFalse(PinyinCorrection.acceptable(digits))
        for (model in listOf(false, true)) {
            val e = engine(grammar = if (model) grammar() else null)
            assertEquals("$model", Cand("今天为什么", 10), e.candidatesWithInitials(digits, "").first())
            assertFalse("$model", e.candidatesCovered(digits, true).any { it.word == "今天为什么" })
            assertOlderCandidatesFollow(e, digits)
        }

        val host = Host()
        val c = nine(engine(), host)
        c.type(digits)
        assertEquals("今天为什么", c.candidateWords().first())
        c.onPickCandidate(0)
        assertEquals(listOf("今天为什么"), host.commits)
        assertEquals("", c.rawComposingForTest())
    }

    @Test fun digits_no_sentence_covers_keep_the_older_candidates_alone() {
        val digits = "5877"
        assertFalse(PinyinCorrection.acceptable(digits))
        val e = engine(listOf(EngineFixture.Row("jt", "今天", 900), EngineFixture.Row("jtq", "鸡汤圈", 50)))
        val older = nine(spelledOnly(e)).apply { type(digits) }.candidateWords()
        assertEquals(listOf("鸡汤圈", "今天"), older.take(2))
        assertEquals(older, nine(e).apply { type(digits) }.candidateWords())
    }

    @Test fun a_forced_cut_a_locked_reading_and_a_literal_decode_as_before() {
        val e = engine()
        val quiet = spelledOnly(e)
        assertFalse(PinyinCorrection.acceptable("5887"))
        assertEquals(
            nine(quiet).apply { type("58'87") }.candidateWords(),
            nine(e).apply { type("58'87") }.candidateWords(),
        )

        fun lockFirst(c: KeyboardController): KeyboardController {
            c.type("5887")
            assertTrue("${c.expandedReadings()}", c.expandedReadings().isNotEmpty())
            c.onPickReadingIndex(0)
            return c
        }
        assertEquals(lockFirst(nine(quiet)).candidateWords(), lockFirst(nine(e)).candidateWords())

        fun literal(c: KeyboardController): KeyboardController {
            c.type("5887")
            c.onKey(Key("1", output = "1", preeditLiteral = true))
            assertEquals("58871", c.rawComposingForTest())
            return c
        }
        assertEquals(literal(nine(quiet)).candidateWords(), literal(nine(e)).candidateWords())
        assertFalse(literal(nine(e)).candidateWords().isEmpty())
    }

    @Test fun letters_keep_their_own_rule() {
        val e = engine()
        val plain = engine(null)
        for (letters in listOf("jintian", "wo", "woni", "jintiantq", "jttq")) {
            assertEquals(letters, emptyList<Cand>(), e.candidatesWithInitials(letters, ""))
            assertEquals(letters, DictEngine(EngineFixture.build(rows), null, null, initialsDict = EngineFixture.build(initials))
                .candidatesCovered(letters, false), e.candidatesCovered(letters, false))
        }
        assertTrue(plain.candidatesCovered("jintiantq", false).none { it.word == "今天天气" })
        assertEquals(Cand("今天天气", 9), e.candidatesCovered("jintiantq", false).first())
    }

    private val fullAssets = FullDictTestAssets.directory
    private fun fullEngine(): DictEngine {
        val files = FullDictTestAssets.productionNames.map { File(fullAssets, it) }
        assumeTrue(FullDictTestAssets.available(*files.toTypedArray()))
        return DictEngine(
            BinaryDict.fromFile(File(fullAssets, FullDictTestAssets.DICT)),
            BinaryDict.fromFile(File(fullAssets, FullDictTestAssets.T9)),
            CharBigramLM.fromFile(File(fullAssets, FullDictTestAssets.LM)),
            initialsDict = BinaryDict.fromFile(File(fullAssets, FullDictTestAssets.JIANPIN)),
        )
    }

    @Test fun full_dictionary_every_typed_prefix_of_spelled_sentences_is_decoded_as_before() {
        val e = fullEngine()
        var checked = 0
        for (letters in listOf(
            "nihao", "nihui", "xuexi", "ziji", "zhongguo", "jintiantianqi", "woxihuanni", "wobuzhidao", "womenyiqiqu",
            "xianzai", "pengyou", "shijian", "woaini", "haojiubujian", "nichilema", "duibuqi",
        )) {
            checked += assertEveryTypedPrefixIsUntouched(e, letters)
        }
        assertTrue("$checked", checked >= 100)
    }

    @Test fun full_dictionary_initials_make_sentences_ahead_of_the_older_candidates() {
        val e = fullEngine()
        for (digits in listOf("9999", "7777", "976", "5887", "9946", "9476444", "999999")) {
            assertFalse(digits, PinyinCorrection.acceptable(digits))
            assertEquals(digits, digits.length, e.candidatesWithInitials(digits, "").first().coveredLen)
            assertOlderCandidatesFollow(e, digits)
            assertEquals(digits, e.candidatesCovered(digits, true), e.candidatesCoveredWithSentences(digits, true, "", 4).first)
        }
        for ((full, tail) in listOf("jintian" to "wsm", "jintian" to "jttq")) {
            val digits = T9Pinyin.toT9(full) + T9Pinyin.toT9(tail)
            assertFalse(digits, PinyinCorrection.acceptable(digits))
            val head = e.candidatesCovered(T9Pinyin.toT9(full), true).first().word
            val spelled = e.candidatesWithInitials(digits, "")
            assertTrue(
                "$digits ${spelled.take(3)}",
                spelled.take(3).any {
                    it.coveredLen == digits.length && it.word.startsWith(head) && it.word.length == head.length + tail.length
                },
            )
            assertOlderCandidatesFollow(e, digits)
        }
    }
}
