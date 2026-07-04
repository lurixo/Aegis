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

class UserAdaptTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")

    @Test
    fun userBoostReranks() {
        assumeTrue(dictFile.exists() && lmFile.exists())
        val dict = BinaryDict.fromFile(dictFile)
        val lm = CharBigramLM.fromFile(lmFile)

        val base = PinyinDecoder(dict, lm).decode("shi", 5)
        assumeTrue("need >=2 candidates", base.size >= 2)
        val target = base[1]

        val um = UserModel()
        repeat(200) { um.record(null, target, it.toLong()) }

        val withUser = PinyinDecoder(dict, lm, userModel = um).decode("shi", 5)
        assertEquals("user-preferred word ranks first", target, withUser.firstOrNull())
    }

    @Test
    fun boostIsPerceptibleWithinAFewUses() {
        assumeTrue(dictFile.exists() && lmFile.exists())
        val dict = BinaryDict.fromFile(dictFile)
        val lm = CharBigramLM.fromFile(lmFile)
        val base = PinyinDecoder(dict, lm).decode("shi", 5)
        assumeTrue("need >=2 candidates", base.size >= 2)
        val target = base[1]

        var uses = -1
        for (n in 1..100) {
            val um = UserModel()
            repeat(n) { um.record(null, target, it.toLong()) }
            if (PinyinDecoder(dict, lm, userModel = um).decode("shi", 5).firstOrNull() == target) { uses = n; break }
        }
        assertTrue("a chosen homophone should reach the front within a few dozen uses (got $uses), far under the old ~200",
            uses in 1..40)
    }
}
