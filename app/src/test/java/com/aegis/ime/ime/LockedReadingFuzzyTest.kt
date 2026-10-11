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
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockedReadingFuzzyTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private class Host : ImeHost {
        override fun commitText(text: CharSequence) {}
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    private val rows = listOf(
        EngineFixture.Row("zong", "总", 800),
        EngineFixture.Row("guo", "过", 800),
        EngineFixture.Row("zhong", "中", 800),
        EngineFixture.Row("guo", "国", 700),
        EngineFixture.Row("zhongguo", "中国", 900),
    )

    private fun engine(): DictEngine = DictEngine(
        EngineFixture.build(rows),
        EngineFixture.build(rows.map { it.copy(key = T9Pinyin.toT9(it.key)) }),
        null,
        fuzzyRules = setOf("zh"),
    )

    private fun out(s: String) = Key(s, output = s)
    private fun pick(reading: String) = Key(reading, output = reading, action = KeyAction.PICK_READING)

    @Test fun locking_a_nine_key_reading_keeps_the_fuzzy_word() {
        val c = KeyboardController(Host(), engine())
        c.attachView(InputView(ctx))
        c.switchTextLayoutForTest(nine = true)
        "9664486".forEach { c.onKey(out(it.toString())) }
        assertTrue("before the lock: ${c.candidateWords()}", "中国" in c.candidateWords())

        c.onKey(pick("zong"))

        assertEquals("zong'guo", c.preeditForTest())
        assertTrue("after the lock: ${c.candidateWords()}", "中国" in c.candidateWords())
    }
}
