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

import com.aegis.ime.decoder.Cand
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.engine.InputAssociations
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U23 data expansion: full-table replay of EVERY association key through the real controller on
 * BOTH keyboards — no sampling. 26-key: the key is typed letter by letter and the glyphs must splice in
 * right after the top word candidate. 9-key: the key's T9 digits are typed and each syllable is locked
 * through the real left-column reading flow (the association fires on the decoded reading).
 */
class AssociationsReplayTest {

    private class RecordingHost : ImeHost {
        val text = StringBuilder()
        val learned = mutableListOf<String>()
        override fun commitText(text: CharSequence) { this.text.append(text) }
        override fun deleteBackward() { if (text.isNotEmpty()) text.deleteCharAt(text.length - 1) }
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = text.substring(maxOf(0, text.length - n))
        override fun replaceBeforeCursor(length: Int, text: CharSequence) {
            val start = maxOf(0, this.text.length - length)
            this.text.delete(start, this.text.length); this.text.append(text)
        }
    }

    /** One stable word for any buffer, so the injection position is observable; records learn() calls. */
    private class OneWordEngine(val learned: MutableList<String> = mutableListOf()) : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean) = listOf("词")
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence) =
            if (composing.isEmpty()) emptyList() else listOf(Cand("词", composing.length))
        override fun learn(prevWord: String?, word: String) { learned.add(word) }
    }

    /** No word candidates at all (9-key locked-reading path) — the glyphs stand alone. */
    private class EmptyEngine : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    private fun out(s: String) = Key(s, output = s)
    private fun act(a: KeyAction) = Key("", action = a)

    // ------------------------------------------------------------------ 26-key: whole table

    @Test fun every_key_fires_on_the_26key_with_glyphs_right_after_the_top_candidate() {
        var replayed = 0
        for ((key, _) in InputAssociations.entriesForTest()) {
            val c = KeyboardController(RecordingHost(), OneWordEngine())
            key.forEach { c.onKey(out(it.toString())) }
            // The 26-key always appends the raw letters as the LAST candidate (controller behavior for
            // committing the buffer as-is) — the glyphs must sit between the top word and that tail.
            val expected = listOf("词") + InputAssociations.lookup(key) + listOf(key)
            assertEquals("26-key replay of '$key'", expected, c.candidateWords())
            replayed++
        }
        println("26-key replay: $replayed keys, all hit")
    }

    @Test fun every_multisyllable_key_fires_on_the_26key_in_separated_form_too() {
        // The 26-key CAN buffer the 隔音符 (an apostrophe key while composing appends to the pinyin
        // buffer — see KeyboardLossMatrixTest chai'ci), so the separated form she'shi'du must hit
        // exactly like the connected form. Whole table, every syllable boundary separated.
        var replayed = 0
        for ((key, _) in InputAssociations.entriesForTest()) {
            val syllables = T9Pinyin.segmentLetters(key) ?: continue
            if (syllables.size < 2) continue
            val c = KeyboardController(RecordingHost(), OneWordEngine())
            val separated = syllables.joinToString("'")
            separated.forEach { c.onKey(out(it.toString())) }
            val words = c.candidateWords()
            for (glyph in InputAssociations.lookup(key)) {
                assertTrue("26-key separated replay of '$separated' must offer $glyph (got $words)", glyph in words)
            }
            replayed++
        }
        println("26-key separated replay: $replayed multi-syllable keys, all hit")
    }

    // ------------------------------------------------------------------ 9-key: whole table via reading locks

    @Test fun every_key_fires_on_the_9key_after_locking_its_syllables() {
        var replayed = 0
        for ((key, _) in InputAssociations.entriesForTest()) {
            val syllables = T9Pinyin.segmentLetters(key)
            assertTrue("'$key' must segment (validity is asserted in the data test)", syllables != null)
            val c = KeyboardController(RecordingHost(), EmptyEngine())
            c.onKey(act(KeyAction.SWITCH_NINE))
            T9Pinyin.toT9(key).forEach { c.onKey(out(it.toString())) }
            for (syl in syllables!!) {
                val idx = c.expandedReadings().indexOf(syl)
                assertTrue(
                    "9-key '$key': syllable '$syl' must be offered in the left column (got ${c.expandedReadings()})",
                    idx >= 0,
                )
                c.onPickReadingIndex(idx)
            }
            assertEquals("9-key replay of '$key' after locking $syllables", InputAssociations.lookup(key), c.candidateWords())
            replayed++
        }
        println("9-key replay: $replayed keys, all hit")
    }

    // ------------------------------------------------------------------ injection semantics on the new data

    @Test fun a_multi_word_base_keeps_its_relative_order_around_the_injection() {
        val engine = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = listOf("摄氏度", "设施", "涉世")
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence) =
                if (composing.isEmpty()) emptyList() else listOf(Cand("摄氏度", composing.length), Cand("设施", composing.length), Cand("涉世", composing.length))
        }
        val c = KeyboardController(RecordingHost(), engine)
        "sheshidu".forEach { c.onKey(out(it.toString())) }
        assertEquals(
            "glyphs splice in after the top candidate; the rest of the base keeps its order",
            listOf("摄氏度") + InputAssociations.lookup("sheshidu") + listOf("设施", "涉世", "sheshidu"),
            c.candidateWords(),
        )
        assertTrue("℃ is offered for sheshidu", "℃" in c.candidateWords())
        assertEquals("the injected glyph is second, never first", "℃", c.candidateWords()[1])
    }

    @Test fun picking_an_injected_symbol_commits_directly_clears_and_never_learns() {
        val host = RecordingHost()
        val engine = OneWordEngine()
        val c = KeyboardController(host, engine)
        "sheshidu".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(c.candidateWords().indexOf("℃"))
        assertEquals("the symbol lands in the editor as-is", "℃", host.text.toString())
        assertTrue("buffer cleared after a direct commit", c.candidateWords().isEmpty())
        assertEquals("preedit is gone", "", c.preeditForTest())
        assertTrue("a direct-committed glyph must never be learned (got ${engine.learned})", engine.learned.isEmpty())
    }

    @Test fun word_candidates_from_the_dictionary_stay_pickable_after_injection() {
        val host = RecordingHost()
        val c = KeyboardController(host, OneWordEngine())
        "meijin".forEach { c.onKey(out(it.toString())) }
        assertTrue("\$ is offered for meijin", "\$" in c.candidateWords())
        c.onPickCandidate(0) // the word, not the glyph
        assertEquals("picking the top word still works normally", "词", host.text.toString())
    }

    // ------------------------------------------------------------------ injection-path full/half de-dup (defense)

    @Test fun the_injection_dedup_collapses_a_constructed_full_half_pair_keeping_the_first() {
        // The computeDecode defense: even if the data ever re-mixed a full/half pair into one key, the injection
        // folds it — a constructed pair collapses to the first-seen form, in BOTH orders (the data keeps the
        // full-width form first, so the full-width survives on shipped data).
        assertEquals("￥/¥ → ￥", listOf("￥"), dedupeFullHalfGlyphs(listOf("￥", "¥")))
        assertEquals("¥/￥ → ¥ (first-seen wins)", listOf("¥"), dedupeFullHalfGlyphs(listOf("¥", "￥")))
        assertEquals("？/? → ？", listOf("？"), dedupeFullHalfGlyphs(listOf("？", "?")))
        assertEquals(
            "a top word then a twin-pair keeps the word and only the first width",
            listOf("词", "＃"), dedupeFullHalfGlyphs(listOf("词", "＃", "#")),
        )
    }

    @Test fun the_injection_dedup_leaves_cross_character_lookalikes_intact() {
        // 。/. , −/- , •/· , ×/x are DIFFERENT characters (they do not fold), so both must survive the de-dup.
        assertEquals(listOf("。", "."), dedupeFullHalfGlyphs(listOf("。", ".")))
        assertEquals(listOf("−", "-"), dedupeFullHalfGlyphs(listOf("−", "-")))
        assertEquals(listOf("•", "·"), dedupeFullHalfGlyphs(listOf("•", "·")))
        assertEquals(listOf("×", "x"), dedupeFullHalfGlyphs(listOf("×", "x")))
    }

    @Test fun the_injection_dedup_is_a_noop_on_every_real_association_key() {
        // The shipped data keeps one width per character, so the defense never actually removes anything from a
        // real lookup — proven over the WHOLE merged table (no sampling). A failure here means a data edit
        // re-introduced a full/half twin (which the defense then correctly collapses).
        for ((key, _) in InputAssociations.entriesForTest()) {
            val hit = InputAssociations.lookup(key)
            assertEquals("dedup altered lookup('$key') — data re-introduced a full/half twin", hit, dedupeFullHalfGlyphs(hit))
        }
    }

    @Test fun renminbi_and_wenhao_offer_only_the_full_width_form_end_to_end() {
        // The exact user report, driven through the real controller on the 26-key: after the whole pinyin is
        // typed, the candidate strip carries the full-width mark and NOT its half-width twin.
        val c1 = KeyboardController(RecordingHost(), OneWordEngine())
        "renminbi".forEach { c1.onKey(out(it.toString())) }
        assertTrue("￥ is offered for renminbi", "￥" in c1.candidateWords())
        assertTrue("half-width ¥ must NOT be offered for renminbi (got ${c1.candidateWords()})", "¥" !in c1.candidateWords())

        val c2 = KeyboardController(RecordingHost(), OneWordEngine())
        "wenhao".forEach { c2.onKey(out(it.toString())) }
        assertTrue("？ is offered for wenhao", "？" in c2.candidateWords())
        assertTrue("half-width ? must NOT be offered for wenhao (got ${c2.candidateWords()})", "?" !in c2.candidateWords())
    }
}
