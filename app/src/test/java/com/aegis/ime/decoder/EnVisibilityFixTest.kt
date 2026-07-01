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

import com.aegis.ime.dict.CharBigramLM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class EnVisibilityFixTest {

    private val assets = File("src/main/assets")
    private val lmFile = File(assets, "aegis_lm.bin")

    @Test fun aliasMap_isExactlyEnToNg() {
        assertEquals(mapOf("en" to listOf("ng")), PinyinDecoder.INPUT_ALIASES)
        assertEquals(mapOf("36" to listOf("ng")), PinyinDecoder.T9_INPUT_ALIASES)
    }

    @Test fun syntheticFullExactLayer_aliasStillReachesLatticeAndList() {
        assumeTrue("lm asset present (edgeN = EDGE_N needs an LM)", lmFile.exists())
        val rows = ArrayList<EngineFixture.Row>()
        rows.add(EngineFixture.Row("en", "恩", 900))
        for (i in 0 until 24) rows.add(EngineFixture.Row("en", EngineFixture.supplementary(300 + i), 1))
        rows.add(EngineFixture.Row("ng", "嗯", 800))
        val dict = EngineFixture.build(rows)
        val d = PinyinDecoder(dict, CharBigramLM.fromFile(lmFile))

        val m = PinyinDecoder::class.java.getDeclaredMethod("edgesFor", String::class.java)
        m.isAccessible = true
        val edges = (m.invoke(d, "en") as List<*>).map { e ->
            e!!.javaClass.getDeclaredField("word").apply { isAccessible = true }.get(e) as String
        }
        assertTrue("alias word must reach the lattice past a full exact layer (edges=${edges.size})", "嗯" in edges)

        val words = d.decodeCovered("en", 30).map { it.word }
        val i = words.indexOf("嗯")
        assertTrue("嗯 must survive the completion budget: ${words.take(8)}", i >= 0)
        assertTrue("嗯 ranks under the top native but above the freq-1 tail: $i", words.indexOf("恩") < i && i <= 4)
    }
}
