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

class LiveUserDictHostReadingRepairTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = 1_700_000_000_000L
    private val db: File get() = File(tmp.root, "userdb.txt")
    private val model = UserModel { clock }
    private val saves = Collections.synchronizedList(ArrayList<Pair<Long?, Long?>>())
    private val hosts = ArrayList<LiveUserDictHost>()
    private val helpers = ArrayList<ExecutorService>()

    @After fun stop() {
        helpers.forEach { it.shutdownNow() }
        hosts.forEach { runCatching { it.stopSaving() } }
        LiveUserData.restoreInProgress = false
    }

    private fun host(): LiveUserDictHost =
        LiveUserDictHost(model, db, onSaved = { userDb, userLearn -> saves += userDb to userLearn }).also { hosts += it }

    private fun drained(h: LiveUserDictHost) {
        repeat(2) {
            val done = CountDownLatch(1)
            assertTrue("the writer must take the barrier", h.handOff { done.countDown() })
            assertTrue(done.await(30, TimeUnit.SECONDS))
        }
    }

    private fun blocked(h: LiveUserDictHost): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.handOff {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        assertTrue("precondition: the user dictionary writer is occupied", entered.await(1, TimeUnit.SECONDS))
        return release
    }

    private fun waitFor(done: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < until) {
            if (done()) return true
            Thread.sleep(10)
        }
        return done()
    }

    private fun onDisk() = UserModel { clock }.apply { load(db, sweepStale = false) }

    private fun drop(reading: String, word: String) = UserModel.ReadingRepair(reading, word, null)

    @Test fun anAppliedRepairIsWrittenAndReported() {
        model.recordWord("jiu", "就是", clock, incrementCount = true)
        model.recordWord("qianmian", "片面", clock, incrementCount = true)
        val h = host()
        assertTrue(h.repairReadings(listOf(drop("jiu", "就是"), UserModel.ReadingRepair("qianmian", "片面", "pianmian"))))
        drained(h)
        assertEquals(mapOf("pianmian" to listOf("片面")), onDisk().readingSnapshot())
        assertTrue("the usage of the dropped reading's word reaches the file", onDisk().wordBoost("就是") > 0.0)
        assertTrue("the write is reported", saves.any { it.first != null })
        assertFalse(model.dirty)
    }

    @Test fun nothingIsAppliedWhileARestoreIsInProgress() {
        model.recordWord("jiu", "就是", clock, incrementCount = true)
        val h = host()
        LiveUserData.restoreInProgress = true
        assertTrue(h.repairReadings(listOf(drop("jiu", "就是"))))
        drained(h)
        assertEquals(mapOf("jiu" to listOf("就是")), model.readingSnapshot())
        assertTrue("nothing is written", saves.isEmpty())
        assertFalse(db.exists())
    }

    @Test fun aStoreThatCouldNotBeReadIsNotRepaired() {
        model.recordWord("jiu", "就是", clock, incrementCount = true)
        db.writeText("this is not an aegis user dictionary\n")
        runCatching { model.load(db) }
        val h = host()
        assertTrue(h.repairReadings(listOf(drop("jiu", "就是"))))
        drained(h)
        assertEquals(mapOf("jiu" to listOf("就是")), model.readingSnapshot())
        assertEquals("this is not an aegis user dictionary\n", db.readText())
    }

    @Test fun anEditThatLandsWhileTheRepairIsQueuedKeepsBothEffects() {
        model.recordWord("jiu", "就是", clock, incrementCount = true)
        model.recordWord("yi", "一个", clock, incrementCount = true)
        val h = host()
        val repairs = model.unmarkedReadings().map { (r, w) -> drop(r, w) }
        val release = blocked(h)
        assertTrue(h.repairReadings(repairs))
        val helper = Executors.newSingleThreadExecutor().also { helpers += it }
        val adding = helper.submit<Boolean> { h.addWord("jiu", "就是", clock) && h.addWord("xinci", "新词", clock) }
        assertTrue(
            "precondition: the hand-added pair is in the store before the repair runs",
            waitFor { model.manualSnapshot()["jiu"]?.contains("就是") == true },
        )
        release.countDown()
        assertTrue(adding.get(30, TimeUnit.SECONDS))
        drained(h)
        val disk = onDisk()
        assertEquals(mapOf("jiu" to listOf("就是"), "xinci" to listOf("新词")), disk.readingSnapshot())
        assertEquals(mapOf("jiu" to setOf("就是"), "xinci" to setOf("新词")), disk.manualSnapshot())
        assertTrue(disk.wordBoost("一个") > 0.0)
    }
}
