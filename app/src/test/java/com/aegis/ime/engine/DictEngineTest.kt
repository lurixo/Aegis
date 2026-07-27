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

import com.aegis.ime.decoder.Cand
import com.aegis.ime.decoder.EngineFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictEngineTest {

    @Test
    fun empty_engine_reports_no_chinese_support() {
        assertFalse(DictEngine(null, null, null).supportsChinese)
    }

    @Test
    fun empty_engine_returns_no_candidates_and_never_throws() {
        val engine: CandidateEngine = DictEngine(null, null, null)
        assertEquals(emptyList<Cand>(), engine.candidatesCovered("nihao", false))
        assertEquals(emptyList<Cand>(), engine.candidatesCovered("236", true))
        assertEquals(emptyList<Cand>(), engine.candidatesForLockedReadingCovered("nihao"))
        assertEquals(emptyList<String>(), engine.candidates("nihao", false))
    }

    @Test
    fun a_language_model_alone_does_not_unlock_chinese() {
        val lm = EngineFixture.buildLm(mapOf('你'.code to 10L, '好'.code to 8L), emptyMap())
        val engine: CandidateEngine = DictEngine(null, null, lm)
        assertFalse(engine.supportsChinese)
        assertEquals(emptyList<Cand>(), engine.candidatesForLockedReadingCovered("nihao"))
        assertTrue(engine.candidatesCovered("ni", false).isEmpty())
    }

    @Test
    fun a_pinyin_dictionary_unlocks_chinese_support() {
        val dict = EngineFixture.build(listOf(EngineFixture.Row("ni", "你", 900)))
        assertTrue(DictEngine(dict, null, null).supportsChinese)
    }

    @Test
    fun a_t9_dictionary_unlocks_chinese_support() {
        val dict = EngineFixture.build(listOf(EngineFixture.Row("ni", "你", 900)))
        assertTrue(DictEngine(null, dict, null).supportsChinese)
    }
}
