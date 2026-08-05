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
import com.aegis.ime.decoder.PinyinDecoder
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
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

    private fun controller() = KeyboardController(Host(), realEngine()).apply { attachView(InputView(ctx)) }

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

    private fun alphaLocked(readings: List<String>): List<String> {
        val c = controller()
        c.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
        type(c, readings.joinToString(""))
        readings.forEach { pick(c, it) }
        assertEquals("every reading is locked", readings.joinToString("'"), c.preeditForTest())
        return c.candidateWords()
    }

    private fun nineKeyLocked(readings: List<String>): List<String> {
        val c = controller()
        c.onKey(Key("", action = KeyAction.SWITCH_NINE))
        type(c, readings.joinToString("") { T9Pinyin.toT9(it) })
        readings.forEach { pick(c, it) }
        assertEquals("every reading is locked", readings.joinToString("'"), c.preeditForTest())
        return c.candidateWords()
    }

    private fun alphaSeparated(readings: List<String>): List<String> {
        val c = controller()
        c.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
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
            "$label: the single segment holds every dictionary single of the first reading",
            singles,
            candidates.drop(firstSingle).takeWhile { isSingleChar(it) }.toSet(),
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

    @Test fun gluedCombinationsLeaveTheFirstScreenButStayReachable() {
        assumeTrue(assetsPresent())
        val readings = listOf("wan", "shi", "wang", "lian")
        val candidates = alphaLocked(readings)
        val firstSingle = candidates.indexOfFirst { isSingleChar(it) }
        val glued = candidates.indexOf("万事网联")
        assertTrue("the glued combination stays reachable, was ${candidates.size} candidates", glued >= 0)
        assertTrue("the glued combination leaves the first screen, was at $glued", glued > firstSingle)
    }
}
