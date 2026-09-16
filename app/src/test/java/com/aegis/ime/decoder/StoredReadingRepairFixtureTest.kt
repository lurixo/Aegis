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
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredReadingRepairFixtureTest {

    private val clock = 1_700_000_000_000L

    private fun stored(rows: List<Pair<String, String>>) =
        UserModel { clock }.apply { rows.forEach { (r, w) -> recordWord(r, w, clock, incrementCount = true) } }

    private fun verdicts(dict: BinaryDict, model: UserModel): Map<Pair<String, String>, String?> =
        PinyinDecoder(dict, userModel = model).storedReadingRepairs(model.unmarkedReadings())
            .associate { (it.reading to it.word) to it.spelled }

    private fun repaired(dict: BinaryDict, model: UserModel): Boolean =
        model.repairReadings(PinyinDecoder(dict, userModel = model).storedReadingRepairs(model.unmarkedReadings()))

    @Test fun aReadingCutShortByPickingACompletionIsDropped() {
        val rows = listOf("bu" to "不是", "shi" to "实现", "jiu" to "九键", "xiang" to "想哭", "xianbu" to "现不是")
        val got = verdicts(EngineFixture.dict(), stored(rows))
        for (row in rows) {
            assertTrue("$row is dropped, verdicts were $got", row in got)
            assertEquals("$row is dropped rather than respelled", null, got[row])
        }
    }

    @Test fun readingsThatFitOrCannotBeTheLearnersAreKept() {
        val rows = listOf(
            "bushi" to "不是",
            "xian" to "西安",
            "jiujiu" to "旧酒",
            "bs" to "不是",
            "b" to "不是",
            "bu" to "不",
            "dcall" to "打call",
            "bi" to "词语",
        )
        assertEquals(emptyMap<Pair<String, String>, String?>(), verdicts(EngineFixture.dict(), stored(rows)))
    }

    private fun spelledOut(): BinaryDict = EngineFixture.build(
        listOf(
            EngineFixture.Row("bu", "不", 900),
            EngineFixture.Row("shi", "是", 900),
            EngineFixture.Row("shi", "试", 800),
            EngineFixture.Row("si", "四", 900),
            EngineFixture.Row("qi", "七", 900),
            EngineFixture.Row("bi", "比", 900),
            EngineFixture.Row("a", "啊", 900),
            EngineFixture.Row("aa", "啊啊", 800),
            EngineFixture.Row("ba", "吧", 900),
            EngineFixture.Row("ce", "测", 900),
            EngineFixture.Row("ci", "词", 900),
            EngineFixture.Row("ku", "库", 900),
            EngineFixture.Row("bushi", "不是", 800),
            EngineFixture.Row("bushiba", "不是吧", 700),
            EngineFixture.Row("ceshi", "测试", 800),
            EngineFixture.Row("ciku", "词库", 800),
        ),
    )

    @Test fun aNineKeyLetterGuessIsRespelledWithTheSameKeys() {
        val got = verdicts(spelledOut(), stored(listOf("biku" to "词库")))
        assertEquals(mapOf(("biku" to "词库") to "ciku"), got)
    }

    @Test fun aNineKeyLetterGuessThatWasAlsoCutShortIsDropped() {
        val rows = listOf("bi" to "词库", "buqi" to "不是", "aesi" to "测试")
        assertEquals(rows.associateWith { null }, verdicts(spelledOut(), stored(rows)))
    }

    @Test fun aReadingThatFitsUnderTheDefaultFuzzyRulesIsKeptThoughItsKeysLookCutShort() {
        val rows = listOf("busi" to "不是", "cesi" to "测试")
        assertEquals(emptyMap<Pair<String, String>, String?>(), verdicts(spelledOut(), stored(rows)))
    }

    @Test fun aFuzzyReadingOfTheWordsOpeningCharactersIsKept() {
        assertEquals(emptyMap<Pair<String, String>, String?>(), verdicts(spelledOut(), stored(listOf("busi" to "不是吧"))))
    }

    @Test fun aOneLetterReadingIsNeverTheLearnersAndIsKept() {
        assertEquals(emptyMap<Pair<String, String>, String?>(), verdicts(spelledOut(), stored(listOf("a" to "啊啊"))))
    }

    @Test fun theSameWordReadExactlyIsStillACutShortCompletion() {
        assertEquals(mapOf(("bushi" to "不是吧") to null), verdicts(spelledOut(), stored(listOf("bushi" to "不是吧"))))
    }

    @Test fun aPairAddedByHandIsNeverJudged() {
        val model = UserModel { clock }.apply {
            addManualWord("bu", "不是", clock)
            recordWord("jiu", "九键", clock, incrementCount = true)
        }
        assertTrue(repaired(EngineFixture.dict(), model))
        assertEquals(mapOf("bu" to listOf("不是")), model.readingSnapshot())
    }

    @Test fun aSecondPassFindsNothingLeftToRepair() {
        val model = stored(
            listOf("bu" to "不是", "biku" to "词库", "bi" to "词库", "bushi" to "不是", "cesi" to "测试", "busi" to "不是吧"),
        )
        assertTrue(repaired(spelledOut(), model))
        assertEquals(
            mapOf("ciku" to listOf("词库"), "bushi" to listOf("不是"), "cesi" to listOf("测试"), "busi" to listOf("不是吧")),
            model.readingSnapshot(),
        )
        val version = model.version
        assertFalse(repaired(spelledOut(), model))
        assertEquals(version, model.version)
    }
}
