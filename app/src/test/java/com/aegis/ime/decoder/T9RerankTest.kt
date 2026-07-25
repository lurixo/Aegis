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

package com.aegis.ime.decoder

import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class T9RerankTest {

    private val t9File = File("src/main/assets/aegis_t9.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")

    private fun decoder(user: UserModel? = null): PinyinDecoder {
        assumeTrue("T9 + LM assets present", t9File.exists() && lmFile.exists())
        return PinyinDecoder(BinaryDict.fromFile(t9File), CharBigramLM.fromFile(lmFile), userModel = user)
    }

    @Test
    fun bareSameCodeIsFrequencyOrdered() {
        val list = decoder().decodeCovered("943943", 30).map { it.word }
        assertEquals("这些", list.first())
        assertTrue("谢谢 is present, just outranked by frequency", list.contains("谢谢"))
    }

    @Test
    fun learningCanPromoteAnExhaustiveTailEntryIntoTheBoundedWindow() {
        val before = decoder().decodeCovered("943943", 30).map { it.word }
        val user = UserModel().apply { repeat(40) { record(null, "写者", 1) } }
        val after = decoder(user).decodeCovered("943943", 30).map { it.word }
        assertEquals(30, before.size)
        assertEquals(30, after.size)
        assertTrue("the unlearned exhaustive tail stays outside the primary window", "写者" !in before)
        assertTrue("learning promotes 写者 into the bounded primary window", "写者" in after)
    }

    @Test
    fun learningDisambiguatesSameCodeToTop1() {
        val user = UserModel().apply { repeat(30) { record(null, "谢谢", 1) } }
        val list = decoder(user).decodeCovered("943943", 30).map { it.word }
        assertEquals("谢谢", list.first())
        assertTrue("谢谢 now ranks before 这些", list.indexOf("谢谢") < list.indexOf("这些"))
    }

    @Test
    fun contextDisambiguatesSameCode_theFix() {
        val d = decoder()
        assertTrue(d.decodeCovered("4343", 30).firstOrNull()?.word != "哥哥")
        assertEquals("哥哥", d.decodeCovered("4343", 30, context = "大").firstOrNull()?.word)
    }

    @Test
    fun emptyContextIsIdentity() {
        val d = decoder()
        val none = d.decodeCovered("4343", 30).map { it.word }
        val empty = d.decodeCovered("4343", 30, context = "").map { it.word }
        val punct = d.decodeCovered("4343", 30, context = "你好。").map { it.word }
        assertEquals(none, empty)
        assertEquals(none, punct)
        assertTrue(none.isNotEmpty())
    }

    @Test
    fun contextDoesNotOverflipWhenFreqWordIsCorrect() {
        assertEquals("这些", decoder().decodeCovered("943943", 30, context = "我喜欢").firstOrNull()?.word)
    }
}
