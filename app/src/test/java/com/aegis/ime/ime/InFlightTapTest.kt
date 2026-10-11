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
import com.aegis.ime.engine.CandidateEngine
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
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InFlightTapTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private class Host : ImeHost {
        val commits = ArrayList<String>()
        override fun commitText(text: CharSequence) { commits.add(text.toString()) }
        override fun deleteBackward() {}
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = ""
    }

    @Test fun a_candidate_tap_lands_while_the_next_decode_is_still_on_the_worker() {
        val workerQ = ArrayDeque<Runnable>()
        val mainQ = ArrayDeque<Runnable>()
        val lane = DecodeLane(Executor { workerQ.add(it) }, Executor { mainQ.add(it) })
        fun drain() {
            while (workerQ.isNotEmpty() || mainQ.isNotEmpty()) {
                while (workerQ.isNotEmpty()) workerQ.removeFirst().run()
                while (mainQ.isNotEmpty()) mainQ.removeFirst().run()
            }
        }
        val engine = DictEngine(
            EngineFixture.build(
                listOf(EngineFixture.Row("ni", "你", 900), EngineFixture.Row("hao", "好", 900)),
            ),
            null,
            null,
        )
        val host = Host()
        val c = KeyboardController(host, engine, lane).apply { attachView(InputView(ctx)) }
        c.switchTextLayoutForTest(nine = false)
        "ni".forEach { ch -> c.onKey(Key(ch.toString(), output = ch.toString())) }
        drain()
        val idx = c.candidateWords().indexOf("你")
        assertTrue("the fixture offers 你 for ni", idx >= 0)

        c.onKey(Key("h", output = "h"))
        assertTrue("the next decode is genuinely in flight", lane.pending)

        c.onPickCandidate(idx)
        assertTrue(
            "the tap must act on the list that is on screen",
            "你" in c.preeditForTest(),
        )
        drain()
        assertTrue("the late decode cannot undo the pick", "你" in c.preeditForTest())
    }

    private class Predictions : CandidateEngine {
        override val supportsChinese: Boolean = true
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        override fun predict(prevWord: String?): List<String> = listOf("吗")
    }

    private class Typing(val c: KeyboardController, val host: Host, val lane: DecodeLane, val drain: () -> Unit) {
        fun type(s: String) = s.forEach { c.onKey(Key(it.toString(), output = it.toString())) }
        fun key(action: KeyAction) = c.onKey(Key("", action = action))
        fun state() = "${host.commits}|${c.composingPrefix()}|${c.rawComposingForTest()}"
        fun shows(input: String, word: String): Int {
            type(input)
            drain()
            val idx = c.candidateWords().indexOf(word)
            assertTrue("the fixture offers $word for $input", idx >= 0)
            return idx
        }
    }

    private fun typing(nine: Boolean, predictions: Boolean = false): Typing {
        val workerQ = ArrayDeque<Runnable>()
        val mainQ = ArrayDeque<Runnable>()
        val lane = DecodeLane(Executor { workerQ.add(it) }, Executor { mainQ.add(it) })
        val rows = listOf(EngineFixture.Row("ni", "你", 900), EngineFixture.Row("hao", "好", 900))
        val engine = if (predictions) {
            Predictions()
        } else {
            DictEngine(
                EngineFixture.build(rows),
                EngineFixture.build(rows.map { EngineFixture.Row(T9Pinyin.toT9(it.key), it.word, it.freq) }),
                null,
            )
        }
        val host = Host()
        val c = KeyboardController(host, engine, lane).apply { attachView(InputView(ctx)) }
        c.switchTextLayoutForTest(nine)
        return Typing(c, host, lane) {
            while (workerQ.isNotEmpty() || mainQ.isNotEmpty()) {
                while (workerQ.isNotEmpty()) workerQ.removeFirst().run()
                while (mainQ.isNotEmpty()) mainQ.removeFirst().run()
            }
        }
    }

    private fun keys(nine: Boolean, letters: String) = if (nine) T9Pinyin.toT9(letters) else letters

    private fun aCandidateTapLandsWhileAppendedKeysAreStillOnTheWorker(nine: Boolean, appended: String) {
        val t = typing(nine)
        val idx = t.shows(keys(nine, "ni"), "你")
        val rest = keys(nine, appended)
        t.type(rest)
        assertTrue("the next decode is genuinely in flight", t.lane.pending)

        t.c.onPickCandidate(idx)
        assertEquals("the tap picks the word on screen and leaves the keys typed after it", "[]|你|$rest", t.state())
        t.drain()
        assertEquals("the late decode cannot undo the pick", "[]|你|$rest", t.state())
    }

    @Test fun a_candidate_tap_lands_while_the_next_decode_is_still_on_the_worker_on_9key() =
        aCandidateTapLandsWhileAppendedKeysAreStillOnTheWorker(nine = true, appended = "h")

    @Test fun a_candidate_tap_lands_on_a_list_two_keys_behind_on_26key() =
        aCandidateTapLandsWhileAppendedKeysAreStillOnTheWorker(nine = false, appended = "ha")

    @Test fun a_candidate_tap_lands_on_a_list_two_keys_behind_on_9key() =
        aCandidateTapLandsWhileAppendedKeysAreStillOnTheWorker(nine = true, appended = "ha")

    private fun aCandidateTapLandsAfterTheKeysTypedPastItsListWereReplaced(nine: Boolean) {
        val t = typing(nine)
        val idx = t.shows(keys(nine, "ni"), "你")
        t.type(keys(nine, "h"))
        t.key(KeyAction.BACKSPACE)
        t.type(keys(nine, "a"))
        assertTrue("the next decode is genuinely in flight", t.lane.pending)

        t.c.onPickCandidate(idx)
        assertEquals("the list on screen is still for the start of the input", "[]|你|${keys(nine, "a")}", t.state())
    }

    @Test fun a_candidate_tap_lands_after_the_keys_typed_past_its_list_were_replaced_on_26key() =
        aCandidateTapLandsAfterTheKeysTypedPastItsListWereReplaced(nine = false)

    @Test fun a_candidate_tap_lands_after_the_keys_typed_past_its_list_were_replaced_on_9key() =
        aCandidateTapLandsAfterTheKeysTypedPastItsListWereReplaced(nine = true)

    private fun aCandidateTapLandsWhileAnUnchangedInputIsDecodedAgain(nine: Boolean) {
        val t = typing(nine)
        val idx = t.shows(keys(nine, "ni"), "你")
        t.key(KeyAction.SHIFT)
        assertTrue("the next decode is genuinely in flight", t.lane.pending)

        t.c.onPickCandidate(idx)
        assertEquals("the list on screen is still for the whole input", "[你]||", t.state())
    }

    @Test fun a_candidate_tap_lands_while_an_unchanged_input_is_decoded_again_on_26key() =
        aCandidateTapLandsWhileAnUnchangedInputIsDecodedAgain(nine = false)

    @Test fun a_candidate_tap_lands_while_an_unchanged_input_is_decoded_again_on_9key() =
        aCandidateTapLandsWhileAnUnchangedInputIsDecodedAgain(nine = true)

    private fun aCandidateTapIsIgnoredOnceTheInputLeftItsList(
        nine: Boolean,
        letters: String,
        word: String,
        predictions: Boolean = false,
        change: (Typing) -> Unit,
    ) {
        val t = typing(nine, predictions)
        val idx = t.shows(keys(nine, letters), word)
        change(t)
        assertTrue("the next decode is genuinely in flight", t.lane.pending)
        val before = t.state()

        t.c.onPickCandidate(idx)
        assertEquals("the list on screen is no longer for the start of the input, so the tap changes nothing", before, t.state())
        t.drain()
        assertEquals(before, t.state())
    }

    @Test fun a_candidate_tap_is_ignored_while_a_backspace_is_still_on_the_worker_on_26key() =
        aCandidateTapIsIgnoredOnceTheInputLeftItsList(nine = false, letters = "nihao", word = "你好") { it.key(KeyAction.BACKSPACE) }

    @Test fun a_candidate_tap_is_ignored_while_a_backspace_is_still_on_the_worker_on_9key() =
        aCandidateTapIsIgnoredOnceTheInputLeftItsList(nine = true, letters = "nihao", word = "你好") { it.key(KeyAction.BACKSPACE) }

    @Test fun a_candidate_tap_is_ignored_while_a_retyped_key_is_still_on_the_worker_on_26key() =
        aCandidateTapIsIgnoredOnceTheInputLeftItsList(nine = false, letters = "nihao", word = "你好") {
            it.key(KeyAction.BACKSPACE)
            it.type("a")
        }

    @Test fun a_candidate_tap_is_ignored_while_a_retyped_key_is_still_on_the_worker_on_9key() =
        aCandidateTapIsIgnoredOnceTheInputLeftItsList(nine = true, letters = "nihao", word = "你好") {
            it.key(KeyAction.BACKSPACE)
            it.type("2")
        }

    @Test fun a_candidate_tap_is_ignored_while_a_syllable_break_is_still_on_the_worker_on_26key() =
        aCandidateTapIsIgnoredOnceTheInputLeftItsList(nine = false, letters = "ni", word = "你") { it.key(KeyAction.SEGMENT) }

    @Test fun a_candidate_tap_is_ignored_while_a_syllable_break_is_still_on_the_worker_on_9key() =
        aCandidateTapIsIgnoredOnceTheInputLeftItsList(nine = true, letters = "ni", word = "你") { it.key(KeyAction.SEGMENT) }

    @Test fun a_candidate_tap_is_ignored_while_a_locked_reading_is_still_on_the_worker_on_26key() =
        aCandidateTapIsIgnoredOnceTheInputLeftItsList(nine = false, letters = "ni", word = "你") {
            it.c.onKey(Key("ni", output = "ni", action = KeyAction.PICK_READING))
        }

    @Test fun a_candidate_tap_is_ignored_while_a_locked_reading_is_still_on_the_worker_on_9key() =
        aCandidateTapIsIgnoredOnceTheInputLeftItsList(nine = true, letters = "ni", word = "你") {
            it.c.onKey(Key("ni", output = "ni", action = KeyAction.PICK_READING))
        }

    @Test fun a_candidate_tap_is_ignored_while_a_language_switch_is_still_on_the_worker_on_26key() =
        aCandidateTapIsIgnoredOnceTheInputLeftItsList(nine = false, letters = "", word = "吗", predictions = true) {
            it.key(KeyAction.TOGGLE_LANG)
        }

    @Test fun a_candidate_tap_is_ignored_while_a_language_switch_is_still_on_the_worker_on_9key() =
        aCandidateTapIsIgnoredOnceTheInputLeftItsList(nine = true, letters = "", word = "吗", predictions = true) {
            it.key(KeyAction.TOGGLE_LANG)
        }

    @Test fun a_candidate_tap_is_ignored_while_a_layout_switch_is_still_on_the_worker_on_26key() =
        aCandidateTapIsIgnoredOnceTheInputLeftItsList(nine = false, letters = "", word = "吗", predictions = true) {
            it.c.switchTextLayoutForTest(nine = true)
        }

    @Test fun a_candidate_tap_is_ignored_while_a_layout_switch_is_still_on_the_worker_on_9key() =
        aCandidateTapIsIgnoredOnceTheInputLeftItsList(nine = true, letters = "", word = "吗", predictions = true) {
            it.c.switchTextLayoutForTest(nine = false)
        }

    @Test fun the_candidate_bar_knows_while_the_list_on_screen_is_waiting_for_a_decode() {
        val workerQ = ArrayDeque<Runnable>()
        val mainQ = ArrayDeque<Runnable>()
        val lane = DecodeLane(Executor { workerQ.add(it) }, Executor { mainQ.add(it) })
        fun drain() {
            while (workerQ.isNotEmpty() || mainQ.isNotEmpty()) {
                while (workerQ.isNotEmpty()) workerQ.removeFirst().run()
                while (mainQ.isNotEmpty()) mainQ.removeFirst().run()
            }
        }
        val engine = DictEngine(EngineFixture.build(listOf(EngineFixture.Row("ni", "你", 900))), null, null)
        val view = InputView(ctx)
        val c = KeyboardController(Host(), engine, lane).apply { attachView(view) }
        c.switchTextLayoutForTest(nine = false)
        drain()
        c.onKey(Key("n", output = "n"))
        assertTrue("the list on screen predates the n that is still decoding", view.candidatesPendingForTest())
        drain()
        assertTrue("the decoded list is no longer pending", !view.candidatesPendingForTest())
    }
}
