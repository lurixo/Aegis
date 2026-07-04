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

/** The user boost should re-rank a previously-chosen homograph to the top. */
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
        val target = base[1] // a non-default candidate for "shi"

        val um = UserModel()
        repeat(200) { um.record(null, target, it.toLong()) }

        val withUser = PinyinDecoder(dict, lm, userModel = um).decode("shi", 5)
        assertEquals("user-preferred word ranks first", target, withUser.firstOrNull())
    }

    /**
     * ③ frequency curve: the strengthened boost makes a repeatedly chosen same-reading homophone rise within
     * a HANDFUL of uses (perceptible), not the ~200 the original unit weight needed. Measured on the real dict:
     * find the fewest uses that lift a non-default candidate to the front, and assert it is well under the old
     * regime. Cold start (no user model) is untouched — the boost is 0 for an unseen word.
     */
    @Test
    fun boostIsPerceptibleWithinAFewUses() {
        assumeTrue(dictFile.exists() && lmFile.exists())
        val dict = BinaryDict.fromFile(dictFile)
        val lm = CharBigramLM.fromFile(lmFile)
        val base = PinyinDecoder(dict, lm).decode("shi", 5)
        assumeTrue("need >=2 candidates", base.size >= 2)
        val target = base[1]

        // Search well past the "handful" bound: if the curve had regressed to the old unit weight the target
        // would need ~200 uses and would NOT be found within this range, so the assertion is not tautological.
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
