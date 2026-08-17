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
import com.aegis.ime.decoder.FullDictTestAssets
import com.aegis.ime.decoder.PinyinDecoder
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StagedFirstScreenKeyboardTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val assets = FullDictTestAssets.directory

    private fun assetsPresent() = FullDictTestAssets.available(
        File(assets, FullDictTestAssets.DICT),
        File(assets, FullDictTestAssets.T9),
        File(assets, FullDictTestAssets.LM),
        File(assets, FullDictTestAssets.JIANPIN),
    )

    private class Host : ImeHost {
        override fun commitText(text: CharSequence) {}
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    private fun realEngine() = DictEngine(
        BinaryDict.fromFile(File(assets, FullDictTestAssets.DICT)),
        BinaryDict.fromFile(File(assets, FullDictTestAssets.T9)),
        CharBigramLM.fromFile(File(assets, FullDictTestAssets.LM)),
        initialsDict = BinaryDict.fromFile(File(assets, FullDictTestAssets.JIANPIN)),
    )

    private fun controller(engine: CandidateEngine) = KeyboardController(Host(), engine).apply { attachView(InputView(ctx)) }

    private fun type(c: KeyboardController, s: String) =
        s.forEach { c.onKey(Key(it.toString(), output = it.toString())) }

    private fun pick(c: KeyboardController, reading: String) =
        c.onKey(Key(reading, output = reading, action = KeyAction.PICK_READING))

    private fun isSingleChar(word: String) = word.codePointCount(0, word.length) == 1

    private val scenarios = listOf(
        listOf("wan", "shi", "wang", "lian"),
        listOf("wo", "men", "yi", "qi"),
        listOf("jin", "tian", "tian", "qi"),
        listOf("zhang", "wei", "ming"),
    )

    private fun alphaLocked(readings: List<String>, engine: CandidateEngine = realEngine()): List<String> {
        val c = controller(engine)
        c.switchTextLayoutForTest(nine = false)
        type(c, readings.joinToString(""))
        readings.forEach { pick(c, it) }
        assertEquals("every reading is locked", readings.joinToString("'"), c.preeditForTest())
        return c.candidateWords()
    }

    @Test fun theFullReadingSentenceLeadsBothKeyboards() {
        assumeTrue(assetsPresent())
        val readings = listOf("ni", "de", "ping", "guo")
        for ((name, words) in listOf("26-key" to alphaLocked(readings), "9-key" to nineKeyLocked(readings))) {
            assertEquals(
                "$name: the sentence covering every locked reading leads, was ${words.take(6)}",
                "你的苹果",
                words.first(),
            )
            val firstSingle = words.indexOfFirst { isSingleChar(it) }
            assertTrue(
                "$name: the singles still start inside the real word slots, was $firstSingle",
                firstSingle in 1..PinyinDecoder.STAGED_REAL_WORD_SLOTS,
            )
        }
    }

    private fun nineKeyLocked(readings: List<String>, engine: CandidateEngine = realEngine()): List<String> {
        val c = controller(engine)
        c.switchTextLayoutForTest(nine = true)
        type(c, readings.joinToString("") { T9Pinyin.toT9(it) })
        readings.forEach { pick(c, it) }
        assertEquals("every reading is locked", readings.joinToString("'"), c.preeditForTest())
        return c.candidateWords()
    }

    private fun alphaSeparated(readings: List<String>): List<String> {
        val c = controller(realEngine())
        c.switchTextLayoutForTest(nine = false)
        readings.forEachIndexed { index, reading ->
            if (index > 0) c.onKey(Key("'", output = "'"))
            type(c, reading)
        }
        assertEquals("the typed separators stay in the preedit", readings.joinToString("'"), c.preeditForTest())
        return c.candidateWords()
    }

    private fun assertStagedShape(label: String, readings: List<String>, candidates: List<String>) {
        val firstSingle = candidates.indexOfFirst { isSingleChar(it) }
        assertTrue(
            "$label: the first single must reach the first screen, was $firstSingle in ${candidates.take(12)}",
            firstSingle in 0..PinyinDecoder.STAGED_REAL_WORD_SLOTS,
        )
        val singles = BinaryDict.fromFile(File(assets, FullDictTestAssets.DICT)).exact(readings.first())
            .filter { isSingleChar(it.word) }
            .map { it.word }
            .toSet()
        assertEquals(
            "$label: the list holds every dictionary single of the first reading and no other",
            singles,
            candidates.filter { isSingleChar(it) }.toSet(),
        )
        val closing = candidates.takeLastWhile { isSingleChar(it) }
        assertTrue("$label: the list closes on single characters", closing.isNotEmpty())
        assertTrue(
            "$label: every multi-char candidate precedes the closing run, was ${candidates.size - closing.size}",
            candidates.indexOfLast { !isSingleChar(it) } < candidates.size - closing.size,
        )
    }

    @Test fun lockingEveryReadingStagesTheFirstScreenOnBothKeyboards() {
        assumeTrue(assetsPresent())
        for (readings in scenarios) {
            val alpha = alphaLocked(readings)
            val nine = nineKeyLocked(readings)
            assertStagedShape("26-key $readings", readings, alpha)
            assertStagedShape("9-key $readings", readings, nine)
            assertEquals("both keyboards offer the same locked candidates for $readings", alpha, nine)
        }
    }

    @Test fun separatorSegmentedInputStagesLikeLockedReadings() {
        assumeTrue(assetsPresent())
        for (readings in scenarios) {
            val separated = alphaSeparated(readings)
            assertStagedShape("26-key separated $readings", readings, separated)
            assertEquals("the separator path offers the locked candidates for $readings", alphaLocked(readings), separated)
        }
    }

    private fun learnedWordEngine(): CandidateEngine {
        val rows = ArrayList<EngineFixture.Row>()
        listOf("民" to 900, "敏" to 850, "闽" to 800, "闵" to 700, "皿" to 600, "悯" to 500)
            .forEach { rows.add(EngineFixture.Row("min", it.first, it.second)) }
        listOf("意" to 900, "一" to 880, "以" to 860, "艺" to 700)
            .forEach { rows.add(EngineFixture.Row("yi", it.first, it.second)) }
        listOf(
            "民意" to 990, "民艺" to 980, "敏意" to 970, "敏艺" to 960, "闽意" to 950,
            "闽艺" to 940, "闵意" to 930, "闵艺" to 920, "皿意" to 910,
        ).forEach { rows.add(EngineFixture.Row("minyi", it.first, it.second)) }
        val letters = EngineFixture.build(rows)
        val digits = EngineFixture.build(rows.map { EngineFixture.Row(T9Pinyin.toT9(it.key), it.word, it.freq) })
        val model = UserModel().apply { recordWord("minyi", "悯意", 1L, incrementCount = true) }
        return DictEngine(letters, digits, null, userModel = model)
    }

    @Test fun aLearnedWordTakesARealWordSlotOnBothKeyboards() {
        val readings = listOf("min", "yi")
        val engine = learnedWordEngine()
        val cases = listOf(
            "26-key" to alphaLocked(readings, engine),
            "9-key" to nineKeyLocked(readings, engine),
        )
        for ((label, candidates) in cases) {
            val at = candidates.indexOf("悯意")
            val firstSingle = candidates.indexOfFirst { isSingleChar(it) }
            assertTrue("$label: the learned word is recalled, was $candidates", at >= 0)
            assertTrue(
                "$label: the learned word sits in the real word segment, was at $at with singles from $firstSingle",
                at < firstSingle,
            )
            assertEquals("$label: the real word segment keeps its eight slots", 8, firstSingle)
            assertEquals("$label: the learned word appears once", 1, candidates.count { it == "悯意" })
        }
        assertEquals("both keyboards agree", cases[0].second, cases[1].second)
    }

    @Test fun gluedCombinationsAndEntriesBothPrecedeTheClosingRun() {
        assumeTrue(assetsPresent())
        val readings = listOf("wan", "shi", "wang", "lian")
        for ((label, candidates) in listOf("26-key" to alphaLocked(readings), "9-key" to nineKeyLocked(readings))) {
            val closing = candidates.takeLastWhile { isSingleChar(it) }
            assertTrue("$label: the list closes on single characters", closing.isNotEmpty())
            for (word in listOf("万事网联", "万事")) {
                val at = candidates.indexOf(word)
                assertTrue("$label: $word stays in the list, was ${candidates.size} candidates", at >= 0)
                assertTrue(
                    "$label: $word precedes the closing run, was at $at of ${candidates.size}",
                    at < candidates.size - closing.size,
                )
            }
        }
    }
}
