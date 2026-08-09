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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LiveUserDictHostSaveQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: File
    private lateinit var learnFile: File
    private val model = UserModel()
    private val savedOn = Collections.synchronizedList(ArrayList<Thread>())
    private val saves = Collections.synchronizedList(ArrayList<Pair<Long?, Long?>>())
    private val helpers = ArrayList<ExecutorService>()
    private val hosts = ArrayList<LiveUserDictHost>()

    private fun liveHost(
        model: UserModel,
        userDb: File,
        userLearning: UserLearning? = null,
        userLearnFile: File? = null,
        onSaved: (Long?, Long?) -> Unit = { _, _ -> },
    ): LiveUserDictHost =
        LiveUserDictHost(model, userDb, userLearning, userLearnFile, onSaved).also { hosts += it }

    private fun watermark(userDbMtime: Long?, userLearnMtime: Long?) {
        savedOn += Thread.currentThread()
        saves += userDbMtime to userLearnMtime
    }

    private fun host(learning: UserLearning? = null): LiveUserDictHost {
        db = File(tmp.root, "userdb.txt")
        learnFile = File(tmp.root, "userlearn.txt")
        return liveHost(model, db, learning, learnFile, ::watermark)
    }

    private fun saveQueue(h: LiveUserDictHost): ExecutorService {
        val field = LiveUserDictHost::class.java.getDeclaredField("io")
        field.isAccessible = true
        return field.get(h) as ExecutorService
    }

    private fun blocked(h: LiveUserDictHost): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        saveQueue(h).execute {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        assertTrue("precondition: the user dictionary writer is occupied", entered.await(1, TimeUnit.SECONDS))
        return release
    }

    private fun wedged(h: LiveUserDictHost): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        saveQueue(h).execute {
            entered.countDown()
            release.await(45, TimeUnit.SECONDS)
        }
        assertTrue("precondition: the user dictionary writer is occupied", entered.await(1, TimeUnit.SECONDS))
        return release
    }

    private fun helper(): ExecutorService = Executors.newSingleThreadExecutor().also { helpers += it }

    private fun onDisk() = UserModel { 10L }.apply { if (db.exists()) load(db) }

    @After fun stopHelpers() {
        helpers.forEach { it.shutdownNow() }
        hosts.forEach { runCatching { it.stopSaving() } }
    }

    @Test fun the_end_of_an_input_session_never_writes_on_the_thread_that_asked() {
        val h = host()
        model.record(null, "你好", 1L)
        h.scheduleSave()
        assertTrue(h.flush())
        assertEquals(1, savedOn.size)
        assertFalse(
            "a queued save must land on the writer thread, never on the caller",
            savedOn.any { it === Thread.currentThread() },
        )
        assertTrue("the word must reach the file", onDisk().wordBoost("你好") > 0.0)
    }

    @Test fun an_explicit_edit_is_written_off_the_caller_thread_but_still_answered_to_it() {
        val h = host()
        assertTrue(h.addWord("nihao", "你好", now = 1L))
        assertEquals(listOf("你好"), onDisk().readingSnapshot()["nihao"])
        assertEquals(1, savedOn.size)
        assertFalse(
            "an explicit edit must be written off the caller thread",
            savedOn.any { it === Thread.currentThread() },
        )
    }

    @Test fun a_burst_of_input_sessions_collapses_into_one_write() {
        val h = host()
        val release = blocked(h)
        try {
            repeat(20) {
                model.record(null, "词$it", it.toLong())
                h.scheduleSave()
            }
            assertEquals("nothing may be written while the writer is occupied", 0, saves.size)
        } finally {
            release.countDown()
        }
        assertTrue(h.flush())
        assertEquals("twenty queued saves must collapse into one write", 1, saves.size)
        val reloaded = onDisk()
        for (i in 0 until 20) assertTrue("词$i must survive the collapse", reloaded.wordBoost("词$i") > 0.0)
    }

    @Test fun an_export_waits_for_a_queued_write_and_finds_it_on_disk() {
        val h = host()
        UserDictHot.host = h
        val release = blocked(h)
        val worker = helper()
        try {
            model.record(null, "待写", 1L)
            h.scheduleSave()
            assertFalse("the queued write has not reached the file yet", db.exists())

            val export = worker.submit<Boolean> { UserDictEdit.flushBeforeExport() }
            Thread.sleep(50)
            assertFalse("an export must not proceed past a queued user dictionary write", export.isDone)

            release.countDown()
            assertTrue("the flush must report success", export.get(5, TimeUnit.SECONDS))
            assertTrue("the queued write must be on disk before the export reads it", db.exists())
            assertTrue(onDisk().wordBoost("待写") > 0.0)
        } finally {
            release.countDown()
            UserDictHot.host = null
        }
    }

    @Test fun a_change_made_while_a_write_is_queued_is_still_written() {
        val h = host()
        val release = blocked(h)
        try {
            model.record(null, "先来", 1L)
            h.scheduleSave()
            model.record(null, "后到", 2L)
        } finally {
            release.countDown()
        }
        assertTrue(h.flush())
        val reloaded = onDisk()
        assertTrue("the change that started the write must be there", reloaded.wordBoost("先来") > 0.0)
        assertTrue("the change that raced it must not be lost", reloaded.wordBoost("后到") > 0.0)
    }

    @Test fun a_change_that_lands_while_the_writer_is_running_survives_the_next_flush() {
        db = File(tmp.root, "userdb.txt")
        learnFile = File(tmp.root, "userlearn.txt")
        var interleave = true
        val h = liveHost(model, db, null, learnFile) { userDbMtime, userLearnMtime ->
            watermark(userDbMtime, userLearnMtime)
            if (interleave) {
                interleave = false
                model.record(null, "插队", 3L)
            }
        }
        model.record(null, "第一", 1L)
        h.scheduleSave()
        assertTrue(h.flush())
        assertTrue("the interleaved change must be marked unsaved again", saves.size >= 2)
        val reloaded = onDisk()
        assertTrue(reloaded.wordBoost("第一") > 0.0)
        assertTrue("a change that landed during a write must not be lost", reloaded.wordBoost("插队") > 0.0)
    }

    @Test fun a_queued_write_that_failed_is_reported_and_carried_by_the_next_flush() {
        val blocker = tmp.newFile("blocker")
        db = File(blocker, "userdb.txt")
        learnFile = File(tmp.root, "userlearn.txt")
        val h = liveHost(model, db, null, learnFile, ::watermark)
        model.record(null, "留住", 1L)
        h.scheduleSave()
        assertFalse("a write that could not land must report failure", h.flush())
        assertTrue("a failed write must keep the change unsaved", model.dirty)
        assertEquals("a write that never happened must not move a watermark", 0, saves.size)

        blocker.delete()
        assertTrue("the next flush must retry the write", tmp.root.resolve("blocker").mkdirs())
        assertTrue(h.flush())
        assertFalse(model.dirty)
        assertTrue(onDisk().wordBoost("留住") > 0.0)
    }

    @Test fun many_commits_racing_the_writer_all_reach_the_file() {
        val h = host()
        val committed = 400
        val writer = Thread {
            repeat(committed) {
                model.record(null, "并发$it", it.toLong())
                if (it % 8 == 0) h.scheduleSave()
            }
        }
        val flusher = Thread { repeat(40) { h.flush() } }
        writer.start()
        flusher.start()
        writer.join(TimeUnit.SECONDS.toMillis(60))
        flusher.join(TimeUnit.SECONDS.toMillis(60))
        assertFalse("the committing thread was still running after sixty seconds", writer.isAlive)
        assertFalse("the flushing thread was still running after sixty seconds", flusher.isAlive)
        assertTrue(h.flush())
        val reloaded = onDisk()
        for (i in 0 until committed) {
            assertTrue("并发$i must survive a write racing the commits", reloaded.wordBoost("并发$i") > 0.0)
        }
    }

    @Test fun a_write_still_in_flight_is_visible_after_the_store_has_gone_clean() {
        db = File(tmp.root, "userdb.txt")
        learnFile = File(tmp.root, "userlearn.txt")
        val inWrite = CountDownLatch(1)
        val go = CountDownLatch(1)
        val h = liveHost(model, db, null, learnFile) { userDbMtime, userLearnMtime ->
            watermark(userDbMtime, userLearnMtime)
            inWrite.countDown()
            go.await(5, TimeUnit.SECONDS)
        }
        model.record(null, "写到一半", 1L)
        val pending = helper().submit<Boolean> { h.flush() }
        assertTrue("precondition: the writer reached the watermark callback", inWrite.await(2, TimeUnit.SECONDS))

        assertFalse("the store goes clean before the caller learns the write happened", model.dirty)
        assertTrue("a write still in flight must be visible so a reload gate can stand down", h.writing)

        go.countDown()
        assertTrue(pending.get(5, TimeUnit.SECONDS))
        assertFalse("the flag must clear once the write is done", h.writing)
    }

    @Test fun a_watermark_callback_that_asks_for_another_flush_does_not_wedge_the_writer() {
        db = File(tmp.root, "userdb.txt")
        learnFile = File(tmp.root, "userlearn.txt")
        var reentered = false
        lateinit var h: LiveUserDictHost
        h = liveHost(model, db, null, learnFile) { userDbMtime, userLearnMtime ->
            watermark(userDbMtime, userLearnMtime)
            if (!reentered) {
                reentered = true
                model.addManualWord("zaici", "再次", 2L)
                h.flush()
                assertTrue(
                    "the outer write is still running, so a reload must still be told to stand down",
                    h.writing,
                )
            }
        }
        model.record(null, "回环", 1L)
        val pending = helper().submit<Boolean> { h.addWord("nihao", "你好", now = 1L) }
        assertTrue("a re-entrant flush must not wedge the writer", pending.get(5, TimeUnit.SECONDS))
        assertTrue(reentered)
        val written = onDisk()
        assertEquals(listOf("你好"), written.readingSnapshot()["nihao"])
        assertEquals(
            "what the re-entrant flush was asked to write must be on disk too",
            listOf("再次"),
            written.readingSnapshot()["zaici"],
        )
    }

    @Test(timeout = 60_000) fun an_edit_the_writer_never_answers_is_reported_as_a_failure_instead_of_wedging_the_caller() {
        val h = host()
        val release = wedged(h)
        try {
            val started = System.nanoTime()
            assertFalse(
                "a caller that never got an answer must be told the write failed",
                h.addWord("nihao", "你好", now = 1L),
            )
            val waited = (System.nanoTime() - started) / 1_000_000L
            assertTrue("the caller waited ${waited}ms on a writer that never came back", waited < 30_000L)
            assertTrue("and the word stays queued rather than being dropped", model.dirty)
        } finally {
            release.countDown()
        }

        assertTrue("the write the caller gave up on is still carried by the next flush", h.flush())
        assertEquals(listOf("你好"), onDisk().readingSnapshot()["nihao"])
    }

    @Test fun stopping_the_writer_still_lets_a_last_edit_be_written_and_reported() {
        val h = host()
        h.stopSaving()
        assertTrue("an edit after the writer stopped must still be persisted", h.addWord("nihao", "你好", now = 1L))
        assertEquals(listOf("你好"), onDisk().readingSnapshot()["nihao"])
    }
}
