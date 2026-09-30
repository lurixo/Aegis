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

import com.aegis.ime.dict.OctagramFixture
import org.junit.Assert.assertEquals
import org.junit.Test

class GrammarAbbreviationTest {

    private val dict = EngineFixture.build(
        listOf(
            EngineFixture.Row("xiang", "乡", 100),
            EngineFixture.Row("zhen", "镇", 100),
        ),
    )

    private val initials = EngineFixture.build(
        listOf(
            EngineFixture.Row("xiangzhen", "想着你", 1000),
            EngineFixture.Row("xiangzh", "想着", 1000),
        ),
    )

    private val decoder = PinyinDecoder(
        dict,
        initialsDict = initials,
        octagram = OctagramFixture.reader(mapOf("无关" to 10.0)),
    )

    @Test fun grammarSentencesSkipAbbreviationsWhenFullSyllablesSpellTheInput() {
        assertEquals("乡镇", decoder.decodeCovered("xiangzhen", 30).first().word)
    }

    @Test fun grammarSentencesKeepAbbreviationsWhenTheInputNeedsThem() {
        assertEquals("想着", decoder.decodeCovered("xiangzh", 30).first().word)
    }
}
