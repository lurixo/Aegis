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

    @Test fun switching_the_text_source_forgets_the_cached_cursor_context() {
        val service = start()
        val controller = field<KeyboardController>(service, "controller")
        val cache = KeyboardController::class.java.getDeclaredField("beforeCursor").apply { isAccessible = true }
        val panel = field<PanelTextInput>(service, "panelInput")
        val editable = object : PanelEditable {
            override fun snapshot(): String = "面板"
            override fun selectionStart(): Int = 2
            override fun selectionEnd(): Int = 2
            override fun setSelection(start: Int, end: Int) {}
            override fun replace(start: Int, end: Int, text: CharSequence) {}
        }
        cache.set(controller, "编辑框")
        panel.begin(editable)
        assertNull("opening a panel field drops the editor's context", cache.get(controller))
        cache.set(controller, "面板")
        panel.end()
        assertNull("closing it drops the panel's context", cache.get(controller))
        val chat = EditorInfo().apply {
            packageName = "com.example.chat"
            fieldId = 7
            inputType = InputType.TYPE_CLASS_TEXT
        }
        service.onStartInput(chat, false)
        cache.set(controller, "旧内容")
        service.onStartInput(chat, true)
        assertNull("the same editor restarting its input is read again", cache.get(controller))
        cache.set(controller, "光标前")
        service.onUpdateSelection(0, 0, 3, 3, -1, -1)
        assertNull("a moved cursor is read again", cache.get(controller))
    }
}
