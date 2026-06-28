// SPDX-License-Identifier: GPL-3.0-only
package com.aegis.ime.ime

import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class Debug12InputCoreTest {

    private val ctx = RuntimeEnvironment.getApplication()
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
        fun select(start: Int, end: Int) { selStart = start; selEnd = end; cursor = end }
        private fun clearSel() { selStart = -1; selEnd = -1 }
    }

    private fun engine(): DictEngine? {
        val p = File("src/main/assets/aegis_dict.bin")
        val t = File("src/main/assets/aegis_t9.bin")
        val l = File("src/main/assets/aegis_lm.bin")
        if (!p.exists() || !t.exists() || !l.exists()) return null
        return DictEngine(BinaryDict.fromFile(p), BinaryDict.fromFile(t), CharBigramLM.fromFile(l))
    }

    private val emptyEngine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    private fun digit(d: Char) = Key(d.toString(), output = d.toString())

    private fun leftColumnHasNoPunctuation(c: KeyboardController): Boolean =
        c.nineLeftColumn().all { it.action == KeyAction.PICK_READING }

    @Test fun locking_every_syllable_keeps_the_strip_rich_and_never_commits_nor_shows_punctuation() {
        val eng = engine(); assumeTrue("dict assets present", eng != null)
        val host = Host()
        val iv = InputView(ctx)
        val c = KeyboardController(host, eng!!)
        c.attachView(iv)
        c.onKey(Key("", action = KeyAction.SWITCH_NINE))
        digits.forEach { c.onKey(digit(it)) }

        val unlocked = iv.shownCandidateCount()
        assertTrue("strip rich before any lock, was $unlocked", unlocked >= 10)
        assertTrue("left column shows readings, not punctuation", leftColumnHasNoPunctuation(c))

        for (r in listOf("jiu", "jian", "zuo", "ce")) {
            val idx = c.expandedReadings().indexOf(r)
            assertTrue("'$r' offered in the left column, was ${c.expandedReadings()}", idx >= 0)
            c.onPickReadingIndex(idx)
            assertTrue(
                "strip stays rich after locking '$r', was ${iv.shownCandidateCount()}",
                iv.shownCandidateCount() >= 10,
            )
            assertTrue("locking '$r' must not commit, commits=${host.commits}", host.commits.isEmpty())
            assertTrue("no punctuation in the left column after locking '$r'", leftColumnHasNoPunctuation(c))
        }
        assertTrue("left column persists after locking every syllable", c.expandedReadings().isNotEmpty())
        assertTrue("the persisted column still offers the last syllable 'ce', was ${c.expandedReadings()}", "ce" in c.expandedReadings())
        assertTrue("the persisted column is never punctuation", leftColumnHasNoPunctuation(c))
        assertTrue("strip still rich with everything locked, was ${iv.shownCandidateCount()}", iv.shownCandidateCount() >= 10)
        assertTrue("still nothing committed to the editor", host.commits.isEmpty())
    }

    @Test fun partial_pick_builds_a_prefix_without_committing_then_completes_in_one_commit() {
        val eng = engine(); assumeTrue("dict assets present", eng != null)
        val host = Host()
        val c = KeyboardController(host, eng!!)
        c.onKey(Key("", action = KeyAction.SWITCH_NINE))
        digits.forEach { c.onKey(digit(it)) }

        val partialIdx = c.candidateWords().indexOfFirst { it.length == 1 }
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

    @Test fun backspace_with_a_selection_deletes_the_selection_not_the_char_before() {
        val host = Host()
        val c = KeyboardController(host, emptyEngine)
        host.sb.append("abcXYZdef")
        host.select(3, 6)

        c.onKey(Key("", action = KeyAction.BACKSPACE))

        assertTrue("deleteSelection was used, ops=${host.ops}", "deleteSelection" in host.ops)
        assertFalse("deleteBackward must NOT run with a selection active", "deleteBackward" in host.ops)
        assertEquals("the selected span is gone, nothing before it touched", "abcdef", host.sb.toString())
    }
}
