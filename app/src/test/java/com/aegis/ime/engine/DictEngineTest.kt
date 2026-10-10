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
import com.aegis.ime.decoder.PinyinDecoder
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.decoder.WordReading
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DictEngineTest {

    @Test
    fun empty_engine_reports_no_chinese_support() {
        assertFalse(DictEngine(null, null, null).supportsChinese)
    }

    @Test
    fun empty_engine_returns_no_candidates_and_never_throws() {
        val engine: CandidateEngine = DictEngine(null, null, null)
        assertEquals(emptyList<Cand>(), engine.candidatesCovered("nihao", false))
        assertEquals(emptyList<Cand>(), engine.candidatesCovered("236", true))
        assertEquals(emptyList<Cand>(), engine.candidatesForLockedReadingCovered("nihao"))
        assertEquals(emptyList<String>(), engine.candidates("nihao", false))
    }

    @Test
    fun a_language_model_alone_does_not_unlock_chinese() {
        val lm = EngineFixture.buildLm(mapOf('你'.code to 10L, '好'.code to 8L), emptyMap())
        val engine: CandidateEngine = DictEngine(null, null, lm)
        assertFalse(engine.supportsChinese)
        assertEquals(emptyList<Cand>(), engine.candidatesForLockedReadingCovered("nihao"))
        assertTrue(engine.candidatesCovered("ni", false).isEmpty())
    }

    @Test
    fun a_pinyin_dictionary_unlocks_chinese_support() {
        val dict = EngineFixture.build(listOf(EngineFixture.Row("ni", "你", 900)))
        assertTrue(DictEngine(dict, null, null).supportsChinese)
    }

    @Test
    fun a_t9_dictionary_unlocks_chinese_support() {
        val dict = EngineFixture.build(listOf(EngineFixture.Row("ni", "你", 900)))
        assertTrue(DictEngine(null, dict, null).supportsChinese)
    }

    @Test
    fun learnedWordsRefreshThroughBothDecoders() {
        val reading = "zici"
        val target = "自词"
        val rows = listOf(
            EngineFixture.Row(reading, "常词", 5_000),
            EngineFixture.Row(reading, target, 10),
        )
        val letters = EngineFixture.build(rows)
        val digits = EngineFixture.build(rows.map { EngineFixture.Row(T9Pinyin.toT9(it.key), it.word, it.freq) })
        val learning = UserLearning { 1_000L }
        val engine = DictEngine(letters, digits, null, userLearning = learning)

        assertEquals("常词", engine.candidates(reading, false).first())
        assertEquals("常词", engine.candidates(T9Pinyin.toT9(reading), true).first())
        repeat(3) {
            learning.observeCommit(null, "自", "zi", 1_000L)
            learning.observeCommit("自", "词", "ci", 1_000L)
            learning.observeBreak()
        }
        assertEquals(target, engine.candidates(reading, false).first())
        assertEquals(target, engine.candidates(T9Pinyin.toT9(reading), true).first())
    }

    @Test
    fun learnedPredictionsPrecedeModelPredictionsAndStayDeduplicated() {
        val learning = UserLearning { 1_000L }.apply {
            repeat(3) { observeCommit("前", "甲", "", 1_000L) }
            repeat(2) { observeCommit("前", "乙", "", 1_000L) }
        }
        val model = UserModel { 1_000L }.apply {
            record("前", "乙", 1_000L)
            record("前", "丙", 1_000L)
        }
        val engine = DictEngine(null, null, null, model, userLearning = learning)

        assertEquals(listOf("甲", "乙", "丙"), engine.predict("前"))
    }

    @Test
    fun an_exact_entry_of_the_installed_dictionary_is_a_dictionary_word() {
        val rows = listOf(EngineFixture.Row("ciku", "词库", 850), EngineFixture.Row("ci", "词", 800))
        val engine: CandidateEngine = DictEngine(EngineFixture.build(rows), null, null)
        assertTrue(engine.isDictionaryWord("词库", "ciku"))
        assertTrue(engine.isDictionaryWord("词", "ci"))
    }

    @Test
    fun a_word_missing_from_the_dictionary_is_not_a_dictionary_word() {
        val engine: CandidateEngine = DictEngine(EngineFixture.build(listOf(EngineFixture.Row("ciku", "词库", 850))), null, null)
        assertFalse(engine.isDictionaryWord("此库", "ciku"))
        assertFalse(engine.isDictionaryWord("词库好", "cikuhao"))
    }

    @Test
    fun a_dictionary_word_under_another_reading_is_not_an_exact_entry() {
        val engine: CandidateEngine = DictEngine(EngineFixture.build(listOf(EngineFixture.Row("ciku", "词库", 850))), null, null)
        assertFalse(engine.isDictionaryWord("词库", "cigu"))
        assertFalse(engine.isDictionaryWord("词库", "ci'ku"))
        assertFalse(engine.isDictionaryWord("词库", ""))
    }

    @Test
    fun without_an_installed_dictionary_nothing_is_a_dictionary_word() {
        assertFalse(DictEngine(null, null, null).isDictionaryWord("词库", "ciku"))
        assertFalse(object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        }.isDictionaryWord("词库", "ciku"))
    }

    @Test
    fun nine_key_learning_asks_the_full_pinyin_dictionary_by_spelled_reading() {
        val rows = listOf(EngineFixture.Row("ciku", "词库", 850))
        val digits = EngineFixture.build(rows.map { EngineFixture.Row(T9Pinyin.toT9(it.key), it.word, it.freq) })
        val engine: CandidateEngine = DictEngine(EngineFixture.build(rows), digits, null)
        assertTrue(engine.isDictionaryWord("词库", "ciku"))
        assertFalse(engine.isDictionaryWord("词库", T9Pinyin.toT9("ciku")))
        assertFalse(DictEngine(null, digits, null).isDictionaryWord("词库", "ciku"))
    }

    private val annotated = listOf(
        EngineFixture.Row("yin", "银", 800),
        EngineFixture.Row("xing", "行", 900), EngineFixture.Row("hang", "行", 700),
        EngineFixture.Row("zou", "走", 800),
        EngineFixture.Row("hao", "好", 900),
        EngineFixture.Row("yinhang", "银行", 850),
        EngineFixture.Row("xingzou", "行走", 800),
        EngineFixture.Row("chao", "朝", 900), EngineFixture.Row("zhao", "朝", 500),
        EngineFixture.Row("le", "乐", 900), EngineFixture.Row("yue", "乐", 600),
        EngineFixture.Row("zhang", "长", 900), EngineFixture.Row("chang", "长", 800),
        EngineFixture.Row("zhong", "重", 900), EngineFixture.Row("chong", "重", 600),
        EngineFixture.Row("lexing", "乐行", 300),
        EngineFixture.Row("hangzhang", "行长", 700),
        EngineFixture.Row("changle", "长乐", 500),
    )

    private fun annotating(): CandidateEngine = DictEngine(EngineFixture.build(annotated), null, null)

    @Test
    fun a_monophonic_word_is_annotated_char_by_char_without_choices() {
        assertEquals(
            WordReading(listOf("hao", "yin"), listOf(listOf("hao"), listOf("yin")), ambiguous = false),
            annotating().annotateWord("好银"),
        )
    }

    @Test
    fun a_dictionary_entry_settles_the_reading_of_a_polyphonic_char() {
        val engine = annotating()
        assertEquals(
            WordReading(listOf("yin", "hang"), listOf(listOf("yin"), listOf("xing", "hang")), ambiguous = false),
            engine.annotateWord("银行"),
        )
        assertEquals(
            WordReading(listOf("xing", "zou"), listOf(listOf("xing", "hang"), listOf("zou")), ambiguous = false),
            engine.annotateWord("行走"),
        )
        val longer = engine.annotateWord("好银行")!!
        assertEquals("haoyinhang", longer.reading)
        assertFalse(longer.ambiguous)
    }

    @Test
    fun an_unsettled_polyphonic_char_defaults_to_its_most_frequent_reading_and_keeps_the_others() {
        val annotation = annotating().annotateWord("行好")!!
        assertEquals("xinghao", annotation.reading)
        assertEquals(listOf(listOf("xing", "hang"), listOf("hao")), annotation.choices)
        assertTrue(annotation.ambiguous)
    }

    @Test
    fun a_settled_polyphonic_char_still_lists_its_other_readings() {
        val engine = annotating()
        assertEquals(
            WordReading(
                listOf("chao", "le", "xing"),
                listOf(listOf("chao", "zhao"), listOf("le", "yue"), listOf("xing", "hang")),
                ambiguous = true,
            ),
            engine.annotateWord("朝乐行"),
        )
        assertEquals(
            WordReading(
                listOf("zhong", "hang", "zhang"),
                listOf(listOf("zhong", "chong"), listOf("xing", "hang"), listOf("zhang", "chang")),
                ambiguous = true,
            ),
            engine.annotateWord("重行长"),
        )
        assertEquals(
            WordReading(listOf("chang", "le"), listOf(listOf("zhang", "chang"), listOf("le", "yue")), ambiguous = false),
            engine.annotateWord("长乐"),
        )
    }

    @Test
    fun a_char_lists_at_most_six_readings_and_always_its_default() {
        val readings = listOf("a", "ai", "an", "ang", "ao", "e", "en", "er")
        val rows = readings.mapIndexed { i, reading -> EngineFixture.Row(reading, "阿", 900 - i * 100) } +
            EngineFixture.Row("ba", "爸", 900) + EngineFixture.Row("erba", "阿爸", 500)
        val engine: CandidateEngine = DictEngine(EngineFixture.build(rows), null, null)
        assertEquals(
            WordReading(listOf("ba", "a"), listOf(listOf("ba"), readings.take(6)), ambiguous = true),
            engine.annotateWord("爸阿"),
        )
        assertEquals(
            WordReading(listOf("er", "ba"), listOf(readings.take(5) + "er", listOf("ba")), ambiguous = false),
            engine.annotateWord("阿爸"),
        )
    }

    private fun aliases(): List<Pair<String, String>> =
        PinyinDecoder.INPUT_ALIASES.flatMap { (typed, targets) -> targets.map { typed to it } }

    private fun unrelated(typed: String, target: String, count: Int): List<String> =
        listOf("a", "ai", "an", "ang", "ao", "e", "o", "ou").filter { it != typed && it != target }.take(count)

    private fun aliasing(typed: String, target: String, vararg extra: EngineFixture.Row): CandidateEngine {
        val other = unrelated(typed, target, 1).single()
        val rows = listOf(
            EngineFixture.Row("cun", "存", 900), EngineFixture.Row("hua", "化", 900),
            EngineFixture.Row(other, "嗯", 900), EngineFixture.Row(target, "嗯", 600),
            EngineFixture.Row(target, "唔", 500),
            EngineFixture.Row(typed, "恩", 800),
        )
        return DictEngine(EngineFixture.build(rows + extra), null, null)
    }

    @Test
    fun a_char_read_through_an_input_alias_defaults_to_the_alias_and_keeps_its_readings() {
        assertTrue("the alias table is not empty", aliases().isNotEmpty())
        for ((typed, target) in aliases()) {
            val other = unrelated(typed, target, 1).single()
            val engine = aliasing(typed, target)
            assertEquals(
                WordReading(
                    listOf("cun", typed, "hua"),
                    listOf(listOf("cun"), listOf(typed, other, target), listOf("hua")),
                    ambiguous = true,
                ),
                engine.annotateWord("存嗯化"),
            )
            assertEquals(
                WordReading(listOf("cun", typed), listOf(listOf("cun"), listOf(typed, target)), ambiguous = false),
                engine.annotateWord("存唔"),
            )
            assertEquals(
                WordReading(listOf(typed), listOf(listOf(typed, other, target)), ambiguous = true),
                engine.annotateWord("嗯"),
            )
        }
    }

    @Test
    fun a_char_listed_under_the_alias_itself_keeps_its_own_reading() {
        for ((typed, target) in aliases()) {
            assertEquals(
                WordReading(listOf("cun", typed, "hua"), listOf(listOf("cun"), listOf(typed), listOf("hua")), ambiguous = false),
                aliasing(typed, target).annotateWord("存恩化"),
            )
        }
    }

    @Test
    fun a_dictionary_entry_keeps_its_reading_while_a_longer_word_takes_the_input_alias() {
        for ((typed, target) in aliases()) {
            val other = unrelated(typed, target, 1).single()
            val engine = aliasing(typed, target, EngineFixture.Row(target + target, "嗯嗯", 700))
            val listed = listOf(typed, other, target)
            assertEquals(
                WordReading(listOf(target, target), listOf(listed, listed), ambiguous = false),
                engine.annotateWord("嗯嗯"),
            )
            assertTrue(engine.isDictionaryWord("嗯嗯", engine.annotateWord("嗯嗯")!!.reading))
            assertEquals(
                WordReading(listOf("cun", typed, typed), listOf(listOf("cun"), listed, listed), ambiguous = false),
                engine.annotateWord("存嗯嗯"),
            )
            assertEquals(WordReading(listOf(target), listOf(listOf(typed, target)), ambiguous = false), engine.annotateWord("唔"))
        }
    }

    @Test
    fun an_input_alias_is_listed_first_among_at_most_six_readings() {
        for ((typed, target) in aliases()) {
            val readings = unrelated(typed, target, 7) + target
            val rows = readings.mapIndexed { i, reading -> EngineFixture.Row(reading, "阿", 900 - i * 100) } +
                EngineFixture.Row("ba", "爸", 900) + EngineFixture.Row(readings[6] + "ba", "阿爸", 500)
            val engine: CandidateEngine = DictEngine(EngineFixture.build(rows), null, null)
            assertEquals(
                WordReading(listOf("ba", typed), listOf(listOf("ba"), listOf(typed) + readings.take(5)), ambiguous = true),
                engine.annotateWord("爸阿"),
            )
            assertEquals(
                WordReading(
                    listOf(readings[6], "ba"),
                    listOf(listOf(typed) + readings.take(4) + readings[6], listOf("ba")),
                    ambiguous = false,
                ),
                engine.annotateWord("阿爸"),
            )
        }
    }

    @Test
    fun the_installed_dictionary_annotates_every_aliased_char_with_its_input_alias() {
        val dir = System.getenv("AEGIS_FULLDICT_DIR")
        assumeTrue("full-dict check only when AEGIS_FULLDICT_DIR is set", !dir.isNullOrEmpty())
        val dict = BinaryDict.fromFile(File(dir, "aegis_dict.bin"))
        val engine: CandidateEngine = DictEngine(dict, null, null)
        var checked = 0
        for ((typed, target) in aliases()) {
            val singles = dict.exact(target).map { it.word }.filter { it.codePointCount(0, it.length) == 1 }
            assertTrue("$target lists single characters", singles.isNotEmpty())
            for (single in singles) {
                val annotation = engine.annotateWord("存${single}化")!!
                assertEquals("存${single}化", "cun${typed}hua", annotation.reading)
                assertEquals("存${single}化", typed, annotation.choices[1].first())
                assertTrue("存${single}化 keeps $target", target in annotation.choices[1])
                assertTrue(annotation.choices[1].size <= 6)
                checked++
            }
        }
        assertTrue("at least one aliased character is covered", checked > 0)
        val aliased = engine.annotateWord("存嗯化")!!
        assertEquals("cunenhua", aliased.reading)
        assertEquals(listOf(listOf("cun"), listOf("en", "n", "ng"), listOf("hua")), aliased.choices)
        assertTrue(aliased.ambiguous)
        val plain = engine.annotateWord("存恩化")!!
        assertEquals("cunenhua", plain.reading)
        assertEquals(listOf(listOf("cun"), listOf("en"), listOf("hua")), plain.choices)
        assertFalse(plain.ambiguous)
        assertTrue(engine.isDictionaryWord("嗯嗯", engine.annotateWord("嗯嗯")!!.reading))
    }

    @Test
    fun a_char_without_any_reading_leaves_the_word_unannotated() {
        val engine = annotating()
        assertNull(engine.annotateWord("银鑫"))
        assertNull(engine.annotateWord("银a"))
        assertNull(engine.annotateWord(""))
        assertNull(DictEngine(null, null, null).annotateWord("银行"))
        assertNull(object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        }.annotateWord("银行"))
    }

    @Test
    fun the_installed_dictionary_settles_common_polyphonic_words() {
        val dir = System.getenv("AEGIS_FULLDICT_DIR")
        assumeTrue("full-dict check only when AEGIS_FULLDICT_DIR is set", !dir.isNullOrEmpty())
        val engine: CandidateEngine = DictEngine(BinaryDict.fromFile(File(dir, "aegis_dict.bin")), null, null)
        assertEquals("yinhang", engine.annotateWord("银行")?.reading)
        assertEquals("xingzou", engine.annotateWord("行走")?.reading)
        assertEquals("chongqing", engine.annotateWord("重庆")?.reading)
        assertFalse(engine.annotateWord("银行")!!.ambiguous)
        assertEquals(listOf("xing", "hang"), engine.annotateWord("银行")!!.choices[1].take(2))
        val name = engine.annotateWord("朝乐行")!!
        assertTrue(name.ambiguous)
        assertEquals(listOf("chao", "le", "xing"), name.syllables)
        assertEquals(listOf(listOf("chao", "zhao"), listOf("le", "yue"), listOf("xing", "hang")), name.choices.map { it.take(2) })
        val officer = engine.annotateWord("重行长")!!
        assertTrue(officer.ambiguous)
        assertEquals(
            listOf(setOf("zhong", "chong"), setOf("xing", "hang"), setOf("zhang", "chang")),
            officer.choices.map { it.take(2).toSet() },
        )
        val town = engine.annotateWord("长乐")!!
        assertFalse(town.ambiguous)
        assertEquals("changle", town.reading)
        assertEquals(listOf(setOf("zhang", "chang"), setOf("le", "yue")), town.choices.map { it.toSet() })
        for (word in listOf(name, officer, town)) assertTrue(word.choices.all { it.size <= 6 })
    }
}
