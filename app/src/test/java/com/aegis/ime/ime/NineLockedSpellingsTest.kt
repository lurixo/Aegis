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

import com.aegis.ime.decoder.Cand
import com.aegis.ime.decoder.EngineFixture
import com.aegis.ime.decoder.FullDictTestAssets
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.LayoutId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NineLockedSpellingsTest {
    private class Host : ImeHost {
        override fun commitText(text: CharSequence) {}
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    private val rows = listOf(
        EngineFixture.Row("xi", "西", 500), EngineFixture.Row("xi", "系", 450),
        EngineFixture.Row("yi", "一", 900), EngineFixture.Row("an", "安", 450),
        EngineFixture.Row("an", "案", 400), EngineFixture.Row("ao", "奥", 100),
        EngineFixture.Row("bo", "波", 300), EngineFixture.Row("guo", "国", 700),
        EngineFixture.Row("xian", "西安", 900), EngineFixture.Row("xiao", "西奥", 200),
        EngineFixture.Row("yibo", "一波", 800), EngineFixture.Row("fang", "方", 500),
        EngineFixture.Row("fangan", "方案", 900),
    )

    private fun fixtureEngine() = DictEngine(
        EngineFixture.build(rows),
        EngineFixture.build(rows.map { it.copy(key = T9Pinyin.toT9(it.key)) }),
        null,
    )

    private fun controller(engine: CandidateEngine = fixtureEngine(), nine: Boolean = true) =
        KeyboardController(Host(), engine).apply {
            setCnDefaultLayout(if (nine) LayoutId.NINE else LayoutId.ALPHA)
            reset()
        }

    private val segment = Key("'", action = KeyAction.SEGMENT)
    private val backspace = Key("⌫", action = KeyAction.BACKSPACE)

    private fun KeyboardController.type(input: String) {
        for (ch in input) onKey(if (ch == '\'') segment else Key(ch.toString(), output = ch.toString()))
    }

    private fun KeyboardController.lock(reading: String) {
        val index = expandedReadings().indexOf(reading)
        assertTrue("the reading column offers $reading, was ${expandedReadings()}", index >= 0)
        onPickReadingIndex(index)
    }

    @Test fun digits_after_a_locked_reading_keep_every_spelling() {
        for (order in 0..1) {
            val c = controller()
            if (order == 0) { c.type("94"); c.lock("xi"); c.type("26") } else { c.type("9426"); c.lock("xi") }
            val words = c.candidateWords()
            assertEquals("order $order: $words", "西安", words.first())
            assertTrue("order $order: $words", "西奥" in words)
            assertFalse("order $order: $words", "一波" in words)
            assertEquals("xi'an", c.preeditForTest())
        }
        val other = controller()
        other.type("9426")
        other.lock("yi")
        assertEquals("一波", other.candidateWords().first())
        assertFalse("西安" in other.candidateWords())
        assertEquals("yi'bo", other.preeditForTest())

        val longer = controller()
        longer.type("3264")
        longer.lock("fang")
        longer.type("26")
        assertEquals("方案", longer.candidateWords().first())
        assertEquals("fang'an", longer.preeditForTest())
    }

    @Test fun every_locked_syllable_keeps_its_reading() {
        val c = controller()
        c.type("9426486")
        c.lock("xi")
        assertEquals("西安国", c.candidateWords().first())
        assertEquals("xi'an'guo", c.preeditForTest())
        c.lock("ao")
        val words = c.candidateWords()
        assertEquals("$words", "西奥国", words.first())
        assertTrue("$words", "西奥" in words)
        assertFalse("$words", words.any { it.startsWith("西安") })
        assertEquals("xi'ao'guo", c.preeditForTest())

        val an = controller()
        an.type("9426486")
        an.lock("xi")
        an.lock("an")
        assertEquals("西安国", an.candidateWords().first())
        assertFalse(an.candidateWords().any { it.startsWith("西奥") })
        assertEquals("xi'an'guo", an.preeditForTest())
    }

    @Test fun separators_digits_typed_later_and_backspace_keep_the_lock() {
        val c = controller()
        c.type("94")
        c.lock("xi")
        c.type("26'486")
        assertEquals("西安国", c.candidateWords().first())
        assertEquals("xi'an'guo", c.preeditForTest())

        repeat(3) { c.onKey(backspace) }
        c.onKey(backspace)
        assertEquals("9426", c.rawComposingForTest())
        assertEquals("西安", c.candidateWords().first())
        assertEquals("xi'an", c.preeditForTest())

        c.type("5")
        val open = c.candidateWords()
        assertEquals("$open", "西安", open.first())
        assertTrue("$open", "西奥" in open)
        assertEquals("xi'an'j", c.preeditForTest())

        repeat(3) { c.onKey(backspace) }
        assertEquals("94", c.rawComposingForTest())
        assertEquals("xi", c.preeditForTest())
        assertEquals(listOf("西", "系"), c.candidateWords())

        c.onKey(backspace)
        val fresh = controller()
        fresh.type("94")
        assertEquals(fresh.preeditForTest(), c.preeditForTest())
        assertEquals(fresh.candidateWords(), c.candidateWords())
    }

    @Test fun digits_that_do_not_reach_past_the_lock_use_the_letter_reading() {
        val real = fixtureEngine()
        var digitCalls = 0
        var letterCalls = 0
        val engine = object : CandidateEngine by real {
            override fun candidatesForLockedDigitsCovered(
                digits: String, readings: List<String>, cuts: Set<Int>, context: CharSequence,
            ): List<Cand> { digitCalls++; return real.candidatesForLockedDigitsCovered(digits, readings, cuts, context) }
            override fun candidatesForLockedReadingCovered(letters: String, cuts: Set<Int>, context: CharSequence): List<Cand> {
                letterCalls++
                return real.candidatesForLockedReadingCovered(letters, cuts, context)
            }
        }
        val c = controller(engine)
        c.type("94")
        c.lock("xi")
        assertTrue(letterCalls > 0)
        assertEquals(listOf("西", "系"), c.candidateWords())
        c.type("9")
        assertEquals("xi'w", c.preeditForTest())
        assertEquals(listOf("西", "系"), c.candidateWords())
        assertTrue(digitCalls > 0)
    }

    @Test fun letters_on_26_keys_never_take_the_digit_path() {
        val real = fixtureEngine()
        var digitCalls = 0
        val engine = object : CandidateEngine by real {
            override fun candidatesForLockedDigitsCovered(
                digits: String, readings: List<String>, cuts: Set<Int>, context: CharSequence,
            ): List<Cand> { digitCalls++; return real.candidatesForLockedDigitsCovered(digits, readings, cuts, context) }
        }
        val c = controller(engine, nine = false)
        c.type("xi")
        c.lock("xi")
        c.type("ao")
        val words = c.candidateWords()
        val decoded = real.candidatesForLockedReadingCovered("xiao", setOf(2), "").map { it.word }
        assertEquals(decoded, words.filter { it in decoded })
        assertEquals("西奥", words.first())
        assertFalse("西安" in words)
        assertEquals("xi'ao", c.preeditForTest())
        assertEquals(0, digitCalls)
    }

    @Test fun the_engine_rejects_locks_that_do_not_spell_the_digits() {
        val e = fixtureEngine()
        val hits = e.candidatesForLockedDigitsCovered("9426", listOf("xi"), setOf(2), "")
        assertEquals(Cand("西安", 4), hits.first())
        assertTrue(hits.all { it.word.startsWith("西") || it.word.startsWith("系") })
        assertTrue(e.candidatesForLockedDigitsCovered("9426", listOf("xi", "an"), setOf(2), "").none { it.word == "西奥" })
        assertTrue(e.candidatesForLockedDigitsCovered("9426", listOf("fa"), setOf(2), "").isEmpty())
        assertTrue(e.candidatesForLockedDigitsCovered("9426", listOf("x"), setOf(1), "").isEmpty())
        assertTrue(e.candidatesForLockedDigitsCovered("9426", emptyList(), emptySet(), "").isEmpty())
        assertTrue(e.candidatesForLockedDigitsCovered("xian", listOf("xi"), setOf(2), "").isEmpty())
    }

    private val fullAssets = FullDictTestAssets.directory

    @Test fun full_dictionary_locked_readings_rank_every_spelling_of_the_other_digits() {
        val files = FullDictTestAssets.productionNames.map { File(fullAssets, it) }
        assumeTrue(FullDictTestAssets.available(*files.toTypedArray()))
        val letters = BinaryDict.fromFile(File(fullAssets, FullDictTestAssets.DICT))
        val engine = DictEngine(
            letters,
            BinaryDict.fromFile(File(fullAssets, FullDictTestAssets.T9)),
            CharBigramLM.fromFile(File(fullAssets, FullDictTestAssets.LM)),
            initialsDict = BinaryDict.fromFile(File(fullAssets, FullDictTestAssets.JIANPIN)),
        )
        data class Case(val first: String, val lock: String, val rest: String, val top: String, val label: String, val kept: String)
        val cases = listOf(
            Case("94", "xi", "26", "西安", "xi'an", "西奥"),
            Case("3264", "fang", "26", "方案", "fang'an", "方波"),
            Case("744", "shi", "543", "世界", "shi'jie", "师姐"),
            Case("64", "ni", "636", "你们", "ni'men", "你"),
            Case("94", "xi", "26'543", "西安街", "xi'an'jie", "西安"),
        )
        for (case in cases) {
            val c = controller(engine)
            c.type(case.first)
            c.lock(case.lock)
            c.type(case.rest)
            val words = c.candidateWords()
            assertEquals("$case: ${words.take(12)}", case.top, words.first())
            assertTrue("$case: ${words.take(40)}", case.kept in words)
            assertEquals("$case", case.label, c.preeditForTest())
            val heads = letters.exact(case.lock).mapTo(HashSet()) { it.word }
            for (word in words) {
                if (!Character.isIdeographic(word.codePointAt(0))) continue
                val head = String(Character.toChars(word.codePointAt(0)))
                assertTrue("$case: $word starts with a character read ${case.lock}", head in heads)
            }
        }
        val two = controller(engine)
        two.type("9426486")
        two.lock("xi")
        two.lock("an")
        assertTrue("${two.candidateWords().take(12)}", two.candidateWords().first().startsWith("西安"))
        assertTrue(two.preeditForTest().startsWith("xi'an'"))

        val alpha = controller(engine, nine = false)
        alpha.type("xi")
        alpha.lock("xi")
        alpha.type("ao")
        val decoded = engine.candidatesForLockedReadingCovered("xiao", setOf(2), "").map { it.word }
        assertEquals(decoded, alpha.candidateWords().filter { it in decoded })
        assertEquals("xi'ao", alpha.preeditForTest())
    }
}
