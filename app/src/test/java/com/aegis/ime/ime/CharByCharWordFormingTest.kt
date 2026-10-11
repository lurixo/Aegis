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
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.user.LiveUserDictHost
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CharByCharWordFormingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class EditorHost : ImeHost {
        val sb = StringBuilder()
        var caret = 0
        override fun commitText(text: CharSequence) {
            sb.insert(caret, text)
            caret += text.length
        }
        override fun deleteBackward() { if (caret > 0) sb.deleteCharAt(--caret) }
        override fun performEnter() { commitText("\n") }
        override fun textBeforeCursor(n: Int): CharSequence = sb.substring((caret - n).coerceAtLeast(0), caret)
    }

    private class Session(
        val controller: KeyboardController,
        val editor: EditorHost,
        val learning: UserLearning,
        val nine: Boolean,
    )

    private val run = listOf(
        "司" to "si", "马" to "ma", "东" to "dong", "方" to "fang",
        "明" to "ming", "月" to "yue", "清" to "qing", "风" to "feng",
        "欧" to "ou", "阳" to "yang", "长" to "chang", "空" to "kong",
        "白" to "bai", "云" to "yun", "苍" to "cang", "狗" to "gou",
        "华" to "hua",
    )
    private val frames = listOf(
        listOf("我" to "wo", "找" to "zhao") to listOf("玩" to "wan"),
        listOf("他" to "ta", "叫" to "jiao") to listOf("吧" to "ba"),
        listOf("跟" to "gen", "着" to "zhe") to listOf("走" to "zou"),
    )

    private val rows = (run + frames.flatMap { it.first + it.second }).map { (ch, r) -> EngineFixture.Row(r, ch, 900) } +
        T9Pinyin.SYLLABLES.map { EngineFixture.Row(it, "丌", 1) }

    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val lm: CharBigramLM by lazy { CharBigramLM.fromFile(lmFile) }
    private val hosts = ArrayList<LiveUserDictHost>()

    @Before fun requireLm() {
        assumeTrue("real LM asset present (production engine wiring)", lmFile.exists())
    }

    @After fun stopHosts() {
        hosts.forEach { runCatching { it.stopSaving() } }
    }

    private fun session(nine: Boolean, letters: BinaryDict, digits: BinaryDict, initials: BinaryDict? = null): Session {
        val model = UserModel()
        val learning = UserLearning()
        val live = LiveUserDictHost(model, tmp.newFile(), learning, tmp.newFile()).also { hosts += it }
        val editor = EditorHost()
        val engine = DictEngine(letters, digits, lm, userModel = model, initialsDict = initials, userLearning = learning)
        val controller = KeyboardController(editor, engine).also {
            it.userLearning = learning
            it.onLearned = { assertTrue("the stores are written", live.flush()) }
            it.switchTextLayoutForTest(nine = nine)
        }
        return Session(controller, editor, learning, nine)
    }

    private fun fixtureSession(nine: Boolean): Session = session(
        nine,
        EngineFixture.build(rows),
        EngineFixture.build(rows.map { EngineFixture.Row(T9Pinyin.toT9(it.key), it.word, it.freq) }),
    )

    private fun Session.type(reading: String) {
        val keys = if (nine) T9Pinyin.toT9(reading) else reading
        keys.forEach { controller.onKey(Key(it.toString(), output = it.toString())) }
    }

    private fun Session.spaceCommit(reading: String): String {
        val before = editor.caret
        type(reading)
        controller.onKey(Key("", action = KeyAction.SPACE))
        return editor.sb.substring(before, editor.caret)
    }

    private fun Session.enter() = controller.onKey(Key("", action = KeyAction.ENTER))

    private fun Session.typed(chars: List<Pair<String, String>>) {
        for ((ch, reading) in chars) assertEquals("space commits the first candidate of $reading", ch, spaceCommit(reading))
    }

    private fun Session.line(chars: List<Pair<String, String>>) {
        typed(chars)
        enter()
    }

    private fun Session.sent(restart: Boolean) {
        editor.sb.setLength(0)
        editor.caret = 0
        if (restart) controller.onInputTargetChanged() else controller.onEditorContextChanged()
    }

    private fun Session.offered(reading: String): List<String> {
        type(reading)
        val words = controller.candidateWords()
        controller.reset(preserveLayout = true)
        return words
    }

    private fun word(chars: List<Pair<String, String>>) = chars.joinToString("") { it.first }

    private fun reading(chars: List<Pair<String, String>>) = chars.joinToString("") { it.second }

    private fun formed(chars: List<Pair<String, String>>) = UserLearning.Formed(word(chars), reading(chars))

    private fun wholeLineNameIsFormedWhole(length: Int, nine: Boolean) {
        val name = run.take(length)
        val s = fixtureSession(nine)
        repeat(2) { s.line(name) }
        assertTrue("no word before the threshold: ${s.learning.formedEntries()}", s.learning.formedEntries().isEmpty())
        s.line(name)
        assertEquals(listOf(formed(name)), s.learning.formedEntries())
        assertTrue("${word(name)} is offered for its whole reading", word(name) in s.offered(reading(name)))
    }

    private fun runPastTheCapFormsItsFirstSixteenCharacters(nine: Boolean) {
        val s = fixtureSession(nine)
        repeat(3) { s.line(run) }
        assertEquals(listOf(formed(run.take(16))), s.learning.formedEntries())
        repeat(3) { s.line(run) }
        assertEquals(listOf(formed(run.take(16))), s.learning.formedEntries())
    }

    private fun nameInsideChangingSentencesIsFormedAlone(nine: Boolean) {
        val name = run.take(5)
        val s = fixtureSession(nine)
        for ((before, after) in frames) s.line(before + name + after)
        assertEquals(listOf(formed(name)), s.learning.formedEntries())
        assertTrue("${word(name)} is offered for its whole reading", word(name) in s.offered(reading(name)))
    }

    private fun alternatingNamesAreEachFormedWhole(nine: Boolean) {
        val first = run.subList(0, 5)
        val second = run.subList(12, 16)
        val s = fixtureSession(nine)
        repeat(3) {
            s.line(first)
            s.line(second)
        }
        assertEquals(setOf(formed(first), formed(second)), s.learning.formedEntries().toSet())
        assertTrue("${word(first)} is offered for its whole reading", word(first) in s.offered(reading(first)))
        assertTrue("${word(second)} is offered for its whole reading", word(second) in s.offered(reading(second)))
    }

    private fun nameTypedOnTheShippedDictionaryIsFormedWhole(nine: Boolean) {
        val dir = System.getenv("AEGIS_FULLDICT_DIR")
        assumeTrue("full-dict check only when AEGIS_FULLDICT_DIR is set", !dir.isNullOrEmpty())
        val s = session(
            nine,
            BinaryDict.fromFile(File(dir, "aegis_dict.bin")),
            BinaryDict.fromFile(File(dir, "aegis_t9.bin")),
            BinaryDict.fromFile(File(dir, "aegis_jianpin.bin")),
        )
        val syllables = listOf("ku", "mao", "tu", "zhu", "xiong")
        val lines = List(3) {
            val typed = syllables.joinToString("") { s.spaceCommit(it) }
            s.enter()
            typed
        }
        val name = lines.first()
        assertEquals("five single characters: $name", 5, name.length)
        assertEquals("the same characters come first on every line", listOf(name, name, name), lines)
        val formed = s.learning.formedEntries()
        assertEquals(listOf(name), formed.map { it.word })
        if (!nine) assertEquals(syllables.joinToString(""), formed.single().reading)
        assertTrue("$name is offered for ${formed.single().reading}", name in s.offered(formed.single().reading))
    }

    private fun nameSentByTheAppIsFormedOnce(nine: Boolean, restart: Boolean) {
        for (length in 3..5) {
            val name = run.take(length)
            val s = fixtureSession(nine)
            repeat(2) {
                s.typed(name)
                s.sent(restart)
            }
            assertTrue("no word before the threshold: ${s.learning.formedEntries()}", s.learning.formedEntries().isEmpty())
            s.typed(name)
            s.sent(restart)
            assertEquals("formed where the third message is sent", listOf(formed(name)), s.learning.formedEntries())
            repeat(3) {
                s.typed(name)
                s.sent(restart)
            }
            assertEquals(listOf(formed(name)), s.learning.formedEntries())
            assertTrue("${word(name)} is offered for its whole reading", word(name) in s.offered(reading(name)))
        }
    }

    private fun messagesSentInTurnAreNotJoined(nine: Boolean) {
        val first = run.subList(4, 6)
        val second = run.subList(6, 8)
        val s = fixtureSession(nine)
        repeat(3) {
            s.typed(first)
            s.sent(restart = true)
            s.typed(second)
            s.sent(restart = false)
        }
        assertEquals(setOf(formed(first), formed(second)), s.learning.formedEntries().toSet())
    }

    private fun reportsOfItsOwnCommitsLeaveTheRunOpen(nine: Boolean) {
        val name = run.take(5)
        val s = fixtureSession(nine)
        repeat(3) {
            for ((ch, reading) in name) {
                s.type(reading)
                s.controller.onEditorContextChanged()
                s.controller.onKey(Key("", action = KeyAction.SPACE))
                s.controller.onEditorContextChanged()
                s.controller.onInputTargetChanged()
                assertTrue("$ch is committed", s.editor.sb.endsWith(ch))
            }
            s.enter()
        }
        assertEquals(listOf(formed(name)), s.learning.formedEntries())
        assertTrue("${word(name)} is offered for its whole reading", word(name) in s.offered(reading(name)))
    }

    private fun editFromOutsideEndsTheRun(nine: Boolean, edit: EditorHost.() -> Unit) {
        val first = run.subList(0, 2)
        val second = run.subList(2, 4)
        val s = fixtureSession(nine)
        repeat(3) {
            s.typed(first)
            s.editor.edit()
            s.controller.onEditorContextChanged()
            s.typed(second)
            s.sent(restart = true)
        }
        assertEquals(setOf(formed(first), formed(second)), s.learning.formedEntries().toSet())
    }

    @Test fun two_character_name_is_formed_whole_on26key() = wholeLineNameIsFormedWhole(2, nine = false)

    @Test fun two_character_name_is_formed_whole_on9key() = wholeLineNameIsFormedWhole(2, nine = true)

    @Test fun three_character_name_is_formed_whole_on26key() = wholeLineNameIsFormedWhole(3, nine = false)

    @Test fun three_character_name_is_formed_whole_on9key() = wholeLineNameIsFormedWhole(3, nine = true)

    @Test fun four_character_name_is_formed_whole_on26key() = wholeLineNameIsFormedWhole(4, nine = false)

    @Test fun four_character_name_is_formed_whole_on9key() = wholeLineNameIsFormedWhole(4, nine = true)

    @Test fun five_character_name_is_formed_whole_on26key() = wholeLineNameIsFormedWhole(5, nine = false)

    @Test fun five_character_name_is_formed_whole_on9key() = wholeLineNameIsFormedWhole(5, nine = true)

    @Test fun sixteen_character_name_is_formed_whole_on26key() = wholeLineNameIsFormedWhole(16, nine = false)

    @Test fun sixteen_character_name_is_formed_whole_on9key() = wholeLineNameIsFormedWhole(16, nine = true)

    @Test fun run_past_the_cap_forms_its_first_sixteen_characters_on26key() =
        runPastTheCapFormsItsFirstSixteenCharacters(nine = false)

    @Test fun run_past_the_cap_forms_its_first_sixteen_characters_on9key() =
        runPastTheCapFormsItsFirstSixteenCharacters(nine = true)

    @Test fun name_inside_changing_sentences_is_formed_alone_on26key() = nameInsideChangingSentencesIsFormedAlone(nine = false)

    @Test fun name_inside_changing_sentences_is_formed_alone_on9key() = nameInsideChangingSentencesIsFormedAlone(nine = true)

    @Test fun alternating_names_are_each_formed_whole_on26key() = alternatingNamesAreEachFormedWhole(nine = false)

    @Test fun alternating_names_are_each_formed_whole_on9key() = alternatingNamesAreEachFormedWhole(nine = true)

    @Test fun name_typed_on_the_shipped_dictionary_is_formed_whole_on26key() =
        nameTypedOnTheShippedDictionaryIsFormedWhole(nine = false)

    @Test fun name_typed_on_the_shipped_dictionary_is_formed_whole_on9key() =
        nameTypedOnTheShippedDictionaryIsFormedWhole(nine = true)

    @Test fun name_sent_with_the_apps_own_button_is_formed_once_on26key() =
        nameSentByTheAppIsFormedOnce(nine = false, restart = true)

    @Test fun name_sent_with_the_apps_own_button_is_formed_once_on9key() =
        nameSentByTheAppIsFormedOnce(nine = true, restart = true)

    @Test fun name_in_a_field_the_app_empties_is_formed_once_on26key() =
        nameSentByTheAppIsFormedOnce(nine = false, restart = false)

    @Test fun name_in_a_field_the_app_empties_is_formed_once_on9key() =
        nameSentByTheAppIsFormedOnce(nine = true, restart = false)

    @Test fun messages_sent_in_turn_are_not_joined_on26key() = messagesSentInTurnAreNotJoined(nine = false)

    @Test fun messages_sent_in_turn_are_not_joined_on9key() = messagesSentInTurnAreNotJoined(nine = true)

    @Test fun reports_of_its_own_commits_leave_the_run_open_on26key() = reportsOfItsOwnCommitsLeaveTheRunOpen(nine = false)

    @Test fun reports_of_its_own_commits_leave_the_run_open_on9key() = reportsOfItsOwnCommitsLeaveTheRunOpen(nine = true)

    @Test fun moving_the_caret_ends_the_run_on26key() = editFromOutsideEndsTheRun(nine = false) { caret = 0 }

    @Test fun moving_the_caret_ends_the_run_on9key() = editFromOutsideEndsTheRun(nine = true) { caret = 0 }

    @Test fun text_the_app_inserts_ends_the_run_on26key() = editFromOutsideEndsTheRun(nine = false) { commitText("清风") }

    @Test fun text_the_app_inserts_ends_the_run_on9key() = editFromOutsideEndsTheRun(nine = true) { commitText("清风") }
}
