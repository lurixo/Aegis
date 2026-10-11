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
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.ime.ImeHost
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.LayoutId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NineInitialsFallbackTest {
    private class Host : ImeHost {
        val commits = ArrayList<String>()
        override fun commitText(text: CharSequence) { commits.add(text.toString()) }
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    private val rows = listOf(
        EngineFixture.Row("wo", "我", 900), EngineFixture.Row("wang", "往", 500),
        EngineFixture.Row("xue", "学", 600), EngineFixture.Row("xi", "习", 400),
        EngineFixture.Row("zai", "在", 800), EngineFixture.Row("yi", "一", 300),
        EngineFixture.Row("wangwang", "往往", 700), EngineFixture.Row("xuexi", "学习", 950),
    )

    private val initials = listOf(
        EngineFixture.Row("ww", "往往", 700), EngineFixture.Row("xx", "学习", 950),
        EngineFixture.Row("xz", "现在", 990), EngineFixture.Row("wwww", "呜呜呜呜", 50),
        EngineFixture.Row("xxxx", "嘻嘻嘻嘻", 80),
    )

    private fun engine(withInitials: Boolean = true) = DictEngine(
        EngineFixture.build(rows),
        EngineFixture.build(rows.map { it.copy(key = T9Pinyin.toT9(it.key)) }),
        null,
        initialsDict = if (withInitials) EngineFixture.build(initials) else null,
    )

    private fun nine(engine: DictEngine, host: Host = Host()) = KeyboardController(host, engine).apply {
        setCnDefaultLayout(LayoutId.NINE)
        reset()
    }

    private fun KeyboardController.type(digits: String) {
        for (digit in digits) onKey(Key(digit.toString(), output = digit.toString()))
    }

    @Test fun digits_that_spell_no_syllable_are_read_as_initials_of_every_letter() {
        val out = engine().candidatesCovered("9999", true)
        assertEquals(
            listOf(Cand("嘻嘻嘻嘻", 4), Cand("呜呜呜呜", 4), Cand("现在", 2), Cand("学习", 2), Cand("往往", 2)),
            out.take(5),
        )
        val single = out.drop(5)
        assertTrue("the first digit alone closes the list: $single", single.isNotEmpty() && single.all { it.coveredLen == 1 })
        assertTrue("$single", single.map { it.word }.containsAll(listOf("在", "我", "学", "一")))
        assertEquals(out.size, out.map { it.word }.toSet().size)
    }

    @Test fun the_sentence_decode_and_forced_cuts_get_the_same_reading() {
        val e = engine()
        val plain = e.candidatesCovered("9999", true)
        assertEquals(plain, e.candidatesCoveredWithSentences("9999", true, "", 4).first)
        assertEquals(plain, e.candidatesCovered("9999", true, setOf(2), ""))
    }

    @Test fun digits_with_a_syllable_reading_and_letters_are_left_alone() {
        val with = engine()
        val without = engine(withInitials = false)
        for (digits in listOf("9", "96", "9264", "98394", "969264", "99", "999")) {
            val expected = without.candidatesCovered(digits, true)
            if (expected.isEmpty()) continue
            assertEquals(digits, expected, with.candidatesCovered(digits, true))
        }
        assertTrue(with.candidatesCovered("96", true).isNotEmpty())
        assertTrue(with.candidatesCovered("9999", false).isEmpty())
    }

    @Test fun nothing_is_added_without_the_initials_dictionary() {
        assertTrue(engine(withInitials = false).candidatesCovered("9999", true).isEmpty())
    }

    @Test fun picking_an_initial_word_keeps_the_rest_of_the_digits_composing() {
        val c = nine(engine())
        c.type("9999")
        assertEquals("嘻嘻嘻嘻", c.candidateWords().first())
        c.onPickCandidate(c.candidateWords().indexOf("学习"))
        assertEquals("学习", c.composingPrefix())
        assertEquals("99", c.rawComposingForTest())
        assertTrue(c.candidateWords().isNotEmpty())

        val whole = Host()
        val d = nine(engine(), whole)
        d.type("9999")
        d.onPickCandidate(0)
        assertEquals("", d.rawComposingForTest())
        assertEquals(listOf("嘻嘻嘻嘻"), whole.commits)
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

    @Test fun full_dictionary_every_digit_string_has_candidates_on_nine_keys() {
        val c = nine(fullEngine())
        val empty = ArrayList<String>()
        var checked = 0
        fun check(digits: String) {
            c.type(digits)
            checked++
            if (c.candidateWords().isEmpty()) empty.add(digits)
            c.onPanelClear()
        }
        fun all(prefix: String, depth: Int) {
            if (prefix.isNotEmpty()) check(prefix)
            if (depth > 0) for (digit in '2'..'9') all(prefix + digit, depth - 1)
        }
        all("", 4)
        for (digit in '2'..'9') for (length in 5..12) check(digit.toString().repeat(length))
        for (letters in listOf("wxyz", "jqxzcs", "ghjklpqrstwxyz", "zgrmghg", "nhwsxzdy")) check(T9Pinyin.toT9(letters))
        assertEquals(4680 + 64 + 5, checked)
        assertTrue("digit strings without candidates: $empty", empty.isEmpty())
    }

    @Test fun full_dictionary_initial_digits_offer_what_the_letters_offer() {
        val e = fullEngine()
        val nineWords = e.candidatesCovered("9999", true).filter { it.coveredLen == 4 }.map { it.word }
        assertTrue("$nineWords", nineWords.size >= 20)
        val letterWords = HashSet<String>()
        for (letters in T9Pinyin.initialSpellings("9999")) {
            e.candidatesCovered(letters, false).filter { it.coveredLen == 4 }.mapTo(letterWords) { it.word }
        }
        assertTrue("${nineWords - letterWords}", letterWords.containsAll(nineWords))

        val c = nine(e)
        c.type("644269999")
        c.onPickCandidate(c.candidateWords().indexOf("你好"))
        assertEquals("9999", c.rawComposingForTest())
        assertEquals(nineWords.first(), c.candidateWords().first())
        assertFalse(c.candidateWords().isEmpty())
    }
}
