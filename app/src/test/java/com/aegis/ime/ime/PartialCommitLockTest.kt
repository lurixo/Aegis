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

import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * debug.14 BUG2: on the 9-key, after the user LOCKS the syllable readings (you'gai'lv'chu'xian) and then
  * Chinese IME behavior note.
 * survive — the remaining preedit stays "gai'lv'chu'xian", not a fresh T9-default re-decode "hai'lu'chu'xiao".
 */
class PartialCommitLockTest {

    private class Host : ImeHost {
        val sb = StringBuilder()
        override fun commitText(text: CharSequence) { sb.append(text) }
        override fun deleteBackward() { if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1) }
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = sb.takeLast(n)
    }

    private val assets = "src/main/assets/"
    private fun realEngine(): DictEngine = DictEngine(
        BinaryDict.fromFile(File(assets + "aegis_dict.bin")),
        BinaryDict.fromFile(File(assets + "aegis_t9.bin")),
        CharBigramLM.fromFile(File(assets + "aegis_lm.bin")),
        UserModel(),
        emptySet(),
        BinaryDict.fromFile(File(assets + "aegis_jianpin.bin")),
        null,
    )

    private fun assetsPresent() =
        File(assets + "aegis_t9.bin").exists() && File(assets + "aegis_lm.bin").exists()

    private fun nineController(): KeyboardController {
        val c = KeyboardController(Host(), realEngine())
        c.setCnDefaultLayout(LayoutId.NINE)
        c.reset()
        return c
    }

    private fun alphaController(): KeyboardController {
        val c = KeyboardController(Host(), realEngine())
        c.setCnDefaultLayout(LayoutId.ALPHA)
        c.reset()
        return c
    }

    /** Lock the active syllable to [reading] via the left column (must be offered there). */
    private fun lock(c: KeyboardController, reading: String) {
        val idx = c.expandedReadings().indexOf(reading)
        assertTrue("9-key column should offer '$reading' (got ${c.expandedReadings()})", idx >= 0)
        c.onPickReadingIndex(idx)
    }

    @Test
    fun partialCommit_keepsTheRemainingLockedSegmentation() {
        assumeTrue("T9 dict + LM assets present", assetsPresent())
        val c = nineController()
        // Type the digits for you'gai'lv'chu'xian, then LOCK each reading so the preedit is the user's choice
        // (the bare T9 default would be you'hai'lu'chu'xiao).
        T9Pinyin.toT9("yougailvchuxian").forEach { c.onKey(Key(it.toString(), output = it.toString())) }
        listOf("you", "gai", "lv", "chu", "xian").forEach { lock(c, it) }
        assertEquals("locked preedit", "you'gai'lv'chu'xian", c.preeditForTest())

        // Chinese IME behavior note.
        val yi = c.candidateWords().indexOf("有")
        assumeTrue("有 offered as a candidate for the locked buffer", yi >= 0)
        c.onPickCandidate(yi)

        // Chinese IME behavior note.
        assertEquals("有", c.composingPrefix())
        assertEquals("remaining stays the locked gai'lv'chu'xian (not hai'lu'chu'xiao)",
            "有gai'lv'chu'xian", c.preeditForTest())
    }

    /**
      * Chinese IME behavior note.
      * Chinese IME behavior note.
     * T9 re-decode), so the remainder stays gai'lv'chu'xian by construction. This test proves the drill path
     * is covered — the remaining letter segmentation is preserved, never the T9-default hai/lu/xiao.
     */
    @Test
    fun drillPartialCommit_26key_keepsTheRemainingLetterSegmentation() {
        assumeTrue("26-key dict + LM assets present",
            File(assets + "aegis_dict.bin").exists() && File(assets + "aegis_lm.bin").exists())
        val c = alphaController()
        "yougailvchuxian".forEach { c.onKey(Key(it.toString(), output = it.toString())) }
        // The 26-key expand column advances one unresolved syllable at a time.
        assertEquals(listOf("you"), c.expandedReadings())
        c.onPickReadingIndex(0)
        assertEquals("drilled into syllable 0", 0, c.drilledSyllableForTest())

        // Chinese IME behavior note.
        val yi = c.candidateWords().indexOf("有")
        assumeTrue("有 offered as a 同音字 of the drilled syllable", yi >= 0)
        c.onPickCandidate(yi)

        assertEquals("有", c.composingPrefix())
        // Chinese IME behavior note.
        assertEquals("有gailvchuxian", c.preeditForTest())
        assertEquals("remaining segmentation preserved", listOf("gai"), c.expandedReadings())
    }
}
