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
class LeftToRightReachabilityTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val assets = File("src/main/assets")
    private fun assetsPresent() =
        File(assets, "aegis_dict.bin").exists() &&
            File(assets, "aegis_t9.bin").exists() &&
            File(assets, "aegis_lm.bin").exists()

    private class Host : ImeHost {
        val commits = mutableListOf<String>()
        override fun commitText(text: CharSequence) { commits.add(text.toString()) }
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    private val dict by lazy { BinaryDict.fromFile(File(assets, "aegis_dict.bin")) }

    private fun realEngine() = DictEngine(
        dict,
        BinaryDict.fromFile(File(assets, "aegis_t9.bin")),
        CharBigramLM.fromFile(File(assets, "aegis_lm.bin")),
    )

    private fun controller(): KeyboardController =
        KeyboardController(Host(), realEngine()).apply { attachView(InputView(ctx)) }

    private fun type(c: KeyboardController, s: String) =
        s.forEach { c.onKey(Key(it.toString(), output = it.toString())) }
    private fun pickReading(c: KeyboardController, reading: String) =
        c.onKey(Key(reading, output = reading, action = KeyAction.PICK_READING))

    private fun isSingleChar(word: String): Boolean = word.codePointCount(0, word.length) == 1
    private fun dictSingles(key: String): Set<String> =
        dict.exact(key).filter { isSingleChar(it.word) }.map { it.word }.toSet()
    private fun shownSingles(c: KeyboardController): Set<String> =
        c.candidateWords().filter { isSingleChar(it) }.toSet()

    private fun assertComplete(what: String, expected: Set<String>, c: KeyboardController) {
        val missing = expected - shownSingles(c)
        assertTrue(
            "$what: ${missing.size} of ${expected.size} characters unreachable, e.g. ${missing.take(8)}",
            missing.isEmpty(),
        )
    }

    private fun pickLeadingSingle(c: KeyboardController, of: Set<String>): String {
        val index = c.candidateWords().indexOfFirst { it in of }
        assertTrue("a leading-syllable character must be offered", index >= 0)
        val word = c.candidateWords()[index]
        c.onPickCandidate(index)
        return word
    }

    @Test fun nine_key_with_two_locked_readings_reaches_every_character_left_to_right() {
        assumeTrue(assetsPresent())
        val ni = dictSingles("ni")
        val hao = dictSingles("hao")
        assumeTrue("the pack holds more ni characters than the candidate limit", ni.size > 30)

        val c = controller()
        c.onKey(Key("", action = KeyAction.SWITCH_NINE))
        type(c, T9Pinyin.toT9("ni"))
        pickReading(c, "ni")
        type(c, T9Pinyin.toT9("hao"))
        pickReading(c, "hao")
        assertEquals("ni'hao", c.preeditForTest())

        assertComplete("9-key with ni and hao locked", ni, c)

        val picked = pickLeadingSingle(c, ni)
        assertEquals("the committed character stays in the preedit", picked + "hao", c.preeditForTest())
        assertTrue(
            "the reading rail advances to the next syllable, was ${c.expandedReadings()}",
            "hao" in c.expandedReadings(),
        )
        assertComplete("9-key after committing $picked", hao, c)
    }

    @Test fun alpha_layout_reaches_every_character_left_to_right() {
        assumeTrue(assetsPresent())
        val ni = dictSingles("ni")
        val hao = dictSingles("hao")
        assumeTrue("the pack holds more ni characters than the candidate limit", ni.size > 30)

        val c = controller()
        type(c, "nihao")
        assertEquals("nihao", c.preeditForTest())

        assertComplete("26-key nihao", ni, c)

        val picked = pickLeadingSingle(c, ni)
        assertEquals("the committed character stays in the preedit", picked + "hao", c.preeditForTest())
        assertComplete("26-key after committing $picked", hao, c)
    }

    @Test fun a_character_past_the_candidate_limit_can_still_be_committed() {
        assumeTrue(assetsPresent())
        val ni = dictSingles("ni")
        assumeTrue("the pack holds more ni characters than the candidate limit", ni.size > 30)

        val host = Host()
        val c = KeyboardController(host, realEngine()).apply { attachView(InputView(ctx)) }
        type(c, "ni")
        val words = c.candidateWords()
        val late = words.withIndex().last { (_, w) -> isSingleChar(w) && w in ni }
        assertTrue("the tail character sits past the 30-candidate window", late.index >= 30)
        c.onPickCandidate(late.index)
        assertEquals(listOf(late.value), host.commits)
    }
}
