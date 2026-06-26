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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * T9 decoding over the digit-keyed dict. Inputs are phone digits (a/b/c=2 ... w/x/y/z=9):
 *   nihao=64426, ceshi=23744, woshizhongguoren=9674494664486736.
 */
class T9DecoderTest {

    private val t9File = File("src/main/assets/aegis_t9.bin")

    private fun decoder(): PinyinDecoder {
        assumeTrue("T9 dict asset present", t9File.exists())
        return PinyinDecoder(BinaryDict.fromFile(t9File))
    }

    @Test
    fun decodesT9() {
        val d = decoder()
        // short + highly ambiguous: expected word must be present among candidates
        assertTrue("64426 -> 你好", d.decode("64426", 30).contains("你好"))
        assertTrue("23744 -> 测试", d.decode("23744", 30).contains("测试"))
        // longer input disambiguates: best full decode should be the sentence
        assertEquals("我是中国人", d.decode("9674494664486736", 30).firstOrNull())
    }
}
