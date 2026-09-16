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

import android.os.Looper
import android.os.Message
import android.os.MessageQueue
import android.os.Process
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.aegis.ime.ime.DecodeLane
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.PanelEditable
import com.aegis.ime.ime.PanelTextInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DecodeWorkerWiringTest {
    private val services = ArrayList<AegisInputMethodService>()

    @After fun stop() {
        services.forEach { it.onDestroy() }
        services.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun start(): AegisInputMethodService {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).create().get()
        services += service
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && Thread.getAllStackTraces().keys.any { it.name == "aegis-dict-load" && it.isAlive }) Thread.yield()
        shadowOf(Looper.getMainLooper()).idle()
        return service
    }

    private fun <T> field(service: AegisInputMethodService, name: String): T =
        service.javaClass.getDeclaredField(name).run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(service) as T
        }

    @Test fun the_decode_worker_runs_at_display_priority() {
        val service = start()
        val worker = field<ExecutorService>(service, "decodeWorker")
        val priority = worker.submit(Callable { Process.getThreadPriority(Process.myTid()) }).get(10, TimeUnit.SECONDS)
        assertEquals(Process.THREAD_PRIORITY_DISPLAY, priority)
    }

    @Test fun decode_results_reach_the_main_thread_as_asynchronous_messages() {
        val service = start()
        val worker = field<ExecutorService>(service, "decodeWorker")
        val lane = field<DecodeLane>(service, "decodeLane")
        var applied = false
        lane.submit(compute = { 1 }, apply = { applied = true })
        worker.submit {}.get(10, TimeUnit.SECONDS)
        val head = MessageQueue::class.java.getDeclaredField("mMessages").run {
            isAccessible = true
            get(Looper.getMainLooper().queue) as Message?
        }
        assertTrue("the result is queued for the main thread", head != null && !applied)
        assertTrue("it is not held back behind a frame's sync barrier", head!!.isAsynchronous)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(applied)
        assertFalse(lane.pending)
    }

}
