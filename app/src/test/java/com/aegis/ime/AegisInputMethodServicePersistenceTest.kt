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
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun userDbLoaded(service: AegisInputMethodService): Boolean =
        service.javaClass.getDeclaredField("userDbLoaded").run {
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

    private fun editor() = EditorInfo().apply { packageName = "com.example.app" }

    @Test fun the_cold_start_leaves_the_live_host_serving() {
        val service = started()
        assertTrue("both user stores loaded, so the gate must be open", userDbLoaded(service))
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
        assertFalse("a store that failed to load must leave the gate shut", userDbLoaded(service))
        model(service).record(null, "不可写回", 1L)

        service.onFinishInput()
        service.onDestroy()

        assertEquals(
            "a store that failed to load must be left exactly as it was found",
            "aegis-userdb 99\nW\t坏\t1\t1\n",
            userDb.readText(),
        )
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
