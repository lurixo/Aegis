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
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InitialReadingLockCandidatesTest {

    private class RecordingHost : ImeHost {
        val commits = mutableListOf<String>()
        val text = StringBuilder()
        override fun commitText(text: CharSequence) { commits.add(text.toString()); this.text.append(text) }
        override fun deleteBackward() { if (text.isNotEmpty()) text.setLength(text.length - 1) }
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = text.substring(maxOf(0, text.length - n))
    }

    private class Board(val host: RecordingHost, val c: KeyboardController, val um: UserModel, val learning: UserLearning)

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)

    private val dict: BinaryDict by lazy { BinaryDict.fromFile(dictFile) }
    private val t9: BinaryDict by lazy { BinaryDict.fromFile(t9File) }
    private val lm: CharBigramLM by lazy { CharBigramLM.fromFile(lmFile) }
    private val jianpin: BinaryDict by lazy { BinaryDict.fromFile(jianpinFile) }

    private fun board(nine: Boolean): Board {
        assumeTrue(
            "production dictionary, T9 table, language model and jianpin table present",
            FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile),
        )
        val um = UserModel()
        val learning = UserLearning()
        val host = RecordingHost()
        val engine = DictEngine(dict, t9, lm, um, emptySet(), jianpin, null, learning)
        val c = KeyboardController(host, engine).apply { userLearning = learning }
        c.switchTextLayoutForTest(nine)
        return Board(host, c, um, learning)
    }

    private fun layout(nine: Boolean) = if (nine) "9-key" else "26-key"

    private fun type(b: Board, keys: String) = keys.forEach { b.c.onKey(Key(it.toString(), output = it.toString())) }

    private fun lock(b: Board, reading: String) {
        val readings = b.c.expandedReadings()
        val index = readings.indexOf(reading)
        assertTrue("$reading is offered in $readings", index >= 0)
        b.c.onPickReadingIndex(index)
    }

    private fun pick(b: Board, word: String) {
        val words = b.c.candidateWords()
        val index = words.indexOf(word)
        assertTrue("$word is offered, was ${words.take(16)}", index >= 0)
        b.c.onPickCandidate(index)
    }

    private fun isSingle(word: String) = word.codePointCount(0, word.length) == 1

    private fun assertOnlyWholeReadingsLearned(b: Board, label: String) {
        for ((reading, words) in b.um.readingSnapshot()) {
            assertTrue("$label: user word reading $reading for $words spells whole syllables", T9Pinyin.segmentLetters(reading) != null)
        }
        for ((reading, words) in b.learning.readingSnapshot()) {
            assertTrue("$label: formed word reading $reading for $words spells whole syllables", T9Pinyin.segmentLetters(reading) != null)
        }
    }

    @Test fun lockingTheInitialOfAWordSpellsItAndCommitsItOnBothLayouts() {
        for (nine in listOf(true, false)) {
            val b = board(nine)
            type(b, if (nine) "594" else "jyi")
            lock(b, "j")
            assertEquals("${layout(nine)}: the lock keeps the rest", "j'yi", b.c.preeditForTest())
            val words = b.c.candidateWords()
            assertTrue("${layout(nine)}: 记忆 offered, was ${words.take(16)}", "记忆" in words)
            assertTrue("${layout(nine)}: j singles follow, was ${words.take(24)}", "就" in words)
            pick(b, "记忆")
            assertEquals("${layout(nine)}", listOf("记忆"), b.host.commits)
            assertEquals("${layout(nine)}", "", b.c.preeditForTest())
            assertEquals("${layout(nine)}", "", b.c.rawComposingForTest())
            assertTrue(
                "${layout(nine)}: 记忆 is filed only under jiyi, was ${b.um.readingSnapshot()}",
                b.um.readingSnapshot().filterValues { "记忆" in it }.keys.all { it == "jiyi" },
            )
            assertOnlyWholeReadingsLearned(b, layout(nine))
        }
    }

    @Test fun aLockedInitialAloneOffersCommittableCharactersOnBothLayouts() {
        for (nine in listOf(true, false)) {
            val b = board(nine)
            type(b, if (nine) "5" else "j")
            lock(b, "j")
            val words = b.c.candidateWords()
            assertTrue("${layout(nine)}: j alone offers characters", words.isNotEmpty())
            assertTrue("${layout(nine)}: j alone offers single characters, was ${words.take(8)}", words.all(::isSingle))
            val first = words.first()
            b.c.onPickCandidate(0)
            assertEquals("${layout(nine)}", listOf(first), b.host.commits)
            assertEquals("${layout(nine)}", "", b.c.preeditForTest())
            assertOnlyWholeReadingsLearned(b, layout(nine))
        }
    }

    @Test fun otherInitialsOfferTheirAlignedWordsOnBothLayouts() {
        data class Case(val digits: String, val letters: String, val initial: String, val word: String)
        val cases = listOf(
            Case("298", "byu", "b", "比喻"),
            Case("994", "wyi", "w", "唯一"),
            Case("994", "zyi", "z", "注意"),
            Case("994", "zyi", "z", "之一"),
        )
        for (nine in listOf(true, false)) for (case in cases) {
            val b = board(nine)
            type(b, if (nine) case.digits else case.letters)
            lock(b, case.initial)
            pick(b, case.word)
            assertEquals("${layout(nine)} ${case.initial}", listOf(case.word), b.host.commits)
            assertEquals("${layout(nine)} ${case.initial}", "", b.c.preeditForTest())
        }
    }

    @Test fun anInitialLockedAfterASyllableSpellsTheWordOnBothLayouts() {
        for (nine in listOf(true, false)) {
            val b = board(nine)
            type(b, if (nine) "644" else "nih")
            lock(b, "ni")
            lock(b, "h")
            assertEquals("${layout(nine)}", "ni'h", b.c.preeditForTest())
            val words = b.c.candidateWords()
            assertTrue("${layout(nine)}: 你会 offered, was ${words.take(16)}", "你会" in words)
            pick(b, "你好")
            assertEquals("${layout(nine)}", listOf("你好"), b.host.commits)
            assertEquals("${layout(nine)}", "", b.c.preeditForTest())
            assertOnlyWholeReadingsLearned(b, layout(nine))
        }
    }

    @Test fun drillingTheLockedInitialOffersItsOwnCharactersOnBothLayouts() {
        for (nine in listOf(true, false)) {
            val b = board(nine)
            type(b, if (nine) "594" else "jyi")
            lock(b, "j")
            lock(b, "j")
            assertEquals("${layout(nine)}: the tapped lock is drilled", 0, b.c.drilledSyllableForTest())
            assertEquals("${layout(nine)}", "j", b.c.lockedHighlightReading())
            val words = b.c.candidateWords()
            assertTrue("${layout(nine)}: the drill offers j characters, was ${words.take(12)}", "就" in words && "一" !in words)
            pick(b, "就")
            assertTrue("${layout(nine)}: a drilled character stays in the preedit", b.host.commits.isEmpty())
            assertEquals("${layout(nine)}", "就", b.c.composingPrefix())
            pick(b, "一")
            assertEquals("${layout(nine)}", listOf("就一"), b.host.commits)
            assertTrue(
                "${layout(nine)}: 就一 is filed only under jiuyi, was ${b.um.readingSnapshot()}",
                b.um.readingSnapshot().filterValues { "就一" in it }.keys.all { it == "jiuyi" },
            )
            assertOnlyWholeReadingsLearned(b, layout(nine))
        }
    }

    @Test fun aBrokenRestAfterALockedInitialStillOffersCandidatesOnBothLayouts() {
        for (nine in listOf(true, false)) {
            val b = board(nine)
            type(b, if (nine) "5494" else "jiyi")
            lock(b, "j")
            val words = b.c.candidateWords()
            assertTrue("${layout(nine)}: candidates after locking j, preedit ${b.c.preeditForTest()}", words.isNotEmpty())
            assertTrue("${layout(nine)}: the locked j keeps its characters, was ${words.take(16)}", "就" in words)
        }
    }

    @Test fun theReadingColumnNeverOffersLettersThatStartNoSyllable() {
        val nine = board(nine = true)
        type(nine, "8")
        assertEquals("9-key 8 offers only t", listOf("t"), nine.c.expandedReadings())
        nine.c.onKey(Key("", action = KeyAction.BACKSPACE))
        type(nine, "4")
        assertEquals("9-key 4 offers g and h", listOf("g", "h"), nine.c.expandedReadings())
        for (letter in listOf("i", "u", "v")) {
            val alpha = board(nine = false)
            type(alpha, letter)
            assertTrue("26-key $letter is not offered, was ${alpha.c.expandedReadings()}", letter !in alpha.c.expandedReadings())
        }
    }
}
