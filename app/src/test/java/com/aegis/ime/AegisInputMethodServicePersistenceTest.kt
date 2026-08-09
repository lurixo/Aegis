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

import android.content.Context
import android.os.Looper
import android.view.inputmethod.EditorInfo
import com.aegis.ime.backup.RestoreJournal
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
import org.robolectric.Shadows.shadowOf
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
        File(filesDir, "restore_journal").deleteRecursively()
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

    private fun prefs() =
        RuntimeEnvironment.getApplication().getSharedPreferences("aegis", Context.MODE_PRIVATE)

    private fun wordsOnDisk(): List<String> =
        UserModel().apply { load(userDb, sweepStale = false) }.userWordEntries().map { it.word }

    private fun aRestoreCaughtHalfWay(): RestoreJournal {
        UserModel().apply { addManualWord("bd", "本地词", 1L) }.save(userDb)
        val journal = RestoreJournal.open(filesDir, prefs())
        UserModel().apply { addManualWord("gd", "归档词", 2L) }.save(userDb)
        assertEquals("precondition: the archive already reached the file", listOf("归档词"), wordsOnDisk())
        return journal
    }

    @Test fun a_restore_the_process_never_finished_is_taken_back_before_the_keyboard_reads_a_thing() {
        aRestoreCaughtHalfWay()

        val service = started()

        assertEquals(
            "a restore nobody finished must be undone when the keyboard comes up, not when the user next opens backup",
            listOf("本地词"),
            wordsOnDisk(),
        )
        assertEquals("and the keyboard must be holding what was put back", listOf("本地词"), model(service).userWordEntries().map { it.word })
        assertFalse("the spent journal must be gone", File(filesDir, "restore_journal").exists())
    }

    @Test fun a_restore_that_did_finish_is_left_alone_when_the_keyboard_comes_up() {
        aRestoreCaughtHalfWay().markDone()

        val service = started()

        assertEquals(
            "a restore that got through must keep what it wrote",
            listOf("归档词"),
            wordsOnDisk(),
        )
        assertEquals(listOf("归档词"), model(service).userWordEntries().map { it.word })
        assertFalse("the spent journal must be gone", File(filesDir, "restore_journal").exists())
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
        drainWriteLane(service)

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
        drainWriteLane(service)

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
        drainWriteLane(service)

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

    private fun handRestoredFilesToTheKeyboard() {
        LiveUserData.restoreInProgress = true
        requireNotNull(LiveUserData.onRestored).invoke()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun archiveUserDbWith(reading: String, word: String) {
        UserModel().apply { addManualWord(reading, word, 2L) }.save(userDb)
        userDb.setLastModified(System.currentTimeMillis() + 60_000L)
    }

    @Test fun a_restore_replaces_the_dictionary_the_keyboard_is_still_holding() {
        val service = started()
        model(service).record(null, "恢复前打的", 1L)
        assertTrue("precondition: the keyboard holds a word it has not written", model(service).dirty)
        archiveUserDbWith("gd", "归档")

        handRestoredFilesToTheKeyboard()
        drainWriteLane(service)

        assertEquals(
            "a restore that never reaches the running dictionary is undone the moment the user types",
            listOf("归档"),
            model(service).readingSnapshot()["gd"],
        )
        assertEquals("the archive wins a restore, memory does not", 0.0, model(service).wordBoost("恢复前打的"), 0.0)
        assertFalse("the capture guard must come down once the restored stores are in memory", LiveUserData.restoreInProgress)
    }

    @Test fun a_restore_leaves_the_next_focused_field_nothing_to_reparse() {
        val service = started()
        archiveUserDbWith("gd", "归档")

        handRestoredFilesToTheKeyboard()
        drainWriteLane(service)

        assertEquals(
            "the restore already read the file, so focusing a field must not read it a second time",
            userDb.lastModified(),
            service.javaClass.getDeclaredField("userDbMtime").apply { isAccessible = true }.getLong(service),
        )
    }

    @Test fun a_restore_still_lands_when_the_write_lane_is_already_gone() {
        val service = started()
        archiveUserDbWith("gd", "归档")
        liveHost(service).stopSaving()

        handRestoredFilesToTheKeyboard()

        assertEquals(
            "a lane nobody can queue on must not swallow the restored dictionary",
            listOf("归档"),
            model(service).readingSnapshot()["gd"],
        )
        assertFalse("nor leave the capture guard standing forever", LiveUserData.restoreInProgress)
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

    private fun watermark(service: AegisInputMethodService, name: String): Long =
        service.javaClass.getDeclaredField(name).run {
            isAccessible = true
            getLong(service)
        }

    private fun learnedOnDisk(): List<String> =
        UserLearning().apply { load(userLearn) }.formedEntries().map { it.word }

    private fun promisesOnDisk(): List<Pair<String, String>> =
        UserModel().apply { load(userDb, sweepStale = false) }.tombstones()

    private fun aGluedWordOnDisk() {
        val now = System.currentTimeMillis()
        UserLearning().apply {
            repeat(8) {
                var prev: String? = null
                for ((word, reading) in listOf("你" to "ni", "呢" to "ne", "嗯" to "n")) {
                    observeCommit(prev, word, reading, now)
                    prev = word
                }
                observeBreak()
            }
            save(userLearn)
        }
        assertEquals("precondition: the learned word is on disk", listOf("你呢嗯"), learnedOnDisk())
    }

    private fun aWordListOwing(word: String, reading: String) {
        UserModel().apply {
            addManualWord("zwm", "张伟明", 1L)
            assertTrue(addTombstone(word, reading))
            save(userDb)
        }
        assertEquals("precondition: the word list carries the promise", listOf(word to reading), promisesOnDisk())
    }

    private fun age(stamp: Long) {
        assertTrue(userDb.setLastModified(stamp))
        assertTrue(userLearn.setLastModified(stamp))
    }

    @Test fun a_cold_start_finishes_a_deletion_the_last_session_could_not() {
        aGluedWordOnDisk()
        aWordListOwing("你呢嗯", "")
        val aged = System.currentTimeMillis() - 600_000L
        age(aged)

        val service = started()

        assertEquals("the promised deletion must be kept", emptyList<String>(), learnedOnDisk())
        assertEquals("and struck off once it is kept", emptyList<Pair<String, String>>(), promisesOnDisk())
        assertEquals(
            "the running keyboard must not still be offering the deleted word",
            emptyList<String>(),
            learning(service).formedEntries().map { it.word },
        )
        assertEquals(
            "keeping the promise rewrote the word list, so its watermark must be the one it wrote",
            userDb.lastModified(),
            watermark(service, "userDbMtime"),
        )
        assertEquals(
            "and the learned store's watermark must be the one it wrote too",
            userLearn.lastModified(),
            watermark(service, "userLearnMtime"),
        )
        assertTrue("precondition: the rewrite really moved the files on", userDb.lastModified() != aged)
    }

    @Test fun a_promise_naming_one_reading_takes_only_that_learned_entry() {
        aGluedWordOnDisk()
        UserLearning().apply {
            load(userLearn)
            observeCommit(null, "别", "bie", System.currentTimeMillis())
            save(userLearn)
        }
        aWordListOwing("你呢嗯", "ninen")

        started()

        assertEquals("the named entry goes", emptyList<String>(), learnedOnDisk())
        assertEquals(emptyList<Pair<String, String>>(), promisesOnDisk())
    }

    @Test fun a_word_list_picked_up_after_a_focus_change_has_its_promises_kept() {
        aGluedWordOnDisk()
        val service = started()
        assertEquals(listOf("你呢嗯"), learning(service).formedEntries().map { it.word })

        aWordListOwing("你呢嗯", "")
        assertTrue(userDb.setLastModified(System.currentTimeMillis() + 60_000L))

        service.onStartInput(editor(), false)
        drainWriteLane(service)

        assertEquals(
            "the word list that just arrived owed a deletion, so the keyboard must carry it out",
            emptyList<String>(),
            learning(service).formedEntries().map { it.word },
        )
        assertEquals(emptyList<Pair<String, String>>(), promisesOnDisk())
        assertEquals(
            "a promise kept on the lane rewrites the word list, so the watermark must follow it there",
            userDb.lastModified(),
            watermark(service, "userDbMtime"),
        )
        assertEquals(userLearn.lastModified(), watermark(service, "userLearnMtime"))
    }

    @Test fun a_learning_store_picked_up_after_a_focus_change_has_the_promises_kept_too() {
        aGluedWordOnDisk()
        val service = started()
        assertEquals(listOf("你呢嗯"), learning(service).formedEntries().map { it.word })

        assertTrue("precondition: the word list owes the deletion", model(service).addTombstone("你呢嗯", ""))
        assertTrue(liveHost(service).flush())
        assertEquals(listOf("你呢嗯" to ""), promisesOnDisk())
        assertEquals(
            "precondition: the word list itself has nothing new for the other gate to read",
            userDb.lastModified(),
            watermark(service, "userDbMtime"),
        )

        UserLearning().apply {
            load(userLearn)
            observeCommit(null, "别", "bie", System.currentTimeMillis())
            save(userLearn)
        }
        assertTrue(userLearn.setLastModified(System.currentTimeMillis() + 60_000L))

        service.onStartInput(editor(), false)
        drainWriteLane(service)

        assertEquals(
            "the learning store that just arrived carries a word the list promised to delete, so it must go now",
            emptyList<String>(),
            learning(service).formedEntries().map { it.word },
        )
        assertEquals("and the promise must be struck off once it is kept", emptyList<Pair<String, String>>(), model(service).tombstones())
        assertEquals(emptyList<Pair<String, String>>(), promisesOnDisk())
        assertEquals(emptyList<String>(), learnedOnDisk())
        assertEquals(
            "keeping the promise rewrote both files, so both watermarks must be the ones it wrote",
            userDb.lastModified(),
            watermark(service, "userDbMtime"),
        )
        assertEquals(userLearn.lastModified(), watermark(service, "userLearnMtime"))
    }

    @Test fun a_restore_finishes_the_deletions_the_archive_still_owes() {
        val service = started()
        aGluedWordOnDisk()
        aWordListOwing("你呢嗯", "")
        age(System.currentTimeMillis() - 600_000L)

        LiveUserData.restoreInProgress = true
        LiveUserData.onRestored?.invoke()
        shadowOf(Looper.getMainLooper()).idle()
        drainWriteLane(service)

        assertFalse("the capture guard must come down again", LiveUserData.restoreInProgress)
        assertEquals("a restored word list that owes a deletion must have it carried out", emptyList<String>(), learnedOnDisk())
        assertEquals(emptyList<Pair<String, String>>(), promisesOnDisk())
        assertEquals(
            "the restore rewrote both stores, so the watermarks must be the ones it wrote",
            userDb.lastModified(),
            watermark(service, "userDbMtime"),
        )
        assertEquals(userLearn.lastModified(), watermark(service, "userLearnMtime"))
    }
}
