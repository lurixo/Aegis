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
import com.aegis.ime.engine.DictEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PinyinDecoderFuzzyHotToggleTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")

    @Test
    fun decoder_setFuzzyRules_changes_results_live() {
        assumeTrue("demo dict asset present", dictFile.exists())
        val decoder = PinyinDecoder(BinaryDict.fromFile(dictFile))

        assertTrue("correct spelling reaches 中国", decoder.decode("zhongguo", 30).contains("中国"))
        assertFalse("fuzzy off: zongguo must NOT reach 中国", decoder.decode("zongguo", 30).contains("中国"))

        decoder.setFuzzyRules(setOf("zh"))
        assertTrue("fuzzy on: zongguo now reaches 中国", decoder.decode("zongguo", 30).contains("中国"))

        decoder.setFuzzyRules(emptySet())
        assertFalse("fuzzy off again: zongguo loses 中国", decoder.decode("zongguo", 30).contains("中国"))
    }

    @Test
    fun engine_setFuzzyRules_forwards_to_the_26key_decoder() {
        assumeTrue("demo dict asset present", dictFile.exists())
        val dict = BinaryDict.fromFile(dictFile)
        val engine = DictEngine(pinyinDict = dict, t9Dict = null, lm = null)

        assertFalse("fuzzy off: zongguo not 中国", engine.candidates("zongguo", t9 = false).contains("中国"))
        engine.setFuzzyRules(setOf("zh"))
        assertTrue("after engine.setFuzzyRules: zongguo reaches 中国", engine.candidates("zongguo", t9 = false).contains("中国"))
    }
}
