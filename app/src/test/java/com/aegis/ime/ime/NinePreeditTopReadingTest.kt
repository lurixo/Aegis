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
import com.aegis.ime.decoder.EngineFixture
import com.aegis.ime.decoder.FullDictTestAssets
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.neural.NeuralModelSpec
import com.aegis.ime.neural.NeuralReranker
import com.aegis.ime.neural.SentenceScorer
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NinePreeditTopReadingTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private class Host : ImeHost {
        val commits = ArrayList<String>()
        override fun commitText(text: CharSequence) { commits.add(text.toString()) }
        override fun deleteBackward() {}
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = ""
    }

    private class Lane {
        private val workerQ = ArrayDeque<Runnable>()
        private val mainQ = ArrayDeque<Runnable>()
        val lane = DecodeLane(Executor { workerQ.add(it) }, Executor { mainQ.add(it) })
        fun drain() {
            while (workerQ.isNotEmpty() || mainQ.isNotEmpty()) {
                while (workerQ.isNotEmpty()) workerQ.removeFirst().run()
                while (mainQ.isNotEmpty()) mainQ.removeFirst().run()
            }
        }
        fun work() {
            while (workerQ.isNotEmpty()) workerQ.removeFirst().run()
        }
        fun deliver() {
            while (mainQ.isNotEmpty()) mainQ.removeFirst().run()
        }
    }

    private val rows = listOf(
        EngineFixture.Row("zhan", "站", 950), EngineFixture.Row("xiao", "小", 300),
        EngineFixture.Row("zha", "炸", 500), EngineFixture.Row("xia", "下", 100),
        EngineFixture.Row("zhang", "张", 700),
        EngineFixture.Row("fangan", "方案", 900), EngineFixture.Row("fang", "方", 500),
        EngineFixture.Row("an", "案", 400), EngineFixture.Row("dao", "到", 300),
        EngineFixture.Row("hao", "好", 600),
        EngineFixture.Row("xian", "西安", 900), EngineFixture.Row("xi", "西", 500),
        EngineFixture.Row("an", "安", 450), EngineFixture.Row("yi", "一", 300),
        EngineFixture.Row("ao", "奥", 100),
        EngineFixture.Row("nihao", "你好", 900), EngineFixture.Row("ni", "你", 800),
    )

    private fun fixtureEngine() = DictEngine(
        EngineFixture.build(rows),
        EngineFixture.build(rows.map { EngineFixture.Row(T9Pinyin.toT9(it.key), it.word, it.freq) }),
        null,
    )

    private val fullAssets = FullDictTestAssets.directory
    private val fullDict by lazy { BinaryDict.fromFile(File(fullAssets, FullDictTestAssets.DICT)) }
    private val fullEngine by lazy {
        DictEngine(
            fullDict,
            BinaryDict.fromFile(File(fullAssets, FullDictTestAssets.T9)),
            CharBigramLM.fromFile(File(fullAssets, FullDictTestAssets.LM)),
            initialsDict = BinaryDict.fromFile(File(fullAssets, FullDictTestAssets.JIANPIN)),
        )
    }

    private fun assumeFullAssets() = assumeTrue(
        FullDictTestAssets.available(
            File(fullAssets, FullDictTestAssets.DICT),
            File(fullAssets, FullDictTestAssets.T9),
            File(fullAssets, FullDictTestAssets.LM),
            File(fullAssets, FullDictTestAssets.JIANPIN),
        ),
    )

    private fun controller(
        engine: CandidateEngine = fixtureEngine(),
        nine: Boolean = true,
        host: Host = Host(),
        lane: DecodeLane? = null,
    ) = KeyboardController(host, engine, lane).apply {
        attachView(InputView(ctx))
        switchTextLayoutForTest(nine)
    }

    private val segment = Key("'", action = KeyAction.SEGMENT)
    private val backspace = Key("⌫", action = KeyAction.BACKSPACE)
    private val enter = Key("⏎", action = KeyAction.ENTER)

    private fun KeyboardController.type(input: String) {
        for (ch in input) onKey(if (ch == '\'') segment else Key(ch.toString(), output = ch.toString()))
    }

    private fun KeyboardController.lock(reading: String) {
        val index = expandedReadings().indexOf(reading)
        assertTrue("the reading column offers $reading, was ${expandedReadings()}", index >= 0)
        onPickReadingIndex(index)
    }

    private fun lettersOf(label: String) = label.replace("'", "")

    private fun assertSpells(digits: String, label: String) =
        assertEquals("every digit shows one letter of its key in '$label'", digits, T9Pinyin.toT9(lettersOf(label)))

    @Test fun the_label_spells_the_first_candidate_rather_than_the_commonest_syllable() {
        val c = controller()
        c.type("9426")
        assertEquals("站", c.candidateWords().first())
        assertNotEquals("zhan", T9Pinyin.preedit("9426"))
        assertEquals("zhan", c.preeditForTest())
    }

    @Test fun the_label_splits_where_the_first_candidate_does() {
        val c = controller()
        c.type("326426")
        assertEquals("方案", c.candidateWords().first())
        assertNotEquals("fang'an", T9Pinyin.preedit("326426"))
        assertEquals("fang'an", c.preeditForTest())
    }

    @Test fun a_typed_separator_stays_in_place_under_the_first_candidates_reading() {
        val c = controller()
        c.type("94'26")
        assertEquals("西安", c.candidateWords().first())
        assertNotEquals("xi'an", T9Pinyin.preedit("9426", setOf(2)))
        assertEquals("xi'an", c.preeditForTest())
        c.type("'")
        assertEquals("xi'an'", c.preeditForTest())
    }

    @Test fun digits_past_the_first_candidate_keep_the_static_spelling() {
        val c = controller()
        c.type("94265")
        assertTrue("the first candidate stops short, was ${c.decodeStateForTest()}", c.decodeStateForTest().startsWith("C:炸/3,"))
        assertNotEquals("zha", T9Pinyin.preedit("942"))
        assertEquals("zha'" + T9Pinyin.preedit("65"), c.preeditForTest())
        assertSpells("94265", c.preeditForTest())
    }

    @Test fun a_locked_reading_outranks_the_first_candidates_reading() {
        val c = controller()
        c.type("9426")
        assertEquals("zhan", c.preeditForTest())
        c.lock("xiao")
        assertEquals("xiao", c.preeditForTest())

        val partial = controller()
        partial.type("9426")
        partial.lock("xi")
        assertEquals("西安", firstCandidate(partial)?.word)
        assertEquals("xi'an", partial.preeditForTest())
    }

    @Test fun a_first_candidate_without_a_reading_falls_back_to_the_static_label() {
        val real = fixtureEngine()
        val engine = object : CandidateEngine by real {
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence) =
                listOf(Cand("😄", composing.length)) + real.candidatesCovered(composing, t9, cuts, context)
        }
        val c = controller(engine)
        c.type("9426")
        assertEquals("😄", c.candidateWords().first())
        assertEquals(T9Pinyin.preedit("9426"), c.preeditForTest())

        val bare = controller(object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = listOf("站")
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence) =
                listOf(Cand("站", composing.length))
        })
        bare.type("9426")
        assertEquals(T9Pinyin.preedit("9426"), bare.preeditForTest())
    }

    @Test fun backspace_moves_the_label_to_the_new_first_candidate() {
        val c = controller()
        c.type("94264")
        assertEquals("zhang", c.preeditForTest())
        c.onKey(backspace)
        assertEquals("zhan", c.preeditForTest())
        c.onKey(backspace)
        assertEquals("炸", c.candidateWords().first())
        assertNotEquals("zha", T9Pinyin.preedit("942"))
        assertEquals("zha", c.preeditForTest())
    }

    @Test fun the_digits_left_after_a_pick_follow_their_own_first_candidate() {
        val c = controller()
        c.type("644269426")
        assertSpells("644269426", c.preeditForTest())
        val index = c.candidateWords().indexOf("你好")
        assertTrue("the list offers 你好, was ${c.candidateWords()}", index >= 0)
        c.onPickCandidate(index)
        assertEquals("站", c.candidateWords().first())
        assertEquals("你好zhan", c.preeditForTest())
    }

    @Test fun enter_commits_the_letters_on_the_label() {
        val host = Host()
        val c = controller(host = host)
        c.type("9426")
        c.onKey(enter)
        assertEquals(listOf("zhan"), host.commits)

        val cut = Host()
        val d = controller(host = cut)
        d.type("94'26")
        d.onKey(enter)
        assertEquals(listOf("xian"), cut.commits)
    }

    @Test fun tapping_the_label_starts_editing_from_the_letters_it_shows() {
        val c = controller()
        c.type("9426")
        c.onPreeditTap()
        assertTrue(c.preeditEditing())
        assertEquals("zhan", c.preeditForTest())
        assertEquals("zhan", c.preeditModelForTest()?.text)

        val cut = controller()
        cut.type("94'26")
        cut.onPreeditTap()
        assertEquals("xi'an", cut.preeditForTest())
    }

    @Test fun a_label_waiting_for_its_list_keeps_what_was_settled_and_one_letter_per_digit() {
        val q = Lane()
        val c = controller(lane = q.lane)
        q.drain()
        c.type("9")
        assertTrue(q.lane.pending)
        assertEquals(T9Pinyin.preedit("9"), c.preeditForTest())
        c.type("426")
        assertSpells("9426", c.preeditForTest())
        q.drain()
        assertEquals("zhan", c.preeditForTest())

        c.type("4")
        assertTrue("the list on screen predates the last digit", q.lane.pending)
        assertEquals("zhang", c.preeditForTest())
        q.drain()
        assertEquals("zhang", c.preeditForTest())

        c.onKey(backspace)
        assertTrue(q.lane.pending)
        assertEquals("zhan", c.preeditForTest())
        q.drain()
        assertEquals("zhan", c.preeditForTest())

        c.type("5")
        assertTrue(q.lane.pending)
        assertEquals("zhan'" + T9Pinyin.preedit("5"), c.preeditForTest())
        c.type("5")
        assertSpells("942655", c.preeditForTest())
        assertTrue(c.preeditForTest().startsWith("zhan'"))
        q.drain()
        val sync = controller()
        sync.type("942655")
        assertEquals(sync.preeditForTest(), c.preeditForTest())
    }

    @Test fun a_list_of_fewer_digits_shown_while_keys_outrun_the_decode_does_not_respell_the_label() {
        val q = Lane()
        val c = controller(lane = q.lane)
        q.drain()
        c.type("94")
        q.drain()
        assertEquals("xi", c.preeditForTest())

        c.type("26")
        q.work()
        c.type("4")
        q.deliver()
        assertTrue(q.lane.pending)
        assertTrue(
            "the fifth key shows the list of the first four, was ${c.decodeStateForTest()}",
            c.decodeStateForTest().startsWith("C:站/4,"),
        )
        assertEquals(T9Pinyin.carryPreedit("xi", "94", "94264", emptySet()), c.preeditForTest())
        assertSpells("94264", c.preeditForTest())
        assertTrue(c.preeditForTest().startsWith("xi"))
        assertNotEquals("zhan'" + T9Pinyin.preedit("4"), c.preeditForTest())
        q.drain()
        assertEquals("张", c.candidateWords().first())
        assertEquals("zhang", c.preeditForTest())
    }

    @Test fun a_list_of_more_digits_shown_while_backspace_outruns_the_decode_does_not_respell_the_label() {
        val q = Lane()
        val c = controller(lane = q.lane)
        q.drain()
        c.type("9426")
        q.drain()
        assertEquals("zhan", c.preeditForTest())

        c.onKey(backspace)
        c.onKey(backspace)
        q.work()
        c.onKey(backspace)
        q.deliver()
        assertTrue(q.lane.pending)
        assertTrue(
            "the third backspace shows the list of the first two digits, was ${c.decodeStateForTest()}",
            c.decodeStateForTest().startsWith("C:西/2,"),
        )
        assertEquals(T9Pinyin.carryPreedit("zhan", "9426", "9", emptySet()), c.preeditForTest())
        assertEquals("z", c.preeditForTest())
        assertSpells("9", c.preeditForTest())
        q.drain()
        val sync = controller()
        sync.type("9")
        assertEquals(sync.preeditForTest(), c.preeditForTest())
    }

    @Test fun a_first_candidate_longer_than_the_digits_spells_only_the_characters_they_reach() {
        val real = fixtureEngine()
        val engine = object : CandidateEngine by real {
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence) =
                listOf(Cand("你好", composing.length)) + real.candidatesCovered(composing, t9, cuts, context)
        }
        val whole = controller(engine)
        whole.type("64")
        assertEquals("ni", whole.preeditForTest())
        val clipped = controller(engine)
        clipped.type("644")
        assertEquals("ni'h", clipped.preeditForTest())
        val completed = controller(engine)
        completed.type("6442")
        assertEquals("ni'ha", completed.preeditForTest())
    }

    @Test fun a_new_composition_does_not_inherit_the_previous_label() {
        val q = Lane()
        val host = Host()
        val c = controller(host = host, lane = q.lane)
        q.drain()
        c.type("9426")
        q.drain()
        assertEquals("zhan", c.preeditForTest())
        c.onKey(enter)
        q.drain()
        assertEquals(listOf("zhan"), host.commits)
        c.type("94")
        assertTrue(q.lane.pending)
        assertEquals(T9Pinyin.preedit("94"), c.preeditForTest())
    }

    @Test fun the_alpha_label_still_shows_the_typed_letters() {
        val host = Host()
        val c = controller(nine = false, host = host)
        c.type("xian")
        assertEquals("西安", c.candidateWords().first())
        assertEquals("xian", c.preeditForTest())
        c.onKey(enter)
        assertEquals(listOf("xian"), host.commits)

        val d = controller(nine = false)
        d.type("xiao")
        assertEquals("xiao", d.preeditForTest())
        d.type("'zhan")
        assertEquals(T9Pinyin.preeditLetters("xiao'zhan"), d.preeditForTest())
    }

    private class Model(real: CandidateEngine, paths: Map<String, List<String>>, choice: String) {
        private val decodeWorker = ArrayDeque<Runnable>()
        private val decodeMain = ArrayDeque<Runnable>()
        private val modelWorker = ArrayDeque<Runnable>()
        private val modelMain = ArrayDeque<Runnable>()
        val lane = DecodeLane(Executor { decodeWorker.add(it) }, Executor { decodeMain.add(it) })
        val engine: CandidateEngine = object : CandidateEngine by real {
            override fun candidatesCoveredWithSentences(
                composing: String,
                t9: Boolean,
                context: CharSequence,
                sentenceLimit: Int,
            ): Pair<List<Cand>, List<Pair<String, Double>>> =
                real.candidatesCovered(composing, t9, emptySet(), context) to
                    paths[composing].orEmpty().mapIndexed { rank, text -> text to -10.0 - rank }
        }
        private val scorer = object : SentenceScorer {
            override fun score(context: String, candidates: List<String>): DoubleArray =
                DoubleArray(candidates.size) { if (candidates[it] == choice) -1.0 else -5.0 }
            override fun warm(context: String) {}
            override fun cancel() {}
            override fun resume() {}
            override fun close() {}
        }
        val reranker = NeuralReranker(
            NeuralModelSpec("test", alphaLetters = 5.0, alphaNine = 5.0, candidates = 10),
            { scorer },
            Executor { modelWorker.add(it) },
            Executor { modelMain.add(it) },
            { _, _ -> },
        )
        fun decode() = drain(decodeWorker, decodeMain)
        fun answer() = drain(modelWorker, modelMain)
        private fun drain(worker: ArrayDeque<Runnable>, main: ArrayDeque<Runnable>) {
            while (worker.isNotEmpty() || main.isNotEmpty()) {
                while (worker.isNotEmpty()) worker.removeFirst().run()
                while (main.isNotEmpty()) main.removeFirst().run()
            }
        }
    }

    private fun modelController(model: Model, nine: Boolean): KeyboardController {
        val c = controller(model.engine, nine, lane = model.lane)
        model.reranker.start()
        model.answer()
        c.neural = model.reranker
        model.decode()
        return c
    }

    private fun nineModel() =
        Model(fixtureEngine(), mapOf("942" to listOf("炸", "下"), "9426" to listOf("站", "小")), "小")

    @Test fun a_label_follows_the_decoded_list_at_once_and_then_the_list_the_model_reorders() {
        val m = nineModel()
        val c = modelController(m, nine = true)
        c.type("94")
        m.decode()
        m.answer()
        assertEquals("西", c.candidateWords().first())
        assertEquals("xi", c.preeditForTest())

        c.type("2")
        assertEquals("the list of the second key stays while the third decodes", "西", c.candidateWords().first())
        assertEquals(T9Pinyin.carryPreedit("xi", "94", "942", emptySet()), c.preeditForTest())
        assertSpells("942", c.preeditForTest())
        assertNotEquals("xi'" + T9Pinyin.preedit("2"), c.preeditForTest())
        m.decode()
        assertEquals("the list of the third key shows before the model answers", "炸", c.candidateWords().first())
        assertEquals("zha", c.preeditForTest())
        m.answer()
        assertEquals("炸", c.candidateWords().first())
        assertEquals("zha", c.preeditForTest())

        c.type("6")
        assertEquals("炸", c.candidateWords().first())
        assertEquals(T9Pinyin.carryPreedit("zha", "942", "9426", emptySet()), c.preeditForTest())
        assertSpells("9426", c.preeditForTest())
        m.decode()
        assertEquals("the list of the fourth key shows in the decoder order", "站", c.candidateWords().first())
        assertEquals("zhan", c.preeditForTest())
        m.answer()
        assertEquals("the model moved its choice to the front", "小", c.candidateWords().first())
        assertNotEquals("xiao", T9Pinyin.carryPreedit("zha", "942", "9426", emptySet()))
        assertEquals("xiao", c.preeditForTest())
    }

    @Test fun a_list_left_on_screen_does_not_respell_the_digit_typed_after_it() {
        val m = nineModel()
        val c = modelController(m, nine = true)
        c.type("94")
        m.decode()
        m.answer()
        assertEquals("xi", c.preeditForTest())
        c.type("2")
        m.decode()
        assertEquals("the list of the third key shows before the model answers", "炸", c.candidateWords().first())
        assertEquals("zha", c.preeditForTest())

        c.type("6")
        assertTrue(
            "the fourth key leaves the list of the third on screen, was ${c.decodeStateForTest()}",
            c.decodeStateForTest().startsWith("C:炸/3,"),
        )
        assertEquals(T9Pinyin.carryPreedit("zha", "942", "9426", emptySet()), c.preeditForTest())
        assertSpells("9426", c.preeditForTest())
        m.decode()
        assertTrue(
            "the list of the fourth key shows before the model answers, was ${c.decodeStateForTest()}",
            c.decodeStateForTest().startsWith("C:站/4,"),
        )
        assertEquals("zhan", c.preeditForTest())
        m.answer()
        assertEquals("小", c.candidateWords().first())
        assertEquals("xiao", c.preeditForTest())
    }

    @Test fun the_alpha_label_shows_the_typed_letters_whichever_list_is_on_screen() {
        val m = Model(fixtureEngine(), mapOf("xia" to listOf("下", "西"), "xiao" to listOf("小", "下")), "")
        val c = modelController(m, nine = false)
        c.type("xi")
        m.decode()
        m.answer()
        assertEquals("西", c.candidateWords().first())
        c.type("a")
        assertEquals("the list of the second key stays while the third decodes", "西", c.candidateWords().first())
        assertEquals("xia", c.preeditForTest())
        m.decode()
        assertEquals("the list of the third key shows before the model answers", "下", c.candidateWords().first())
        assertEquals("xia", c.preeditForTest())
        c.type("o")
        assertEquals("the fourth key leaves the list of the third on screen", "下", c.candidateWords().first())
        assertEquals("xiao", c.preeditForTest())
        m.decode()
        assertEquals("小", c.candidateWords().first())
        assertEquals("xiao", c.preeditForTest())
        m.answer()
        assertEquals("小", c.candidateWords().first())
        assertEquals("xiao", c.preeditForTest())
    }

    private val corpus = listOf(
        "nihao", "zhan", "fangan", "xianzai", "womenyiqiqu", "kongzhi", "zhongguoren", "shurufa", "xiexie", "meiyouwenti",
    )

    private fun firstCandidate(c: KeyboardController): Cand? {
        val head = c.decodeStateForTest().removePrefix("C:").substringBefore("|").substringBefore(",")
        val word = head.substringBeforeLast("/", "")
        return if (word.isEmpty()) null else Cand(word, head.substringAfterLast("/").toInt())
    }

    private fun readsWith(char: String, part: String): Boolean =
        T9Pinyin.SYLLABLES.any { reading -> reading.startsWith(part) && fullDict.exact(reading).any { it.word == char } }

    @Test fun full_dictionary_labels_follow_the_first_candidate_on_nine_keys_and_the_letters_on_alpha() {
        assumeFullAssets()
        var followed = 0
        var clipped = 0
        var completed = 0
        for (reading in corpus) for (length in 1..reading.length) {
            val letters = reading.substring(0, length)
            val digits = T9Pinyin.toT9(letters)
            val nine = controller(fullEngine)
            nine.type(digits)
            val label = nine.preeditForTest()
            assertSpells(digits, label)
            val top = firstCandidate(nine)
            if (top != null && top.word.codePoints().allMatch(Character::isIdeographic)) {
                val chars = top.word.codePoints().toArray().map { String(Character.toChars(it)) }
                val parts = ArrayList<String>()
                var covered = 0
                for (part in label.split('\'')) {
                    if (covered >= top.coveredLen) break
                    parts.add(part)
                    covered += part.length
                }
                assertEquals("$digits: '$label' covers ${top.word} exactly", top.coveredLen, covered)
                assertTrue("$digits: '$label' has at most one syllable per character of ${top.word}", parts.size <= chars.size)
                if (parts.size < chars.size) completed++
                for ((index, part) in parts.withIndex()) {
                    assertTrue("$digits: ${chars[index]} reads $part in '$label'", readsWith(chars[index], part))
                    if (part !in T9Pinyin.SYLLABLES) clipped++
                }
                followed++
            }
            val alpha = controller(fullEngine, nine = false)
            alpha.type(letters)
            assertEquals(T9Pinyin.preeditLetters(letters), alpha.preeditForTest())
        }
        assertTrue("the corpus exercises followed labels, was $followed", followed > corpus.size)
        assertTrue("the corpus exercises abbreviated first candidates", clipped > 0)
        assertTrue("the corpus exercises first candidates longer than the digits", completed > 0)
    }

    @Test fun full_dictionary_labels_spell_the_digits_while_lists_lag_and_settle_like_the_synchronous_path() {
        assumeFullAssets()
        for (reading in corpus) {
            val digits = T9Pinyin.toT9(reading)
            val q = Lane()
            val lagging = controller(fullEngine, lane = q.lane)
            q.drain()
            for (length in 1..digits.length) {
                lagging.type(digits.substring(length - 1, length))
                assertSpells(digits.substring(0, length), lagging.preeditForTest())
                if (length % 3 == 0) {
                    q.drain()
                    val sync = controller(fullEngine)
                    sync.type(digits.substring(0, length))
                    assertEquals(sync.preeditForTest(), lagging.preeditForTest())
                }
            }
            for (length in digits.length - 1 downTo 1) {
                lagging.onKey(backspace)
                assertSpells(digits.substring(0, length), lagging.preeditForTest())
            }
            q.drain()
            val sync = controller(fullEngine)
            sync.type(digits.substring(0, 1))
            assertEquals(sync.preeditForTest(), lagging.preeditForTest())
        }
    }

    private fun learnedEngine(reading: String, word: String): DictEngine {
        val model = UserModel()
        assertTrue(model.addManualWord(reading, word, System.currentTimeMillis()))
        return DictEngine(
            fullDict,
            BinaryDict.fromFile(File(fullAssets, FullDictTestAssets.T9)),
            CharBigramLM.fromFile(File(fullAssets, FullDictTestAssets.LM)),
            userModel = model,
            initialsDict = BinaryDict.fromFile(File(fullAssets, FullDictTestAssets.JIANPIN)),
        )
    }

    @Test fun a_first_candidate_reached_through_an_input_alias_is_spelled_as_typed() {
        assumeFullAssets()
        for ((digits, word, label) in listOf(Triple("3636", "嗯嗯", "en'en"), Triple("3663", "嗯呢", "en'ne"))) {
            assertTrue("$word is stored under another reading", fullDict.exact(lettersOf(label)).none { it.word == word })
            val host = Host()
            val c = controller(fullEngine, host = host)
            c.type(digits)
            assertEquals(word, firstCandidate(c)?.word)
            assertEquals(label, c.preeditForTest())
            assertEquals("en", c.expandedReadings().first())
            assertTrue("ng" !in c.expandedReadings())
            c.onKey(enter)
            assertEquals(listOf(lettersOf(label)), host.commits)
        }
    }

    @Test fun a_learned_first_candidate_reached_through_an_input_alias_is_spelled_as_typed() {
        assumeFullAssets()
        val engine = learnedEngine("cunenhua", "存嗯化")
        val host = Host()
        val c = controller(engine, host = host)
        c.type("28636482")
        assertEquals("存嗯化", firstCandidate(c)?.word)
        assertEquals("cun'en'hua", c.preeditForTest())
        c.onKey(enter)
        assertEquals(listOf("cunenhua"), host.commits)

        val tapped = controller(engine)
        tapped.type("28636482")
        tapped.onPreeditTap()
        assertTrue(tapped.preeditEditing())
        assertEquals("cunenhua", lettersOf(tapped.preeditForTest()))
    }

    private val aliasRows = listOf(
        EngineFixture.Row("ng", "嗯", 900), EngineFixture.Row("cun", "存", 800), EngineFixture.Row("hua", "化", 700),
    )

    private fun aliasEngine(model: UserModel? = null) = DictEngine(
        EngineFixture.build(aliasRows),
        EngineFixture.build(aliasRows.map { EngineFixture.Row(T9Pinyin.toT9(it.key), it.word, it.freq) }),
        null,
        userModel = model,
    )

    private fun learnedAliasEngine() = aliasEngine(
        UserModel().also { assertTrue(it.addManualWord("cunenhua", "存嗯化", System.currentTimeMillis())) },
    )

    @Test fun an_input_alias_is_spelled_as_typed_and_its_stored_reading_as_stored() {
        val alias = controller(aliasEngine())
        alias.type("36")
        assertEquals("嗯", firstCandidate(alias)?.word)
        assertEquals("en", alias.preeditForTest())

        val stored = controller(aliasEngine())
        stored.type("64")
        assertEquals("嗯", firstCandidate(stored)?.word)
        assertEquals("ng", stored.preeditForTest())
    }

    @Test fun an_input_alias_keeps_its_spelling_under_separators() {
        for (digits in listOf("28636'482", "286'36'482")) {
            val cut = controller(learnedAliasEngine())
            cut.type(digits)
            assertEquals("存嗯化", firstCandidate(cut)?.word)
            assertEquals("cun'en'hua", cut.preeditForTest())
        }
    }

    @Test fun an_input_alias_keeps_its_spelling_after_a_locked_reading() {
        val locked = controller(learnedAliasEngine())
        locked.type("28636482")
        locked.lock("cun")
        assertEquals("存嗯化", firstCandidate(locked)?.word)
        assertEquals("cun'en'hua", locked.preeditForTest())

        val both = controller(learnedAliasEngine())
        both.type("28636482")
        both.lock("cun")
        both.lock("en")
        assertEquals("存嗯化", firstCandidate(both)?.word)
        assertEquals("cun'en'hua", both.preeditForTest())
    }

    @Test fun the_alpha_label_shows_an_input_alias_and_its_stored_reading_as_typed() {
        assumeFullAssets()
        for (letters in listOf("enen", "ngng", "cunenhua")) {
            val c = controller(if (letters == "cunenhua") learnedEngine(letters, "存嗯化") else fullEngine, nine = false)
            c.type(letters)
            assertEquals(T9Pinyin.preeditLetters(letters), c.preeditForTest())
            assertEquals(letters, lettersOf(c.preeditForTest()))
        }
        for (letters in listOf("enen", "ngng")) {
            val c = controller(fullEngine, nine = false)
            c.type(letters)
            assertEquals("嗯嗯", firstCandidate(c)?.word)
        }
    }
}
