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

import com.aegis.ime.decoder.FullDictTestAssets
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LockedSyllableUserWordTest {

    private class Host : ImeHost {
        val sb = StringBuilder()
        override fun commitText(text: CharSequence) { sb.append(text) }
        override fun deleteBackward() { if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1) }
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = sb.takeLast(n)
    }

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)

    private fun assets() = assumeTrue(
        "production dictionary, T9 table, language model and jianpin table present",
        FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile),
    )

    private fun engine(um: UserModel) = DictEngine(
        BinaryDict.fromFile(dictFile),
        BinaryDict.fromFile(t9File),
        CharBigramLM.fromFile(lmFile),
        um,
        emptySet(),
        BinaryDict.fromFile(jianpinFile),
        null,
        UserLearning(),
    )

    private fun controller(um: UserModel, nine: Boolean): KeyboardController =
        KeyboardController(Host(), engine(um)).apply {
            setCnDefaultLayout(if (nine) LayoutId.NINE else LayoutId.ALPHA)
            reset()
        }

    private fun type(c: KeyboardController, reading: String, nine: Boolean) {
        val keys = if (nine) T9Pinyin.toT9(reading) else reading
        keys.forEach { c.onKey(Key(it.toString(), output = it.toString())) }
    }

    private fun lock(c: KeyboardController, reading: String) {
        val index = c.expandedReadings().indexOf(reading)
        assertTrue("$reading is offered in ${c.expandedReadings()}", index >= 0)
        c.onPickReadingIndex(index)
    }

    private fun lockedWords(um: UserModel, reading: String, nine: Boolean): List<String> {
        val c = controller(um, nine)
        type(c, reading, nine)
        lock(c, reading)
        return c.candidateWords()
    }

    private fun layout(nine: Boolean) = if (nine) "9-key" else "26-key"

    private fun multiCharacter(words: List<String>) = words.filter { it.codePointCount(0, it.length) > 1 }

    @Test fun pickingACompletionNeverFilesItUnderTheTypedSyllable() {
        assets()
        for (nine in listOf(true, false)) {
            val um = UserModel()
            val c = controller(um, nine)
            type(c, "jiu", nine)
            val at = c.candidateWords().indexOf("就是")
            assertTrue("${layout(nine)}: unlocked jiu completes to 就是, was ${c.candidateWords().take(12)}", at >= 0)
            c.onPickCandidate(at)
            assertTrue(
                "${layout(nine)}: 就是 is not stored under jiu, was ${um.readingSnapshot()}",
                um.readingSnapshot().values.none { "就是" in it },
            )
            type(c, "jiu", nine)
            lock(c, "jiu")
            assertEquals("${layout(nine)}: locked jiu offers single characters only", emptyList<String>(), multiCharacter(c.candidateWords()))
        }
    }

    @Test fun completionsStoredByEarlierVersionsStayOutOfTheLockedSyllable() {
        assets()
        val stored = listOf("jiu" to "就是", "yi" to "一个", "yi" to "一下", "yi" to "已经", "tian" to "天气")
        for (nine in listOf(true, false)) {
            val um = UserModel().apply { stored.forEach { (r, w) -> recordWord(r, w, 1L, incrementCount = true) } }
            for (reading in listOf("jiu", "yi", "tian")) {
                val got = lockedWords(um, reading, nine)
                assertTrue("${layout(nine)}: locked $reading still offers characters, was ${got.take(8)}", got.isNotEmpty())
                assertEquals("${layout(nine)}: locked $reading, was ${got.take(8)}", emptyList<String>(), multiCharacter(got))
            }
        }
    }

    @Test fun aWordAddedByHandStillLeadsItsLockedSyllable() {
        assets()
        for (nine in listOf(true, false)) {
            val um = UserModel().apply { addManualWord("yi", "一个", 1L) }
            val got = lockedWords(um, "yi", nine)
            assertTrue("${layout(nine)}: 一个 was added by hand under yi, was ${got.take(8)}", "一个" in got)
        }
    }

    @Test fun anAssembledWordIsStillLearnedUnderItsFullReading() {
        assets()
        val um = UserModel()
        val c = controller(um, nine = false)
        type(c, "jiujiu", nine = false)
        lock(c, "jiu")
        val first = c.candidateWords().indexOf("旧")
        assertTrue("旧 offered for the first locked syllable, was ${c.candidateWords().take(12)}", first >= 0)
        c.onPickCandidate(first)
        val second = c.candidateWords().indexOf("酒")
        assertTrue("酒 offered for the remaining syllable, was ${c.candidateWords().take(12)}", second >= 0)
        c.onPickCandidate(second)
        assertTrue("旧酒 is stored under jiujiu, was ${um.readingSnapshot()}", "旧酒" in um.readingSnapshot()["jiujiu"].orEmpty())
    }

    @Test fun storedReadingsAreSpelledFromTheWordItself() {
        assets()
        val e = engine(UserModel())
        assertEquals("", e.spelledReading("就是", "jiu"))
        assertEquals("", e.spelledReading("天气", "tian"))
        assertEquals("jiushi", e.spelledReading("就是", "jiushi"))
        assertEquals("xian", e.spelledReading("西安", "xian"))
        assertEquals("the 9-key letter guess is replaced by the spelling with the same keys", "pianmian", e.spelledReading("片面", "qianmian"))
    }

    @Test fun controllerLearnsOnlyWhatTheEngineCanSpell() {
        for (spells in listOf(false, true)) {
            val learned = ArrayList<Pair<String, String>>()
            val fake = object : com.aegis.ime.engine.CandidateEngine {
                override fun candidates(composing: String, t9: Boolean): List<String> = listOf("就是")
                override fun spelledReading(word: String, reading: String): String = if (spells) "jiushi" else ""
                override fun learnWord(reading: String, word: String, assembled: Boolean) { learned.add(reading to word) }
            }
            val host = Host()
            val c = KeyboardController(host, fake).apply { setCnDefaultLayout(LayoutId.ALPHA); reset() }
            "jiu".forEach { c.onKey(Key(it.toString(), output = it.toString())) }
            c.onKey(Key(" ", action = KeyAction.SPACE))
            assertEquals("就是", host.sb.toString())
            assertEquals(if (spells) listOf("jiushi" to "就是") else emptyList(), learned)
        }
    }
}
