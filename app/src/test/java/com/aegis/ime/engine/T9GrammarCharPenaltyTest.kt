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

package com.aegis.ime.engine

import com.aegis.ime.decoder.EngineFixture
import com.aegis.ime.dict.OctagramFixture
import org.junit.Assert.assertEquals
import org.junit.Test

class T9GrammarCharPenaltyTest {

    @Test fun nineKeyGrammarSentencesPayForEveryCharacter() {
        val letters = EngineFixture.build(
            listOf(
                EngineFixture.Row("xian", "先", 100),
                EngineFixture.Row("xi", "西", 100),
                EngineFixture.Row("an", "安", 100),
            ),
        )
        val nineKey = EngineFixture.build(
            listOf(
                EngineFixture.Row("9426", "先", 100),
                EngineFixture.Row("94", "西", 100),
                EngineFixture.Row("26", "安", 100),
            ),
        )
        val engine = DictEngine(letters, nineKey, null, octagram = OctagramFixture.reader(mapOf("西安" to 30.0)))
        assertEquals("西安", engine.candidatesCovered("xian", false).first().word)
        assertEquals("先", engine.candidatesCovered("9426", true).first().word)
    }
}
