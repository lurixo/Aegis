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

    @Test fun an_undisturbed_reload_still_replaces_the_dictionary() {
        val file = userDbWith("wb" to "外部")
        val model = UserModel().apply { addManualWord("bd", "本地", 1L) }

        assertTrue("an undisturbed read must adopt what it read", model.reloadIfUnchanged(file))

        assertEquals(listOf("外部"), model.readingSnapshot()["wb"])
        assertNull("the reload replaces the store, it does not merge into it", model.readingSnapshot()["bd"])
    }

    @Test fun a_restore_replaces_the_dictionary_even_when_the_keyboard_holds_unwritten_words() {
        val file = userDbWith("wb" to "外部")
        val model = UserModel().apply { record(null, "内存里的", 1L) }
        assertTrue("precondition: the keyboard holds something it has not written", model.dirty)

        model.reload(file)

        assertEquals("an archive must win over memory, that is what restoring means", listOf("外部"), model.readingSnapshot()["wb"])
        assertEquals(0.0, model.wordBoost("内存里的"), 0.0)
    }
}
