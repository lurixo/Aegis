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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssembledCandidateOrderTest {

    private fun words(cands: List<Cand>) = cands.map { it.word }

    @Test fun aGluedSentenceNeverLeadsTheDictionaryWordOfTheSameReading() {
        val rows = listOf(
            EngineFixture.Row("diu", "丢", 900),
            EngineFixture.Row("zi", "字", 900),
            EngineFixture.Row("zi", "子", 800),
            EngineFixture.Row("diuzi", "丢子", 40),
        )
        val decoder = PinyinDecoder(EngineFixture.build(rows))
        for ((path, got) in listOf(
            "free" to words(decoder.decodeCovered("diuzi", 30)),
            "cut" to words(decoder.decodeCovered("diuzi", 30, setOf(3))),
            "locked" to words(decoder.decodeCoveredAtomic("diuzi", 30, setOf(3))),
        )) {
            assertEquals("$path: the dictionary word of the reading leads, was $got", "丢子", got.first())
            val at = got.indexOf("丢字")
            assertTrue("$path: the glued sentence stays reachable behind it, was at $at", at > 0)
        }
    }
}
