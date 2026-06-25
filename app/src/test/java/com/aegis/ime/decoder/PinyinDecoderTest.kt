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
import org.junit.Test
import java.io.File

class PinyinDecoderTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")

    private fun decoder(): PinyinDecoder {
        assertTrue("demo dict asset present", dictFile.exists())
        return PinyinDecoder(BinaryDict.fromFile(dictFile))
    }

    @Test
    fun decodesSentences() {
        val d = decoder()
        fun top(s: String) = d.decodeCovered(s, 30).firstOrNull()?.word
        assertEquals("测试", top("ceshi"))
        assertEquals("你好世界", top("nihaoshijie"))
        assertEquals("我是中国人", top("woshizhongguoren"))
        assertEquals("北京大学", top("beijingdaxue"))
        assertEquals("输入法", top("shurufa"))
    }

    @Test
    fun aLimitBeyondHalfTheIntRangeStillGrowsTheCandidateList() {
        val d = decoder()
        val at30 = d.decodeCovered("h", 30).map { it.word }
        val atMax = d.decodeCovered("h", Int.MAX_VALUE).map { it.word }

        assertTrue("26-key: a huge limit keeps every candidate the small limit shows", atMax.containsAll(at30))
        assertTrue("26-key: a huge limit offers more completions than the small limit", atMax.size > at30.size)
    }
}
