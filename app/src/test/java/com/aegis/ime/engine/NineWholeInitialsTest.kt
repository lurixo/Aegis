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
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.LayoutId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NineWholeInitialsTest {
    private class Host : ImeHost {
        val commits = ArrayList<String>()
        override fun commitText(text: CharSequence) { commits.add(text.toString()) }
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    private val rows = listOf(
        EngineFixture.Row("yi", "一", 900), EngineFixture.Row("yi", "以", 880),
        EngineFixture.Row("yi", "已", 860), EngineFixture.Row("yi", "亿", 500),
        EngineFixture.Row("yi", "义", 480), EngineFixture.Row("xi", "西", 600),
        EngineFixture.Row("xi", "系", 580), EngineFixture.Row("zi", "子", 700),
        EngineFixture.Row("yiyi", "意义", 800), EngineFixture.Row("xixi", "嘻嘻", 300),
        EngineFixture.Row("yixi", "一系", 200), EngineFixture.Row("tu", "图", 300),
        EngineFixture.Row("tu", "土", 200), EngineFixture.Row("wo", "我", 950),
    )

    private val pairWords = listOf("中国", "一个", "喜欢", "之后", "以后", "外国", "香港", "这个", "文化", "银行", "学会", "晚高")
    private val pairKeys = listOf("zg", "yg", "xh", "zh", "yh", "wg", "xg", "zg", "wh", "yh", "xh", "wg")
    private val fourKeys = listOf(
        "wwww", "wxyz", "xxxx", "xyxy", "yyyy", "yzyz", "zzzz", "zwzw", "wzxy", "xwyz", "ywzx", "zxwy",
    )
    private val fourWords = fourKeys.indices.map { "四字" + "甲乙丙丁戊己庚辛壬癸子丑"[it] + "词" }

    private val initials =
        pairWords.indices.map { EngineFixture.Row(pairKeys[it], pairWords[it], 950 - it * 10) } +
            fourKeys.indices.map { EngineFixture.Row(fourKeys[it], fourWords[it], 500 - it * 10) } +
            listOf(
                EngineFixture.Row("tt", "天天", 900), EngineFixture.Row("tt", "太太", 800),
                EngineFixture.Row("tt", "团体", 700), EngineFixture.Row("zgyh", "中国银行", 500),
                EngineFixture.Row("xhzg", "喜欢中国", 400), EngineFixture.Row("xyz", "小宇宙", 60),
                EngineFixture.Row("xx", "学习", 955), EngineFixture.Row("xxxxxxxx", "八个嘻嘻嘻嘻嘻嘻", 30),
            )

    private fun engine(initialRows: List<EngineFixture.Row>? = initials) = DictEngine(
        EngineFixture.build(rows),
        EngineFixture.build(rows.map { it.copy(key = T9Pinyin.toT9(it.key)) }),
        null,
        initialsDict = initialRows?.let(EngineFixture::build),
    )

    private val moreFourKeys = listOf("wwwx", "wwwy", "wwwz", "xxxw", "xxxy", "xxxz", "yyyw", "yyyx", "yyyz", "zzzw")
    private val moreFourWords = moreFourKeys.indices.map { "四字" + "寅卯辰巳午未申酉戌亥"[it] + "词" }
    private val moreFour = moreFourKeys.indices.map { EngineFixture.Row(moreFourKeys[it], moreFourWords[it], 300 - it * 10) }

    private fun DictEngine.whole(digits: String) =
        CandidateEngine.withInitialWords(candidatesCovered(digits, true), initialWords(digits))

    private fun nine(engine: DictEngine, host: Host = Host()) = KeyboardController(host, engine).apply {
        setCnDefaultLayout(LayoutId.NINE)
        reset()
    }

    private val segment = Key("'", action = KeyAction.SEGMENT)

    private fun KeyboardController.type(input: String) {
        for (ch in input) onKey(if (ch == '\'') segment else Key(ch.toString(), output = ch.toString()))
    }

    @Test fun initial_words_follow_the_first_three_spelled_candidates() {
        val spelled = engine(null).candidatesCovered("94", true)
        assertTrue("$spelled", spelled.size > 3)
        val e = engine()
        assertEquals(spelled, e.candidatesCovered("94", true))
        assertEquals(
            spelled.take(3) + pairWords.take(10).map { Cand(it, 2) } + spelled.drop(3),
            e.whole("94"),
        )
        assertEquals(spelled, e.candidatesCoveredWithSentences("94", true, "", 4).first)
    }

    @Test fun a_word_is_listed_once_at_its_earliest_place() {
        val spelled = engine(null).candidatesCovered("94", true)
        assertTrue("$spelled", spelled.size > 4)
        val early = spelled[1].word
        val late = spelled[4].word
        val e = engine(initials + EngineFixture.Row("yg", early, 999) + EngineFixture.Row("zh", late, 998))
        val middle = (listOf(late) + pairWords.take(9)).map { Cand(it, 2) }
        assertEquals(
            spelled.take(3) + middle + spelled.drop(3).filterNot { it.word == late },
            e.whole("94"),
        )
        val out = e.whole("94")
        assertEquals(1, out.count { it.word == early })
        assertEquals(1, out.count { it.word == late })
    }

    @Test fun the_initial_words_are_refilled_to_ten_after_dropping_the_ones_already_ahead() {
        val spelled = engine(null).candidatesCovered("94", true)
        val head = spelled.take(3)
        assertEquals("$spelled", 3, head.size)
        val e = engine(initials + head.mapIndexed { i, c -> EngineFixture.Row("yg", c.word, 999 - i) })
        assertEquals(head.map { it.word } + pairWords.take(10), e.initialWords("94").map { it.word })
        assertEquals(head + pairWords.take(10).map { Cand(it, 2) } + spelled.drop(3), e.whole("94"))
        val two = engine(initials + head.take(2).mapIndexed { i, c -> EngineFixture.Row("yg", c.word, 999 - i) })
        assertEquals(head + pairWords.take(10).map { Cand(it, 2) } + spelled.drop(3), two.whole("94"))
    }

    @Test fun initial_words_follow_three_candidates_in_whatever_order_they_are_given() {
        val e = engine()
        val spelled = e.candidatesCovered("94", true)
        assertTrue("$spelled", spelled.size > 4)
        val supply = e.initialWords("94")
        assertEquals(pairWords, supply.map { it.word })
        val late = listOf(spelled[4]) + spelled.filterNot { it == spelled[4] }
        assertEquals(
            late.take(3) + pairWords.take(10).map { Cand(it, 2) } + late.drop(3),
            CandidateEngine.withInitialWords(late, supply),
        )
        val picked = listOf(Cand(pairWords[1], 2)) + spelled
        assertEquals(
            picked.take(3) + (pairWords - pairWords[1]).take(10).map { Cand(it, 2) } + picked.drop(3),
            CandidateEngine.withInitialWords(picked, supply),
        )
        assertEquals(
            spelled.take(4) + pairWords.take(10).map { Cand(it, 2) } + spelled.drop(4),
            CandidateEngine.withInitialWords(spelled, supply, 4),
        )
    }

    @Test fun fewer_than_three_spelled_candidates_all_stay_ahead() {
        val spelled = engine(null).candidatesCovered("88", true)
        assertTrue("$spelled", spelled.size in 1..2)
        assertEquals(
            spelled + listOf(Cand("天天", 2), Cand("太太", 2), Cand("团体", 2)),
            engine().whole("88"),
        )
    }

    @Test fun without_a_spelled_candidate_twenty_initial_words_lead_the_shorter_readings() {
        val e = engine(initials + moreFour)
        assertTrue(engine(null).candidatesCovered("9999", true).isEmpty())
        val out = e.candidatesCovered("9999", true)
        assertEquals(
            (fourWords + moreFourWords.take(8)).map { Cand(it, 4) } + Cand("小宇宙", 3) + Cand("学习", 2),
            out.take(22),
        )
        val single = out.drop(22)
        assertTrue("$single", single.isNotEmpty() && single.all { it.coveredLen == 1 })
        assertEquals(out.size, out.map { it.word }.toSet().size)
        assertEquals(out, e.candidatesCoveredWithSentences("9999", true, "", 4).first)
        assertEquals(out, e.candidatesCovered("9999", true, setOf(2), ""))
        assertEquals(out, e.whole("9999"))
        val c = nine(e)
        c.type("9999")
        assertEquals(out.map { it.word }, c.candidateWords())
    }

    @Test fun initial_words_are_found_past_six_digits() {
        val e = engine()
        assertEquals(Cand("八个嘻嘻嘻嘻嘻嘻", 8), e.whole("99999999").first())
        assertEquals(Cand("八个嘻嘻嘻嘻嘻嘻", 8), e.candidatesCovered("99999999", true).first())
        assertEquals(Cand("四字甲词", 4), e.whole("99999999")[1])
    }

    @Test fun one_digit_forced_cuts_letters_and_a_missing_dictionary_add_nothing() {
        val e = engine()
        val plain = engine(null)
        assertEquals(plain.candidatesCovered("9", true), e.whole("9"))
        assertTrue(e.whole("9").isNotEmpty())
        val cut = plain.candidatesCovered("9494", true, setOf(2), "")
        assertTrue("$cut", cut.isNotEmpty())
        assertEquals(cut, e.candidatesCovered("9494", true, setOf(2), ""))
        assertEquals(plain.candidatesCovered("9494", true), e.candidatesCovered("9494", true))
        assertTrue(e.whole("9494").any { it.word == "中国银行" })
        assertEquals(plain.candidatesCovered("9494", true), plain.whole("9494"))
        assertEquals(emptyList<Cand>(), CandidateEngine.withInitialWords(emptyList(), e.initialWords("94")))
        assertEquals(emptyList<Cand>(), e.initialWords("9"))
        assertEquals(emptyList<Cand>(), plain.initialWords("94"))
        for (letters in listOf("yi", "yg", "zg", "zgyh", "xxxx")) {
            val c = KeyboardController(Host(), e).apply { setCnDefaultLayout(LayoutId.ALPHA); reset() }
            c.type(letters)
            assertEquals(letters, e.candidatesCovered(letters, false).map { it.word }, c.candidateWords())
        }
    }

    @Test fun typed_digits_show_initial_words_and_picking_one_commits_the_whole_input() {
        val e = engine()
        val host = Host()
        val c = nine(e, host)
        c.type("94")
        assertEquals(e.whole("94").map { it.word }, c.candidateWords())
        c.onPickCandidate(c.candidateWords().indexOf("中国"))
        assertEquals(listOf("中国"), host.commits)
        assertEquals("", c.rawComposingForTest())
        assertEquals("", c.composingPrefix())
    }

    @Test fun a_forced_cut_a_locked_reading_and_a_literal_hide_the_initial_words() {
        val e = engine()
        val plain = engine(null)
        val offered = (pairWords + listOf("中国银行", "喜欢中国")).toSet()

        val cut = nine(e)
        cut.type("94'94")
        assertEquals(plain.candidatesCovered("9494", true, setOf(2), "").map { it.word }, cut.candidateWords())
        val uncut = nine(e)
        uncut.type("9494")
        assertTrue("${uncut.candidateWords()}", "中国银行" in uncut.candidateWords())

        val locked = nine(e)
        locked.type("94")
        assertTrue("${locked.candidateWords()}", "中国" in locked.candidateWords())
        val reading = locked.expandedReadings().indexOf("yi")
        assertTrue("${locked.expandedReadings()}", reading >= 0)
        locked.onPickReadingIndex(reading)
        assertTrue("${locked.candidateWords()}", locked.candidateWords().isNotEmpty())
        assertFalse("${locked.candidateWords()}", locked.candidateWords().any { it in offered })

        val literal = nine(e)
        literal.type("94")
        literal.onKey(Key("1", output = "1", preeditLiteral = true))
        assertEquals("941", literal.rawComposingForTest())
        assertTrue("${literal.candidateWords()}", literal.candidateWords().isNotEmpty())
        assertFalse("${literal.candidateWords()}", literal.candidateWords().any { it in offered })
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

    private val common = listOf(
        "nh" to "你好", "zg" to "中国", "bzd" to "不知道", "wsm" to "为什么", "jttq" to "今天天气", "wxhn" to "我喜欢你",
    )

    @Test fun full_dictionary_common_initials_follow_the_first_three_spelled_candidates() {
        val e = fullEngine()
        val source = BinaryDict.fromFile(File(fullAssets, FullDictTestAssets.JIANPIN))
        assertEquals(listOf("64", "94", "293", "976", "5887", "9946"), common.map { T9Pinyin.toT9(it.first) })
        for ((letters, word) in common) {
            val digits = T9Pinyin.toT9(letters)
            val spelled = e.candidatesCovered(digits, true)
            val out = e.whole(digits)
            val head = spelled.take(3)
            assertTrue("$digits has spelled candidates", head.isNotEmpty())
            assertEquals(digits, head, out.take(head.size))
            val at = out.indexOfFirst { it.word == word }
            assertTrue("$digits offers $word at $at", at in head.size until head.size + 10)
            assertEquals(digits, Cand(word, digits.length), out[at])
            val ahead = head.map { it.word }.toSet()
            val middle = source.exactAmong(T9Pinyin.initialLetters(digits), 40)
                .map { it.word }.filterNot { it in ahead }.take(10).map { Cand(it, digits.length) }
            val placed = middle.map { it.word }.toSet()
            assertEquals(digits, head + middle + spelled.drop(3).filterNot { it.word in placed }, out)
            for (cand in middle) assertEquals("$digits ${cand.word}", 1, out.count { it.word == cand.word })
            assertEquals(digits, spelled, e.candidatesCoveredWithSentences(digits, true, "", 4).first)
        }
    }

    @Test fun full_dictionary_letters_and_single_digits_are_unchanged() {
        val e = fullEngine()
        for ((letters, word) in common) {
            val plain = e.candidatesCovered(letters, false)
            assertTrue("$letters offers $word", plain.any { it.word == word })
            val c = KeyboardController(Host(), e).apply { setCnDefaultLayout(LayoutId.ALPHA); reset() }
            c.type(letters)
            assertEquals(letters, plain.map { it.word }, c.candidateWords())
        }
        for (digit in '2'..'9') {
            val plain = e.candidatesCovered(digit.toString(), true)
            assertTrue("$digit", plain.isNotEmpty())
            assertEquals(digit.toString(), plain, e.whole(digit.toString()))
            val c = nine(e)
            c.type(digit.toString())
            assertEquals(digit.toString(), plain.map { it.word }, c.candidateWords().filter { w -> plain.any { it.word == w } })
        }
    }

    @Test fun full_dictionary_typed_initials_commit_the_whole_input() {
        val e = fullEngine()
        for ((letters, word) in common) {
            val digits = T9Pinyin.toT9(letters)
            val host = Host()
            val c = nine(e, host)
            c.type(digits)
            val shown = e.candidatesWithInitials(digits, "").ifEmpty { e.candidatesCovered(digits, true) }
            assertEquals(
                digits,
                CandidateEngine.withInitialWords(shown, e.initialWords(digits)).map { it.word },
                c.candidateWords(),
            )
            c.onPickCandidate(c.candidateWords().indexOf(word))
            assertEquals(digits, listOf(word), host.commits)
            assertEquals(digits, "", c.rawComposingForTest())
        }
        val cut = nine(e)
        cut.type("64'426")
        assertEquals(e.candidatesCovered("64426", true, setOf(2), "").map { it.word }, cut.candidateWords())
    }
}
