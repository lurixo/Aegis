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
import org.junit.Test
import java.io.File

class UserAdaptTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")

    @Test
    fun userBoostReranks() {
        assertTrue(dictFile.exists() && lmFile.exists())
        val dict = BinaryDict.fromFile(dictFile)
        val lm = CharBigramLM.fromFile(lmFile)

        val base = PinyinDecoder(dict, lm).decodeCovered("shi", 30).map { it.word }
        val homophones = base.filter { it.codePointCount(0, it.length) == 1 }
        assertTrue("need >=2 homophones", homophones.size >= 2)
        val target = homophones[1]

        val um = UserModel()
        repeat(200) { um.record(null, target, it.toLong()) }

        val withUser = PinyinDecoder(dict, lm, userModel = um).decodeCovered("shi", 30).map { it.word }
        assertEquals("user-preferred word ranks first", target, withUser.firstOrNull())
    }

    @Test
    fun boostIsPerceptibleWithinAFewUses() {
        assertTrue(dictFile.exists() && lmFile.exists())
        val dict = BinaryDict.fromFile(dictFile)
        val lm = CharBigramLM.fromFile(lmFile)
        val base = PinyinDecoder(dict, lm).decodeCovered("shi", 30).map { it.word }
        val homophones = base.filter { it.codePointCount(0, it.length) == 1 }
        assertTrue("need >=2 homophones", homophones.size >= 2)
        val target = homophones[1]

        var uses = -1
        for (n in 1..100) {
            val um = UserModel()
            repeat(n) { um.record(null, target, it.toLong()) }
            if (PinyinDecoder(dict, lm, userModel = um).decodeCovered("shi", 30).firstOrNull()?.word == target) { uses = n; break }
        }
        assertTrue("a chosen homophone should reach the front within a few dozen uses (got $uses), far under the old ~200",
            uses in 1..40)
    }
}
