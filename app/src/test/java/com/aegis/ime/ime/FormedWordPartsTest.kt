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
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.user.UserLearning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FormedWordPartsTest {

    private class EditorHost : ImeHost {
        val sb = StringBuilder()
        override fun commitText(text: CharSequence) { sb.append(text) }
        override fun deleteBackward() { if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1) }
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = sb.takeLast(n)
    }

    private val luoName = listOf("罗" to "luo", "三" to "san", "为" to "wei")
    private val cengName = listOf("层" to "ceng", "三" to "san", "为" to "wei")
    private val good = listOf("好" to "hao")
    private val sixteenName = listOf(
        "司" to "si", "马" to "ma", "东" to "dong", "方" to "fang",
        "明" to "ming", "月" to "yue", "清" to "qing", "风" to "feng",
        "欧" to "ou", "阳" to "yang", "长" to "chang", "空" to "kong",
        "白" to "bai", "云" to "yun", "苍" to "cang", "狗" to "gou",
    )

    private val rows = (luoName + cengName + good + sixteenName).distinct().map { (ch, r) -> EngineFixture.Row(r, ch, 900) } +
        T9Pinyin.SYLLABLES.map { EngineFixture.Row(it, "丌", 1) }

    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val lm: CharBigramLM by lazy { CharBigramLM.fromFile(lmFile) }

    @Before fun requireLm() {
        assumeTrue("real LM asset present (production engine wiring)", lmFile.exists())
    }

    private fun controller(learning: UserLearning, nine: Boolean): KeyboardController {
        val letters = EngineFixture.build(rows)
        val digits = EngineFixture.build(rows.map { EngineFixture.Row(T9Pinyin.toT9(it.key), it.word, it.freq) })
        val engine = DictEngine(letters, digits, lm, userLearning = learning)
        return KeyboardController(EditorHost(), engine).also {
            it.userLearning = learning
            it.switchTextLayoutForTest(nine = nine)
        }
    }

    private fun typeCharByChar(c: KeyboardController, nine: Boolean, name: List<Pair<String, String>>) {
        for ((ch, r) in name) {
            (if (nine) T9Pinyin.toT9(r) else r).forEach { c.onKey(Key(it.toString(), output = it.toString())) }
            val i = c.candidateWords().indexOf(ch)
            assertTrue("candidate '$ch' present in ${c.candidateWords()}", i >= 0)
            c.onPickCandidate(i)
        }
        c.reset(preserveLayout = true)
    }

    private fun word(name: List<Pair<String, String>>) = name.joinToString("") { it.first }

    private fun formedWords(learning: UserLearning) = learning.formedEntries().map { it.word }.toSet()

    private fun countingRows(learning: UserLearning): List<String> {
        val file = File.createTempFile("userlearn-parts", ".txt").also { it.deleteOnExit() }
        learning.save(file)
        return file.readLines().filter { it.startsWith("P\t") }
    }

    private fun partsOfAFormedNameDoNotFormWithAnotherName(nine: Boolean) {
        val learning = UserLearning()
        val c = controller(learning, nine)
        repeat(4) { typeCharByChar(c, nine, luoName) }
        assertEquals(listOf(UserLearning.Formed("罗三为", "luosanwei")), learning.formedEntries())
        assertTrue("its parts are not counted: ${countingRows(learning)}", countingRows(learning).isEmpty())
        repeat(2) { typeCharByChar(c, nine, cengName) }
        assertEquals(setOf("罗三为"), formedWords(learning))
        typeCharByChar(c, nine, cengName)
        assertEquals(setOf("罗三为", "层三为"), formedWords(learning))
    }

    private fun formedNameTypedManyTimesLeavesNoPart(nine: Boolean) {
        for (length in listOf(3, 5, 16)) {
            val name = sixteenName.take(length)
            val learning = UserLearning()
            val c = controller(learning, nine)
            repeat(14) { typeCharByChar(c, nine, name) }
            assertEquals(setOf(word(name)), formedWords(learning))
            assertTrue("its parts are not counted: ${countingRows(learning)}", countingRows(learning).isEmpty())
        }
    }

    private fun formedNameGrowsOnlyAsAWhole(nine: Boolean) {
        val learning = UserLearning()
        val c = controller(learning, nine)
        repeat(3) { typeCharByChar(c, nine, luoName) }
        repeat(2) { typeCharByChar(c, nine, luoName + good) }
        assertEquals(setOf("罗三为"), formedWords(learning))
        assertEquals("only the longer whole is counted", 1, countingRows(learning).size)
        typeCharByChar(c, nine, luoName + good)
        assertEquals(setOf("罗三为", "罗三为好"), formedWords(learning))
    }

    @Test fun parts_of_a_formed_name_do_not_form_with_another_name_on26key() =
        partsOfAFormedNameDoNotFormWithAnotherName(nine = false)

    @Test fun parts_of_a_formed_name_do_not_form_with_another_name_on9key() =
        partsOfAFormedNameDoNotFormWithAnotherName(nine = true)

    @Test fun formed_name_typed_many_times_leaves_no_part_on26key() = formedNameTypedManyTimesLeavesNoPart(nine = false)

    @Test fun formed_name_typed_many_times_leaves_no_part_on9key() = formedNameTypedManyTimesLeavesNoPart(nine = true)

    @Test fun formed_name_grows_only_as_a_whole_on26key() = formedNameGrowsOnlyAsAWhole(nine = false)

    @Test fun formed_name_grows_only_as_a_whole_on9key() = formedNameGrowsOnlyAsAWhole(nine = true)
}
