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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineLockedFixTest {

    private val dict = EngineFixture.dict()
    private val d = PinyinDecoder(dict)

    private fun words(c: List<Cand>) = c.map { it.word }

    private fun locked(readings: List<String>): List<Cand> {
        val full = readings.joinToString("")
        val cuts = HashSet<Int>(); var acc = 0
        for (r in readings) { acc += r.length; if (acc < full.length) cuts.add(acc) }
        return d.decodeCovered(full, 30, cuts)
    }

    @Test fun oneUnitPrefixCompletionsUseTheBoundedTopIndexOnLiveDecodePaths() {
        val fanout = 300
        val letterRows = ArrayList<EngineFixture.Row>()
        for (i in 0 until fanout) {
            letterRows.add(EngineFixture.Row("sa${i.toString().padStart(3, '0')}", "低频字$i", 1))
        }
        letterRows.add(EngineFixture.Row("sz999", "高频词", 5000))
        val letterDict = EngineFixture.build(letterRows)
        val letterTop = letterDict.prefixByFreq("s", fanout + 10)
        assertTrue("one-letter prefix index must not materialize every matching key", letterTop.size < letterRows.size)
        assertEquals("高频词", letterTop.first().word)

        val letterDecoder = PinyinDecoder(letterDict)
        assertEquals("高频词", letterDecoder.decodeCovered("s", 1).single().word)

        val digitRows = ArrayList<EngineFixture.Row>()
        for (i in 0 until fanout) {
            digitRows.add(EngineFixture.Row("92${i.toString().padStart(3, '0')}", "低频九$i", 1))
        }
        digitRows.add(EngineFixture.Row("99", "高频九", 5000))
        val digitDict = EngineFixture.build(digitRows)
        val digitTop = digitDict.prefixByFreq("9", fanout + 10)
        assertTrue("one-digit prefix index must not materialize every matching key", digitTop.size < digitRows.size)
        assertEquals("高频九", digitTop.first().word)

        val digitDecoder = PinyinDecoder(digitDict)
        assertEquals("高频九", digitDecoder.decodeCovered("9", 1).single().word)
    }

    @Test fun shixian_surfacesShixianAsTheLeadingWord() {
        val w = words(locked(listOf("shi", "xian")))
        assertTrue("实现 in #1/#2", w.take(2).contains("实现"))
        assertFalse("no 西安", w.any { it.contains("西安") })
    }
}
