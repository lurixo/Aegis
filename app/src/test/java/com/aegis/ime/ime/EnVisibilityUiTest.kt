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

import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.dict.OctagramReader
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnVisibilityUiTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val assets = File("src/main/assets")

    private class Host : ImeHost {
        override fun commitText(text: CharSequence) {}
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    private fun engine(dir: File, gram: OctagramReader?) = DictEngine(
        BinaryDict.fromFile(File(dir, "aegis_dict.bin")),
        BinaryDict.fromFile(File(dir, "aegis_t9.bin")),
        CharBigramLM.fromFile(File(assets, "aegis_lm.bin")),
        initialsDict = BinaryDict.fromFile(File(dir, "aegis_jianpin.bin")),
        octagram = gram,
    )
    private fun controller(dir: File, gram: OctagramReader?): KeyboardController =
        KeyboardController(Host(), engine(dir, gram)).apply { attachView(InputView(ctx)) }
    private fun type(c: KeyboardController, s: String) = s.forEach { c.onKey(Key(it.toString(), output = it.toString())) }
    private fun pick(c: KeyboardController, reading: String) =
        c.onKey(Key(reading, output = reading, action = KeyAction.PICK_READING))

    private fun assertStripOffers嗯(dir: File, gram: OctagramReader?, cfg: String) {
        assumeTrue("LM present", File(assets, "aegis_lm.bin").exists())
        val bad = ArrayList<String>()
        fun check(tag: String, words: List<String>, rankedAfter恩: Boolean = true) {
            val i = words.indexOf("嗯")
            if (i < 0) { bad.add("$tag: 嗯 missing, first12=${words.take(12)}"); return }
            if (rankedAfter恩 && words.indexOf("恩") !in 0 until i) {
                bad.add("$tag: 嗯@$i not after 恩@${words.indexOf("恩")}")
            }
        }
        run {
            val c = controller(dir, gram)
            c.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
            type(c, "en")
            check("$cfg 26k en", c.candidateWords())
        }
        run {
            val c = controller(dir, gram)
            c.onKey(Key("", action = KeyAction.SWITCH_NINE))
            type(c, "36")
            check("$cfg 9k 36", c.candidateWords())
            val col = c.expandedReadings()
            if ("en" !in col) bad.add("$cfg 9k readings lost en: $col")
            if ("ng" in col) bad.add("$cfg 9k readings gained ng: $col")
        }
        run {
            val c = controller(dir, gram)
            c.onKey(Key("", action = KeyAction.SWITCH_NINE))
            type(c, "36")
            pick(c, "en")
            check("$cfg 9k lock(en)", c.candidateWords())
        }
        run {
            val c = controller(dir, gram)
            c.onKey(Key("", action = KeyAction.SWITCH_NINE))
            type(c, "64")
            check("$cfg 9k 64(ng)", c.candidateWords(), rankedAfter恩 = false)
        }
        assertTrue("strip en-visibility failures: $bad", bad.isEmpty())
    }

    @Test fun seed_stripOffers嗯_bothLayouts() {
        assumeTrue("assets present", File(assets, "aegis_dict.bin").exists())
        assertStripOffers嗯(assets, gram = null, cfg = "seed")
    }

    @Test fun fullConfig_stripOffers嗯_bothLayouts() {
        val dir = System.getenv("AEGIS_FULLDICT_DIR")
        assumeTrue("full-dict check only when AEGIS_FULLDICT_DIR is set", !dir.isNullOrEmpty())
        val gramPath = System.getenv("AEGIS_GRAM")
        val gram = if (!gramPath.isNullOrEmpty() && File(gramPath).exists())
            OctagramReader.fromFile(File(gramPath)) else null
        assertStripOffers嗯(File(dir!!), gram, cfg = "full")
    }
}
