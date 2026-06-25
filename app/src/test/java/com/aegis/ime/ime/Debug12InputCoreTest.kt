// SPDX-License-Identifier: GPL-3.0-only
package com.aegis.ime.ime

import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Debug12InputCoreTest {

    private val digits = "548542698623"

    private class Host : ImeHost {
        val sb = StringBuilder()
        var cursor = 0
        var selStart = -1
        var selEnd = -1
        val commits = mutableListOf<String>()
        val ops = mutableListOf<String>()
        override fun commitText(text: CharSequence) {
            commits.add(text.toString())
            if (hasSelection()) { sb.delete(selStart, selEnd); cursor = selStart; clearSel() }
            sb.insert(cursor, text); cursor += text.length
        }
        override fun deleteBackward() {
            ops.add("deleteBackward")
            val at = if (hasSelection()) selStart else cursor
            if (at > 0) { sb.deleteCharAt(at - 1); cursor = at - 1 }
            clearSel()
        }
        override fun deleteSelection() {
            ops.add("deleteSelection")
            if (hasSelection()) { sb.delete(selStart, selEnd); cursor = selStart; clearSel() }
        }
        override fun performEnter() {}
        override fun hasSelection(): Boolean = selStart in 0 until selEnd
        override fun textBeforeCursor(n: Int): CharSequence {
            val end = if (hasSelection()) selStart else cursor
            return sb.substring(maxOf(0, end - n), end)
        }
        private fun clearSel() { selStart = -1; selEnd = -1 }
    }

    private fun engine(): DictEngine? {
        val p = File("src/main/assets/aegis_dict.bin")
        val t = File("src/main/assets/aegis_t9.bin")
        val l = File("src/main/assets/aegis_lm.bin")
        if (!p.exists() || !t.exists() || !l.exists()) return null
        return DictEngine(BinaryDict.fromFile(p), BinaryDict.fromFile(t), CharBigramLM.fromFile(l))
    }

    private fun digit(d: Char) = Key(d.toString(), output = d.toString())
    private fun isSingleChar(word: String): Boolean = word.codePointCount(0, word.length) == 1

    @Test fun partial_pick_builds_a_prefix_without_committing_then_completes_in_one_commit() {
        val eng = engine(); assumeTrue("dict assets present", eng != null)
        val host = Host()
        val c = KeyboardController(host, eng!!)
        c.switchTextLayoutForTest(nine = true)
        digits.forEach { c.onKey(digit(it)) }

        val partialIdx = c.candidateWords().indexOfFirst { isSingleChar(it) }
        assertTrue("a single-char partial candidate is offered, was ${c.candidateWords().take(8)}", partialIdx >= 0)
        val firstChar = c.candidateWords()[partialIdx]
        c.onPickCandidate(partialIdx)

        assertTrue("a partial pick must NOT commit to the editor, commits=${host.commits}", host.commits.isEmpty())
        assertEquals("the pick is held as the assembled prefix", firstChar, c.composingPrefix())
        assertTrue(
            "the prefix renders at the strip's leftmost, was '${c.preeditForTest()}'",
            c.preeditForTest().startsWith(firstChar),
        )

        c.onKey(Key("", action = KeyAction.ENTER))
        assertEquals("the whole word lands in one commit, commits=${host.commits}", 1, host.commits.size)
        assertTrue("the single commit begins with the confirmed prefix", host.commits[0].startsWith(firstChar))
    }
}
