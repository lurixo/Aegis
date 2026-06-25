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

package com.aegis.ime.ime

import com.aegis.ime.decoder.FullDictTestAssets
import com.aegis.ime.decoder.PinyinDecoder
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardLossMatrixTest {

    private val assets = FullDictTestAssets.directory
    private fun assetsPresent() = FullDictTestAssets.available(
        File(assets, FullDictTestAssets.DICT),
        File(assets, FullDictTestAssets.T9),
        File(assets, FullDictTestAssets.LM),
        File(assets, FullDictTestAssets.JIANPIN),
    )

    @Test fun aDeclaredBoundaryCutExcludesCrossBoundaryCompletions() {
        assumeTrue(assetsPresent())
        val dec = PinyinDecoder(
            BinaryDict.fromFile(File(assets, FullDictTestAssets.DICT)),
            CharBigramLM.fromFile(File(assets, FullDictTestAssets.LM)),
            initialsDict = BinaryDict.fromFile(File(assets, FullDictTestAssets.JIANPIN)),
        )
        val noCut = dec.decodeCovered("nihao", 30).map { it.word }
        val withCut = dec.decodeCovered("nihao", 30, setOf(2)).map { it.word }
        assertTrue("no-cut decode floods with cross-boundary 你好X completions", noCut.any { it.length >= 3 && it.startsWith("你好") })
        assertTrue("the boundary cut excludes EVERY 你好X cross-boundary completion", withCut.none { it.length >= 3 && it.startsWith("你好") })
        assertTrue("…but keeps the in-boundary 你好", "你好" in withCut)
        assertTrue("…and keeps the leading single 你", "你" in withCut)
    }
}
