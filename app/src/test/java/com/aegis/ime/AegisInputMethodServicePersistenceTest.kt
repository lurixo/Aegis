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

package com.aegis.ime

import android.view.inputmethod.EditorInfo
import com.aegis.ime.user.LiveUserDictHost
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserLearnEdit
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AegisInputMethodServicePersistenceTest {

    private val filesDir: File get() = RuntimeEnvironment.getApplication().filesDir
    private val userDb: File get() = File(filesDir, "userdb.txt")
    private val userLearn: File get() = File(filesDir, "userlearn.txt")
    private var release: CountDownLatch? = null

    @Before fun clean() {
        UserDictHot.host = null
        LiveUserData.onRestored = null
        LiveUserData.onBeforeExport = null
        LiveUserData.onBeforeRestore = null
        LiveUserData.restoreInProgress = false
        userDb.delete()
        userLearn.delete()
    }

    @After fun letGo() {
        release?.countDown()
        UserDictHot.host = null
        LiveUserData.onBeforeExport = null
        LiveUserData.onBeforeRestore = null
    }

    private fun started(): AegisInputMethodService {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).create().get()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (Thread.getAllStackTraces().keys.none { it.name == "aegis-dict-load" && it.isAlive }) break
            Thread.yield()
        }
        assertTrue(
            "precondition: the cold start must have finished",
            Thread.getAllStackTraces().keys.none { it.name == "aegis-dict-load" && it.isAlive },
        )
        return service
    }

    private fun userStoresLoaded(service: AegisInputMethodService): Boolean =
        service.javaClass.getDeclaredField("userStoresLoaded").run {
            isAccessible = true
            getBoolean(service)
        }

    private fun liveHost(service: AegisInputMethodService): LiveUserDictHost {
        val delegate = service.javaClass.getDeclaredField("liveUserDictHost\$delegate").run {
            isAccessible = true
            get(service) as Lazy<*>
        }
        return delegate.value as LiveUserDictHost
    }

    private fun model(service: AegisInputMethodService): UserModel =
        service.javaClass.getDeclaredField("userModel").run {
            isAccessible = true
            get(service) as UserModel
        }

    private fun learning(service: AegisInputMethodService): UserLearning =
        service.javaClass.getDeclaredField("userLearning").run {
            isAccessible = true
            get(service) as UserLearning
        }

    private fun occupyWriter(service: AegisInputMethodService) {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        release = gate
        val field = LiveUserDictHost::class.java.getDeclaredField("io").apply { isAccessible = true }
        (field.get(liveHost(service)) as ExecutorService).execute {
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
        }
        assertTrue("precondition: the user dictionary writer is occupied", entered.await(2, TimeUnit.SECONDS))
    }

    private fun glue(learned: UserLearning) {
        repeat(8) {
            var prev: String? = null
            for ((word, reading) in listOf("你" to "ni", "呢" to "ne", "嗯" to "n")) {
                learned.observeCommit(prev, word, reading, 1_700_000_000_000L)
                prev = word
            }
            learned.observeBreak()
        }
    }

    private fun drainWriteLane(service: AegisInputMethodService) {
        val host = liveHost(service)
        val io = host.javaClass.getDeclaredField("io").run {
            isAccessible = true
            get(host) as ExecutorService
        }
        io.submit { }.get(10, TimeUnit.SECONDS)
    }

    private fun editor() = EditorInfo().apply { packageName = "com.example.app" }

    @Test fun the_cold_start_leaves_the_live_host_serving() {
        val service = started()
        assertTrue("the cold start finished, so the reload gate must be open", userStoresLoaded(service))
        assertTrue("the live host must be serving once the cold start finished", UserDictHot.host === liveHost(service))
    }

    @Test fun the_end_of_an_input_session_hands_the_write_over_instead_of_doing_it() {
        val service = started()
        occupyWriter(service)
        model(service).record(null, "记住", 1L)

        service.onFinishInput()

        assertFalse("the input session must not have written the file itself", userDb.exists())
        release?.countDown()
        assertTrue(liveHost(service).flush())
        assertTrue("the handed-over write must still land", userDb.exists())
        assertTrue(UserModel { 10L }.apply { load(userDb) }.wordBoost("记住") > 0.0)
    }

    @Test fun the_end_of_an_input_session_hands_over_the_learning_store_too() {
        val service = started()
        occupyWriter(service)
        val learned = learning(service)
        glue(learned)
        assertTrue("the learning store must have something to write", learned.dirty)

        service.onFinishInput()

        assertFalse("the input session must not have written the learning file itself", userLearn.exists())
        release?.countDown()
        assertTrue(liveHost(service).flush())
        assertTrue("the handed-over learning write must still land", userLearn.exists())
    }

    @Test fun teardown_writes_what_the_last_input_session_left_unsaved() {
        val service = started()
        model(service).record(null, "临别", 1L)
        assertTrue(model(service).dirty)

        service.onDestroy()

        assertTrue("nothing may be left unwritten when the process goes away", userDb.exists())
        assertTrue(UserModel { 10L }.apply { load(userDb) }.wordBoost("临别") > 0.0)
    }

    @Test fun teardown_drains_a_write_the_input_session_had_already_queued() {
        val service = started()
        occupyWriter(service)
        model(service).record(null, "排队", 1L)
        service.onFinishInput()
        assertFalse(userDb.exists())
        release?.countDown()

        service.onDestroy()

        assertTrue("a queued write must be on disk once teardown returns", userDb.exists())
        assertTrue(UserModel { 10L }.apply { load(userDb) }.wordBoost("排队") > 0.0)
    }

    @Test fun a_store_that_could_not_be_read_is_never_written_back() {
        userDb.writeText("aegis-userdb 99\nW\t坏\t1\t1\n")
        val service = started()
        assertFalse("a store that failed to load must say so", model(service).readable)
        model(service).record(null, "不可写回", 1L)

        service.onFinishInput()
        service.onDestroy()

        assertEquals(
            "a store that failed to load must be left exactly as it was found",
            "aegis-userdb 99\nW\t坏\t1\t1\n",
            userDb.readText(),
        )
    }

    @Test fun a_learning_file_changed_outside_is_picked_up_although_the_dictionary_could_not_be_read() {
        userDb.writeText("aegis-userdb 99\nW\t坏\t1\t1\n")
        val service = started()
        assertFalse("precondition: the dictionary really could not be read", model(service).readable)

        val recently = System.currentTimeMillis()
        UserLearning().apply {
            repeat(8) {
                var prev: String? = null
                for ((word, reading) in listOf("你" to "ni", "呢" to "ne", "嗯" to "n")) {
                    observeCommit(prev, word, reading, recently)
                    prev = word
                }
                observeBreak()
            }
        }.save(userLearn)
        userLearn.setLastModified(recently + 60_000L)

        service.onStartInput(editor(), false)

        assertEquals(
            "one store that could not be read must not stop the other from being picked up",
            listOf("你呢嗯"),
            learning(service).formedWordsFor("ninen"),
        )
    }

    @Test fun a_dictionary_that_could_not_be_read_is_reparsed_once_not_on_every_input_session() {
        userDb.writeText("aegis-userdb 99\nW\t坏\t1\t1\n")
        val service = started()
        assertFalse("precondition: the dictionary really could not be read", model(service).readable)

        val watermark = service.javaClass.getDeclaredField("userDbMtime").apply { isAccessible = true }
        assertEquals("precondition: the failed cold-start load left the watermark behind", 0L, watermark.getLong(service))

        service.onStartInput(editor(), false)

        assertEquals(
            "the watermark must move even when the file would not parse, or every focused field reparses it whole on the main thread",
            userDb.lastModified(),
            watermark.getLong(service),
        )
    }

    @Test fun an_outside_change_must_not_wipe_out_words_the_keyboard_has_not_written_yet() {
        val service = started()
        assertTrue("precondition: the dictionary loaded fine", model(service).readable)

        model(service).record(null, "还没落盘", 1L)
        assertTrue("precondition: the word is only in memory so far", model(service).dirty)

        UserModel().apply { addManualWord("wb", "外部", 2L) }.save(userDb)
        userDb.setLastModified(System.currentTimeMillis() + 60_000L)
        service.onStartInput(editor(), false)

        assertTrue(
            "picking up a file changed outside must not throw away what has not been written yet",
            model(service).wordBoost("还没落盘") > 0.0,
        )
    }

    @Test fun a_restore_in_flight_is_not_written_over_by_the_end_of_an_input_session() {
        val service = started()
        glue(learning(service))
        assertTrue("precondition: the keyboard holds learned words it has not written", learning(service).dirty)

        LiveUserData.restoreInProgress = true
        try {
            service.onFinishInput()
            drainWriteLane(service)
            assertFalse(
                "a restore is replacing these files; writing the pre-restore state over them undoes it silently",
                userLearn.exists(),
            )
        } finally {
            LiveUserData.restoreInProgress = false
        }
        assertTrue("the words are still queued for after the restore", learning(service).dirty)
    }

    @Test fun a_restore_in_flight_is_not_raced_by_a_reload_from_the_input_session() {
        val service = started()
        assertTrue("precondition: the dictionary loaded fine", model(service).readable)

        UserModel().apply { addManualWord("wb", "外部", 2L) }.save(userDb)
        userDb.setLastModified(System.currentTimeMillis() + 60_000L)

        LiveUserData.restoreInProgress = true
        try {
            service.onStartInput(editor(), false)
            assertNull(
                "a restore owns the stores while it runs, so focusing a field must not read the file underneath it",
                model(service).readingSnapshot()["wb"],
            )
        } finally {
            LiveUserData.restoreInProgress = false
        }
    }

    @Test fun a_dictionary_repaired_from_outside_is_picked_up_even_after_the_keyboard_gave_up_on_it() {
        userDb.writeText("aegis-userdb 99\nW\t坏\t1\t1\n")
        val service = started()
        assertFalse("precondition: the dictionary could not be read", model(service).readable)

        model(service).record(null, "打过字", 1L)
        assertTrue("precondition: one keystroke pins dirty, and a refused save can never clear it", model(service).dirty)
        service.onStartInput(editor(), false)

        UserModel().apply { addManualWord("xiu", "修好", 1L) }.save(userDb)
        userDb.setLastModified(System.currentTimeMillis() + 60_000L)
        service.onStartInput(editor(), false)

        assertTrue("a store nobody can write to must not also refuse to read a repaired file", model(service).readable)
        assertEquals(listOf("修好"), model(service).readingSnapshot()["xiu"])
    }

    @Test fun clearing_the_learned_words_while_the_dictionary_is_unreadable_is_not_undone_by_the_keyboard() {
        userDb.writeText("aegis-userdb 99\nW\t坏\t1\t1\n")
        val service = started()
        glue(learning(service))
        service.onFinishInput()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && !userLearn.exists()) Thread.yield()
        assertTrue("precondition: the learned words really did reach the disk", userLearn.exists())

        assertTrue("the settings page must be able to clear them", UserLearnEdit.clear(userLearn))

        model(service).record(null, "接着打字", 2L)
        service.onFinishInput()
        liveHost(service).flush()

        assertTrue(
            "an explicit clear must not be undone by the next thing the user types",
            UserLearning().apply { load(userLearn) }.isEmpty(),
        )
    }

    @Test fun a_user_dictionary_that_could_not_be_read_does_not_take_the_learning_store_down_with_it() {
        userDb.writeText("aegis-userdb 99\nW\t坏\t1\t1\n")
        val service = started()
        assertFalse("precondition: the dictionary really did fail to load", model(service).readable)
        assertTrue(
            "a dictionary that could not be read must still leave the keyboard serving as the single writer",
            UserDictHot.host === liveHost(service),
        )
        glue(learning(service))
        assertTrue("precondition: the learning store has something worth keeping", learning(service).dirty)

        service.onFinishInput()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && !userLearn.exists()) Thread.yield()

        assertTrue("the healthy learning store must still be written", userLearn.exists())
        assertFalse("and it must not still be waiting to be written", learning(service).dirty)
        assertEquals(
            "the unreadable dictionary is still left exactly as it was found",
            "aegis-userdb 99\nW\t坏\t1\t1\n",
            userDb.readText(),
        )
    }

    @Test fun the_end_of_an_input_session_is_what_puts_the_words_on_disk() {
        val service = started()
        model(service).record(null, "会话末尾", 1L)
        glue(learning(service))

        service.onFinishInput()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && !(userDb.exists() && userLearn.exists())) Thread.yield()

        assertTrue("finishing an input session must persist the dictionary by itself", userDb.exists())
        assertTrue("finishing an input session must persist the learning store by itself", userLearn.exists())
        assertTrue(UserModel { 10L }.apply { load(userDb) }.wordBoost("会话末尾") > 0.0)
    }

    @Test fun the_watermark_only_moves_for_the_file_the_handed_over_write_touched() {
        val service = started()
        val marker = 1_600_000_000_000L
        service.javaClass.getDeclaredField("userDbMtime").apply { isAccessible = true }.setLong(service, marker)
        glue(learning(service))

        service.onFinishInput()
        assertTrue(liveHost(service).flush())

        val userDbMtime = service.javaClass.getDeclaredField("userDbMtime")
            .apply { isAccessible = true }.getLong(service)
        assertTrue("the learning file is the one that was written", userLearn.exists())
        assertEquals("only the learning file was written, so the dictionary watermark must stay", marker, userDbMtime)
    }

    @Test fun a_restart_of_the_input_session_does_not_lose_the_handed_over_write() {
        val service = started()
        occupyWriter(service)
        model(service).record(null, "跨会话", 1L)
        service.onFinishInput()
        service.onStartInput(editor(), false)
        release?.countDown()

        assertTrue(liveHost(service).flush())
        assertTrue(UserModel { 10L }.apply { load(userDb) }.wordBoost("跨会话") > 0.0)
    }
}
