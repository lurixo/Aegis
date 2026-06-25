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

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class ReleaseDictionaryBuildToolTest {

    @Test
    fun retiredDictionaryBuildersStayOutsideTheToolDistribution() {
        val tools = File("../tools/src/main/kotlin/com/aegis/tools")
        val entrypoint = File(tools, "DictBuilder.kt").readText()
        val pinyin = File(tools, "Pinyin.kt").readText()
        val t2s = File(tools, "T2SMerge.kt").readText()

        assertFalse(File(tools, "PrefixIndexBuilder.kt").exists())
        assertFalse(File(tools, "EnBuilder.kt").exists())
        assertFalse(File("../tools/wanxiang-coverage.txt").exists())
        assertFalse(entrypoint.contains("prefix-index"))
        assertFalse(entrypoint.contains("EnBuilder"))
        assertFalse(pinyin.contains("fuzzyNormalize"))
        assertFalse(t2s.contains("val rejection: T2SReject?"))
    }
}
