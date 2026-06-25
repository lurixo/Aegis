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

import com.aegis.ime.decoder.EngineFixture
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class UserDictEndToEndTest {

    private val rows = listOf(
        EngineFixture.Row("ci", "次", 900), EngineFixture.Row("ci", "此", 850), EngineFixture.Row("ci", "词", 800),
        EngineFixture.Row("shi", "是", 950), EngineFixture.Row("shi", "时", 920), EngineFixture.Row("shi", "试", 680),
        EngineFixture.Row("ku", "库", 900), EngineFixture.Row("ku", "哭", 800),
        EngineFixture.Row("hao", "好", 950),
        EngineFixture.Row("ciku", "词库", 850),
    )
    private fun letterDict(): BinaryDict = EngineFixture.build(rows)
    private fun t9Dict(): BinaryDict = EngineFixture.build(rows.map { EngineFixture.Row(T9Pinyin.toT9(it.key), it.word, it.freq) })

    private val lmFile = java.io.File("src/main/assets/aegis_lm.bin")
    private val lm: com.aegis.ime.dict.CharBigramLM by lazy { com.aegis.ime.dict.CharBigramLM.fromFile(lmFile) }

    @org.junit.Before fun requireLm() {
        org.junit.Assume.assumeTrue("real LM asset present (production engine wiring)", lmFile.exists())
    }

    private fun engine(um: UserModel) = DictEngine(letterDict(), t9Dict(), lm, um)

    @Test fun same_word_only_and_same_reading_only_remain_distinct_learning_records() {
        val um = UserModel()
        val candidateEngine = engine(um)
        candidateEngine.learnWord("CI'KU", "词库", assembled = true)
        candidateEngine.learnWord("ci'gui", "词库", assembled = true)
        candidateEngine.learnWord("CI-KU", "此库", assembled = true)
        assertEquals(listOf("词库"), um.readingSnapshot()["cigui"])
        assertEquals(setOf("词库", "此库"), um.readingSnapshot().getValue("ciku").toSet())
    }
}
