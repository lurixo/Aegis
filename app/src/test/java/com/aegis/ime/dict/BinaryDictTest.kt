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

package com.aegis.ime.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class BinaryDictTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")

    @Test
    fun looksUpCommonWords() {
        assumeTrue("demo dict asset present", dictFile.exists())
        val dict = BinaryDict.fromFile(dictFile)

        assertTrue("nihao -> 你好", dict.query("nihao", 30).contains("你好"))
        assertTrue("zhongguo -> 中国", dict.query("zhongguo", 30).contains("中国"))
        assertTrue("ceshi -> 测试", dict.query("ceshi", 30).contains("测试"))
        assertTrue("shuru -> 输入", dict.query("shuru", 30).contains("输入"))

        val a = dict.query("a", 30)
        assertTrue("a -> 啊", a.contains("啊"))
        assertTrue("啊 before 阿", a.indexOf("啊") < a.indexOf("阿"))

        assertTrue("prefix bei non-empty", dict.query("bei", 30).isNotEmpty())

        assertTrue("limit honored", dict.query("a", 2).size <= 2)
        assertEquals("empty -> none", emptyList<String>(), dict.query("", 30))
    }
}
