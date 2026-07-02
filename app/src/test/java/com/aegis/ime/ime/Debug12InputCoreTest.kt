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

/**
 * debug.12 input-core regression — the EXACT scenario (multi-syllable jiujianzuoce, real dict,
 * real [InputView]), NOT a happy-case. Locks the three confirmed bugs so they cannot return:
 *
  * Chinese IME behavior note.
 *        IME-internal confirmed prefix (shown at the strip's leftmost) and decoding continues; the whole
 *        word lands in ONE commit only when complete.
  * Chinese IME behavior note.
 *        readings (then empty once all are locked) — never fall back to punctuation while composing.
  * Chinese IME behavior note.
 *
 * jiujianzuoce digits: jiu=548 jian=5426 zuo=986 ce=23 → "548542698623".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Debug12InputCoreTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val digits = "548542698623"

    /** Editor model: records commits, tracks a selection, and distinguishes deleteSelection from a
     *  selection-start-relative deleteBackward so S2 data loss is observable. */
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
            ops.add("deleteBackward") // selection-start-relative: removes the char BEFORE the cursor/selStart
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

    /** F5: an engine that returns nothing — for the dict-independent S2 selection-backspace test so it need
     *  not be gated on optional dict assets (the controller ctor just requires a non-null engine). */
    private val emptyEngine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    private fun digit(d: Char) = Key(d.toString(), output = d.toString())
    private fun isSingleChar(word: String): Boolean = word.codePointCount(0, word.length) == 1

    /**
     *  (all syllables locked); real readings are PICK_READING; punctuation keys are not. */
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
            // The collapse = this dropping to 1. It must stay rich after EVERY lock.
            assertTrue(
                "strip stays rich after locking '$r', was ${iv.shownCandidateCount()}",
                iv.shownCandidateCount() >= 10,
            )
            // Locking a reading must NEVER commit anything to the editor.
            assertTrue("locking '$r' must not commit, commits=${host.commits}", host.commits.isEmpty())
            // The left column must keep showing readings (or be empty once all are locked) — never punctuation.
            assertTrue("no punctuation in the left column after locking '$r'", leftColumnHasNoPunctuation(c))
        }
        // UI-1 (debug.13): all four locked → the left column does NOT vanish (the old behaviour). It keeps
        // Chinese IME behavior note.
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

        // Pick a single-character candidate (a PARTIAL pick — it covers only the first syllable).
        val partialIdx = c.candidateWords().indexOfFirst { isSingleChar(it) }
        assertTrue("a single-char partial candidate is offered, was ${c.candidateWords().take(8)}", partialIdx >= 0)
        val firstChar = c.candidateWords()[partialIdx]
        c.onPickCandidate(partialIdx)

        // Chinese IME behavior note.
        assertTrue("a partial pick must NOT commit to the editor, commits=${host.commits}", host.commits.isEmpty())
        assertEquals("the pick is held as the assembled prefix", firstChar, c.composingPrefix())
        assertTrue(
            "the prefix renders at the strip's leftmost, was '${c.preeditForTest()}'",
            c.preeditForTest().startsWith(firstChar),
        )

        // Completing the word (flush) sends prefix + remainder to the editor in EXACTLY ONE commit.
        c.onKey(Key("", action = KeyAction.ENTER))
        assertEquals("the whole word lands in one commit, commits=${host.commits}", 1, host.commits.size)
        assertTrue("the single commit begins with the confirmed prefix", host.commits[0].startsWith(firstChar))
    }

    @Test fun backspace_with_a_selection_deletes_the_selection_not_the_char_before() {
        // F5 (debug.12): S2 is engine-independent (it never touches the dictionary), so it must NOT be gated
        // on optional dict assets — the old assumeTrue silently SKIPPED this data-loss regression on clean
        // checkouts / CI. Drive it with an empty engine so it ALWAYS runs.
        val host = Host()
        val c = KeyboardController(host, emptyEngine)
        host.sb.append("abcXYZdef")
        host.select(3, 6) // "XYZ" selected, nothing composing in the IME

        c.onKey(Key("", action = KeyAction.BACKSPACE))

        // S2: it must delete the SELECTION itself, leaving "abcdef" — NOT deleteBackward (which would be
        // selection-start-relative and eat 'c', leaving "abXYZdef").
        assertTrue("deleteSelection was used, ops=${host.ops}", "deleteSelection" in host.ops)
        assertFalse("deleteBackward must NOT run with a selection active", "deleteBackward" in host.ops)
        assertEquals("the selected span is gone, nothing before it touched", "abcdef", host.sb.toString())
    }
}
