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

import com.aegis.ime.decoder.EngineFixture
import com.aegis.ime.decoder.Cand
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.dict.OctagramReader
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LongUserWordRecallTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ctx = RuntimeEnvironment.getApplication()

    private class EditorHost : ImeHost {
        val sb = StringBuilder()
        override fun commitText(text: CharSequence) { sb.append(text) }
        override fun deleteBackward() { if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1) }
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = sb.takeLast(n)
    }

    private class Shipped(
        val letters: BinaryDict,
        val digits: BinaryDict,
        val initials: BinaryDict,
        val lm: CharBigramLM,
        val grammar: OctagramReader,
    )

    private class Shown(val source: List<String>, val rendered: List<String>)

    private val pool = listOf(
        "和" to "he", "都" to "du", "海" to "hai", "会" to "hui", "长" to "zhang", "中" to "zhong", "性" to "xing", "了" to "le",
        "的" to "de", "这" to "zhe", "品" to "pin", "类" to "lei", "存" to "cun", "化" to "hua", "脱" to "tuo", "为" to "wei",
        "秒" to "miao", "桥" to "qiao", "灯" to "deng", "纸" to "zhi", "雪" to "xue", "窗" to "chuang", "墙" to "qiang", "棉" to "mian",
        "锁" to "suo", "塔" to "ta", "桶" to "tong", "梯" to "ti", "瓶" to "ping", "帽" to "mao", "鞋" to "xie", "伞" to "san",
    )
    private val addable = listOf(2, 3, 4, 5, 6, 7, 8, 10, 12, 16, 24, 32)
    private val formable = listOf(6, 7, 8, 10, 12, 16)

    private fun word(n: Int) = pool.take(n).joinToString("") { it.first }
    private fun reading(n: Int) = pool.take(n).joinToString("") { it.second }
    private fun keys(reading: String, nine: Boolean) = if (nine) T9Pinyin.toT9(reading) else reading

    private fun shipped(): Shipped {
        cached?.let { return it }
        val dir = System.getenv("AEGIS_FULLDICT_DIR")
        val grammar = System.getenv("AEGIS_GRAM")
        assumeTrue(
            "full-dict check only when AEGIS_FULLDICT_DIR and AEGIS_GRAM are set",
            !dir.isNullOrEmpty() && !grammar.isNullOrEmpty() && File(grammar).exists(),
        )
        return Shipped(
            BinaryDict.fromFile(File(dir, "aegis_dict.bin")),
            BinaryDict.fromFile(File(dir, "aegis_t9.bin")),
            BinaryDict.fromFile(File(dir, "aegis_jianpin.bin")),
            CharBigramLM.fromFile(File(dir, "aegis_lm.bin")),
            OctagramReader.fromFile(File(grammar)),
        ).also { cached = it }
    }

    private fun shown(engine: CandidateEngine, nine: Boolean, typed: String, before: (KeyboardController) -> Unit = {}): Shown {
        val view = InputView(ctx)
        val c = KeyboardController(EditorHost(), engine)
        c.attachView(view)
        c.switchTextLayoutForTest(nine)
        before(c)
        typed.forEach { c.onKey(Key(it.toString(), output = it.toString())) }
        view.showExpandedCandidates()
        return Shown(c.candidateWords(), view.expandedGridForTest().renderedCandidateTextsForTest())
    }

    private fun storedWordsAreShown(
        nine: Boolean,
        lengths: List<Int>,
        store: (KeyboardController, UserModel, UserLearning, Int) -> Unit,
    ) {
        val s = shipped()
        for (n in lengths) {
            val model = UserModel()
            val learning = UserLearning()
            val engine = DictEngine(
                s.letters, s.digits, s.lm, userModel = model, initialsDict = s.initials, octagram = s.grammar, userLearning = learning,
            )
            val shown = shown(engine, nine, keys(reading(n), nine)) { store(it, model, learning, n) }
            val label = "${word(n)} on the ${if (nine) "9-key" else "26-key"} layout"
            assertTrue("$label is a candidate for its whole reading", word(n) in shown.source)
            assertTrue("$label is shown among the expanded candidates", word(n) in shown.rendered)
        }
    }

    private fun addedWordsAreShown(nine: Boolean) = storedWordsAreShown(nine, addable) { c, model, _, n ->
        var outcome: WordAddOutcome? = null
        c.addWordReading(word(n), reading(n), { r, w -> model.addManualWord(r, w, 1L) }) { outcome = it }
        assertEquals("${word(n)} is not a dictionary word", WordAddOutcome.ADDED, outcome)
    }

    private fun assembledWordsAreShown(nine: Boolean) = storedWordsAreShown(nine, addable) { _, model, _, n ->
        model.recordWord(reading(n), word(n), 1L, true)
        assertEquals(listOf(word(n)), model.readingSnapshot()[reading(n)])
    }

    private fun formedWordsAreShown(nine: Boolean) = storedWordsAreShown(nine, formable) { _, _, learning, n ->
        val file = tmp.newFile()
        file.writeText("aegis-userlearn 1\nF\t${reading(n)}\t${word(n)}\t3.0\t${System.currentTimeMillis()}\n")
        learning.load(file)
        assertEquals(listOf(UserLearning.Formed(word(n), reading(n))), learning.formedEntries())
    }

    @Test fun added_words_of_every_accepted_length_are_shown_for_their_reading_on26key() = addedWordsAreShown(nine = false)

    @Test fun added_words_of_every_accepted_length_are_shown_for_their_reading_on9key() = addedWordsAreShown(nine = true)

    @Test fun assembled_words_are_shown_for_their_reading_on26key() = assembledWordsAreShown(nine = false)

    @Test fun assembled_words_are_shown_for_their_reading_on9key() = assembledWordsAreShown(nine = true)

    @Test fun words_formed_from_single_characters_are_shown_for_their_reading_on26key() = formedWordsAreShown(nine = false)

    @Test fun words_formed_from_single_characters_are_shown_for_their_reading_on9key() = formedWordsAreShown(nine = true)

    private class Crowded(private val stored: Boolean) : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = candidatesCovered(composing, t9).map { it.word }

        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
            if (composing.isEmpty()) {
                emptyList()
            } else {
                AHEAD.map { Cand(it, composing.length) } + Cand(WORD, composing.length) +
                    BEHIND.map { Cand(it, composing.length) } + SINGLES.map { Cand(it, 2) }
            }

        override fun storedWords(composing: String, t9: Boolean): List<String> = if (stored) listOf(WORD) else emptyList()
    }

    private fun wordBehindThreeRowsIsShownOnlyWhenStored(nine: Boolean) {
        val typed = keys("heduhaihuizhangzhongxingledezhe", nine)
        val plain = shown(Crowded(stored = false), nine, typed)
        val kept = shown(Crowded(stored = true), nine, typed)
        assertEquals("the candidates themselves do not depend on the stored words", plain.source, kept.source)
        assertTrue("the word is offered", WORD in plain.source)
        assertFalse("a phrase behind the third row is left out", WORD in plain.rendered)
        val phrases = plain.rendered.filter { GraphemeText.clusterCount(it) > 1 }
        assertEquals("the stored word follows the three rows of phrases", phrases + WORD, kept.rendered.take(phrases.size + 1))
        assertEquals("only the stored word is added", plain.rendered, kept.rendered - WORD)
    }

    @Test fun stored_word_behind_the_third_phrase_row_is_shown_on26key() = wordBehindThreeRowsIsShownOnlyWhenStored(nine = false)

    @Test fun stored_word_behind_the_third_phrase_row_is_shown_on9key() = wordBehindThreeRowsIsShownOnlyWhenStored(nine = true)

    @Test fun engine_lists_the_words_stored_under_typed_keys() {
        val rows = listOf(
            EngineFixture.Row("ci", "此", 850), EngineFixture.Row("shi", "是", 950), EngineFixture.Row("ku", "库", 900),
        )
        val model = UserModel().apply {
            recordWord("cishi", "此是", 1L, true)
            addManualWord("cishiku", "此是库", 1L)
        }
        val engine = DictEngine(
            EngineFixture.build(rows),
            EngineFixture.build(rows.map { EngineFixture.Row(T9Pinyin.toT9(it.key), it.word, it.freq) }),
            null,
            model,
        )
        assertEquals(listOf("此是"), engine.storedWords("cishi", t9 = false))
        assertEquals(listOf("此是"), engine.storedWords("ci'shi", t9 = false))
        assertEquals(listOf("此是"), engine.storedWords(T9Pinyin.toT9("cishi"), t9 = true))
        assertEquals(listOf("此是库"), engine.storedWords("cishiku", t9 = false))
        assertEquals(listOf("此是库"), engine.storedWords(T9Pinyin.toT9("cishiku"), t9 = true))
        assertEquals(emptyList<String>(), engine.storedWords("ci", t9 = false))
    }

    private companion object {
        const val WORD = "和都海会长中性了的这"
        val AHEAD = List(12) { "整句候选占满一行第${('甲'.code + it).toChar()}条" }
        val BEHIND = List(6) { "后面的词${('子'.code + it).toChar()}" }
        val SINGLES = List(8) { (0x4e00 + it).toChar().toString() }
        var cached: Shipped? = null
    }
}
