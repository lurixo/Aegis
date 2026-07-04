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

package com.aegis.ime.user

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class UserStoreReadLaneTest {

    @get:Rule val temp = TemporaryFolder()

    private val readStarted = CountDownLatch(1)
    private val releaseRead = CountDownLatch(1)

    @After fun letTheReadFinish() {
        releaseRead.countDown()
    }

    private inner class HeldFile(path: String) : File(path) {
        override fun length(): Long {
            readStarted.countDown()
            releaseRead.await()
            return super.length()
        }
    }

    private fun userDbWith(vararg entries: Pair<String, String>): File {
        val file = temp.newFile("userdb.txt")
        UserModel().apply { for ((reading, word) in entries) addManualWord(reading, word, 1L) }.save(file)
        return file
    }

    private fun userLearnWithGlue(): File {
        val file = temp.newFile("userlearn.txt")
        UserLearning().apply { glue(this, listOf("你" to "ni", "呢" to "ne", "嗯" to "n")) }.save(file)
        return file
    }

    private fun glue(learning: UserLearning, run: List<Pair<String, String>>) {
        val recently = System.currentTimeMillis()
        repeat(8) {
            var prev: String? = null
            for ((word, reading) in run) {
                learning.observeCommit(prev, word, reading, recently)
                prev = word
            }
            learning.observeBreak()
        }
    }

    private fun writeLaneOf(host: LiveUserDictHost): ExecutorService =
        LiveUserDictHost::class.java.getDeclaredField("io").run {
            isAccessible = true
            get(host) as ExecutorService
        }

    private fun onItsOwnThread(name: String, work: () -> Unit): Thread =
        Thread(work, name).apply { isDaemon = true }.also { it.start() }

    private fun assertFinishesWhileTheFileIsBeingRead(what: String, work: () -> Unit) {
        assertTrue("precondition: the read really is in flight", readStarted.await(10, TimeUnit.SECONDS))
        val done = CountDownLatch(1)
        onItsOwnThread("aegis-test-typist") { work(); done.countDown() }
        assertTrue(
            "$what must not wait for the file to be parsed; it is still being read at this point",
            done.await(10, TimeUnit.SECONDS),
        )
    }

    @Test fun a_user_dictionary_reload_parses_outside_the_lock_the_keyboard_types_through() {
        val file = userDbWith("wb" to "外部")
        val model = UserModel().apply { load(file) }
        val reader = onItsOwnThread("aegis-test-reader") { model.reload(HeldFile(file.path)) }

        assertFinishesWhileTheFileIsBeingRead("a keystroke") { model.record(null, "打字", 1L) }
        assertFinishesWhileTheFileIsBeingRead("ranking a candidate") { model.wordBoost("外部") }
        assertFinishesWhileTheFileIsBeingRead("a candidate lookup") { model.readingSnapshot() }

        releaseRead.countDown()
        reader.join(10_000)
        assertFalse(reader.isAlive)
    }

    @Test fun a_learning_store_reparse_parses_outside_the_lock_the_keyboard_types_through() {
        val file = userLearnWithGlue()
        val learning = UserLearning().apply { load(file) }
        val reader = onItsOwnThread("aegis-test-reader") { learning.loadIfUnchanged(HeldFile(file.path)) }

        assertFinishesWhileTheFileIsBeingRead("a committed word") {
            learning.observeCommit(null, "你", "ni", System.currentTimeMillis())
        }
        assertFinishesWhileTheFileIsBeingRead("a candidate lookup") { learning.formedWordsFor("ninen") }

        releaseRead.countDown()
        reader.join(10_000)
        assertFalse(reader.isAlive)
    }

    @Test fun a_word_formed_while_the_learning_store_is_first_read_is_not_thrown_away() {
        val file = userLearnWithGlue()
        val learning = UserLearning()
        val reader = onItsOwnThread("aegis-test-reader") { learning.load(HeldFile(file.path)) }
        assertTrue("precondition: the read really is in flight", readStarted.await(10, TimeUnit.SECONDS))

        val typed = CountDownLatch(1)
        val typist = onItsOwnThread("aegis-test-typist") {
            glue(learning, listOf("大" to "da", "家" to "jia"))
            typed.countDown()
        }
        awaitTypedOrHeldOff(typist, typed)

        releaseRead.countDown()
        reader.join(10_000)
        typist.join(10_000)

        assertEquals("the store that was read must be in place", listOf("你呢嗯"), learning.formedWordsFor("ninen"))
        assertEquals(
            "a word formed while the store was being read must not go down with the read",
            listOf("大家"),
            learning.formedWordsFor("dajia"),
        )
        assertTrue("and it must still be owed to the file", learning.dirty)
    }

    private fun awaitTypedOrHeldOff(typist: Thread, typed: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (typed.count > 0L && typist.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.yield()
        }
    }

    @Test fun handing_a_read_to_the_write_lane_does_not_wait_for_the_lane() {
        val host = LiveUserDictHost(UserModel(), temp.newFile("userdb.txt"))
        try {
            val occupied = CountDownLatch(1)
            writeLaneOf(host).execute { occupied.countDown(); releaseRead.await() }
            assertTrue("precondition: the lane really is busy", occupied.await(10, TimeUnit.SECONDS))

            val ran = CountDownLatch(1)
            val returned = CountDownLatch(1)
            onItsOwnThread("aegis-test-caller") { host.handOff { ran.countDown() }; returned.countDown() }

            assertTrue(
                "handing work to a busy lane must return at once; waiting parks whoever is queueing behind the writes",
                returned.await(10, TimeUnit.SECONDS),
            )
            assertEquals("and it must not run the work on the caller's thread instead", 1L, ran.count)

            releaseRead.countDown()
            assertTrue("the handed-off work must still land", ran.await(10, TimeUnit.SECONDS))
        } finally {
            releaseRead.countDown()
            host.stopSaving()
        }
    }

    @Test fun a_word_typed_while_the_dictionary_is_being_read_survives_the_reload() {
        val file = userDbWith("wb" to "外部")
        val model = UserModel().apply { load(file) }
        val declined = booleanArrayOf(true)
        val reader = onItsOwnThread("aegis-test-reader") {
            declined[0] = model.reloadIfUnchanged(HeldFile(file.path))
        }

        assertFinishesWhileTheFileIsBeingRead("a keystroke") { model.record(null, "还没落盘", 1L) }
        releaseRead.countDown()
        reader.join(10_000)

        assertFalse("a reload that lost the race must say so", declined[0])
        assertTrue(
            "picking up an outside change must not throw away what has not been written yet",
            model.wordBoost("还没落盘") > 0.0,
        )
    }

    @Test fun a_learned_word_formed_while_the_store_is_being_read_survives_the_load() {
        val file = userLearnWithGlue()
        val learning = UserLearning()
        val declined = booleanArrayOf(true)
        val reader = onItsOwnThread("aegis-test-reader") {
            declined[0] = learning.loadIfUnchanged(HeldFile(file.path))
        }

        assertFinishesWhileTheFileIsBeingRead("a committed word") {
            glue(learning, listOf("大" to "da", "家" to "jia"))
        }
        releaseRead.countDown()
        reader.join(10_000)

        assertFalse("a load that lost the race must say so", declined[0])
        assertEquals(listOf("大家"), learning.formedWordsFor("dajia"))
    }

    @Test fun an_undisturbed_reload_still_replaces_the_dictionary() {
        val file = userDbWith("wb" to "外部")
        val model = UserModel().apply { addManualWord("bd", "本地", 1L) }

        assertTrue("an undisturbed read must adopt what it read", model.reloadIfUnchanged(file))

        assertEquals(listOf("外部"), model.readingSnapshot()["wb"])
        assertNull("the reload replaces the store, it does not merge into it", model.readingSnapshot()["bd"])
    }

    @Test fun an_undisturbed_load_still_replaces_the_learning_store() {
        val file = userLearnWithGlue()
        val learning = UserLearning()

        assertTrue("an undisturbed read must adopt what it read", learning.loadIfUnchanged(file))

        assertEquals(listOf("你呢嗯"), learning.formedWordsFor("ninen"))
    }

    @Test fun a_restore_replaces_the_dictionary_even_when_the_keyboard_holds_unwritten_words() {
        val file = userDbWith("wb" to "外部")
        val model = UserModel().apply { record(null, "内存里的", 1L) }
        assertTrue("precondition: the keyboard holds something it has not written", model.dirty)

        model.reload(file)

        assertEquals("an archive must win over memory, that is what restoring means", listOf("外部"), model.readingSnapshot()["wb"])
        assertEquals(0.0, model.wordBoost("内存里的"), 0.0)
    }

    @Test fun a_file_that_will_not_parse_leaves_the_learning_store_marked_unreadable() {
        val file = temp.newFile("userlearn.txt").apply { writeText("aegis-userlearn 99\n") }
        val learning = UserLearning()

        assertFalse("a store that could not be read must not claim it adopted anything", learning.loadIfUnchanged(file))

        assertFalse("and it must say so, or a later save writes over a file nobody could read", learning.readable)
    }
}
