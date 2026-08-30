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
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.BarFunction
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.translate.TranslateClient
import com.aegis.ime.translate.TranslateMode
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslateComposingTest {

    private class Connection(target: View) : BaseInputConnection(target, true) {
        val composings = ArrayList<String>()
        var finishes = 0
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composings += text.toString()
            return super.setComposingText(text, newCursorPosition)
        }
        override fun finishComposingText(): Boolean {
            finishes++
            return super.finishComposingText()
        }
    }

    private class Script(private val reply: (String) -> String) {
        val bodies = CopyOnWriteArrayList<String>()
        val client = TranslateClient(transport = { _, body -> bodies += body; reply(body) })
    }

    private class Session(val service: AegisInputMethodService, val controller: KeyboardController, val view: InputView)

    private fun editor(fieldId: Int = 11) = EditorInfo().apply {
        packageName = "com.example.editor"
        this.fieldId = fieldId
        inputType = InputType.TYPE_CLASS_TEXT
    }

    private fun started(script: Script, info: EditorInfo = editor()): Session {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val engine = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        }
        val controller = KeyboardController(service, engine, null)
        service.javaClass.getDeclaredField("controller").apply { isAccessible = true; set(service, controller) }
        controller.onShowTranslate = {
            service.javaClass.getDeclaredMethod("toggleTranslateBar").apply { isAccessible = true }.invoke(service)
        }
        service.setTranslateClientForTest(script.client)
        service.onStartInput(info, false)
        val view = service.onCreateInputView() as InputView
        service.onStartInputView(info, false)
        return Session(service, controller, view)
    }

    private fun install(service: AegisInputMethodService, connection: InputConnection) {
        val framework = requireNotNull(service.javaClass.superclass)
        for (name in listOf("mInputConnection", "mStartedInputConnection")) {
            framework.getDeclaredField(name).apply { isAccessible = true; set(service, connection) }
        }
    }

    private fun type(service: AegisInputMethodService, text: String) {
        service.javaClass.getDeclaredMethod("commitExternalText", CharSequence::class.java)
            .apply { isAccessible = true }
            .invoke(service, text)
    }

    private fun settle(until: () -> Boolean = { true }) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (until()) return
            Thread.sleep(10)
        }
    }

    @Test fun typing_is_translated_once_after_a_pause_and_lands_as_composing_text() {
        val script = Script { """[["Hello"],["zh-CN"]]""" }
        val s = started(script)
        val connection = Connection(s.view)
        install(s.service, connection)
        s.controller.onBarFunction(BarFunction.TRANSLATE)

        type(s.service, "你")
        type(s.service, "好")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertTrue("nothing leaves before the pause", script.bodies.isEmpty())

        settle { connection.composings.isNotEmpty() }
        assertEquals(listOf("""[[["你好"],"auto","en"],"wt_lib"]"""), script.bodies.toList())
        assertEquals(listOf("Hello"), connection.composings)
        assertEquals(0, connection.finishes)
    }

    @Test fun editing_replaces_the_composing_text_and_clearing_empties_it_without_a_request() {
        val script = Script { body -> if ("你好吗" in body) """[["How are you"],["zh-CN"]]""" else """[["Hello"],["zh-CN"]]""" }
        val s = started(script)
        val connection = Connection(s.view)
        install(s.service, connection)
        s.controller.onBarFunction(BarFunction.TRANSLATE)

        type(s.service, "你好")
        settle { connection.composings.size == 1 }
        type(s.service, "吗")
        settle { connection.composings.size == 2 }
        assertEquals(listOf("Hello", "How are you"), connection.composings)
        assertEquals(2, script.bodies.size)

        repeat(3) { s.service.javaClass.getDeclaredMethod("deleteBackward").apply { isAccessible = true }.invoke(s.service) }
        settle()
        assertEquals("", s.view.translateText())
        assertEquals("the emptied field empties the composing span at once", "", connection.composings.last())
        assertEquals("no request goes out for blank text", 2, script.bodies.size)
    }

    @Test fun closing_the_bar_finishes_the_translation_in_place() {
        val script = Script { """[["Hello"],["zh-CN"]]""" }
        val s = started(script)
        val connection = Connection(s.view)
        install(s.service, connection)
        s.controller.onBarFunction(BarFunction.TRANSLATE)
        type(s.service, "你好")
        settle { connection.composings.isNotEmpty() }

        s.view.translateBarForTest().closeButtonForTest().performClick()
        assertEquals(1, connection.finishes)
        assertEquals("Hello", connection.editable.toString())
        assertEquals(listOf("Hello"), connection.composings)
    }

    @Test fun a_failed_request_toasts_once_and_keeps_the_last_translation() {
        var fail = false
        val script = Script { if (fail) throw IOException("HTTP 502") else """[["Hello"],["zh-CN"]]""" }
        val s = started(script)
        val connection = Connection(s.view)
        install(s.service, connection)
        s.controller.onBarFunction(BarFunction.TRANSLATE)
        type(s.service, "你好")
        settle { connection.composings.isNotEmpty() }

        fail = true
        type(s.service, "吗")
        settle { s.service.toastTextForTest() != null }
        assertEquals(
            s.service.getString(R.string.translate_failed_cause, s.service.getString(R.string.download_cause_server)),
            s.service.toastTextForTest(),
        )
        assertEquals(listOf("Hello"), connection.composings)
    }

    @Test fun a_request_superseded_while_in_flight_fails_silently_and_the_newer_one_lands() {
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val script = Script { body ->
            if (calls.incrementAndGet() == 1) {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                throw IOException("Socket closed")
            }
            """[["Hello"],["zh-CN"]]"""
        }
        val s = started(script)
        val connection = Connection(s.view)
        install(s.service, connection)
        s.controller.onBarFunction(BarFunction.TRANSLATE)

        type(s.service, "你")
        settle { calls.get() == 1 }
        assertEquals("the first request is in flight", 1, calls.get())

        type(s.service, "好")
        release.countDown()
        settle { connection.composings.isNotEmpty() }

        assertEquals("the cut-off request must not surface as a failure", null, s.service.toastTextForTest())
        assertEquals(listOf("Hello"), connection.composings)
        assertEquals(2, calls.get())
        assertTrue("the newer request carries the newer text", script.bodies.last().contains("你好"))
    }

    @Test fun the_chosen_mode_is_remembered_and_drives_the_next_request() {
        val script = Script { """[["こんにちは"],["zh-CN"]]""" }
        val s = started(script)
        val connection = Connection(s.view)
        install(s.service, connection)
        s.controller.onBarFunction(BarFunction.TRANSLATE)
        val bar = s.view.translateBarForTest()
        bar.modeButtonForTest().performClick()
        bar.choiceForTest(TranslateMode.ZH_JA).performClick()
        assertEquals("ZH_JA", s.service.getSharedPreferences("aegis", 0).getString("translate_mode", null))

        type(s.service, "你好")
        settle { connection.composings.isNotEmpty() }
        assertEquals(listOf("""[[["你好"],"auto","ja"],"wt_lib"]"""), script.bodies.toList())
        assertEquals(listOf("こんにちは"), connection.composings)

        s.controller.onBarFunction(BarFunction.TRANSLATE)
        s.controller.onBarFunction(BarFunction.TRANSLATE)
        assertEquals("the bar reopens on the remembered mode", TranslateMode.ZH_JA, s.view.translateMode())
    }

    @Test fun an_editor_switch_leaves_the_old_translation_behind_and_composes_into_the_new_editor() {
        val script = Script { """[["Hello"],["zh-CN"]]""" }
        val s = started(script)
        val first = Connection(s.view)
        install(s.service, first)
        s.controller.onBarFunction(BarFunction.TRANSLATE)
        type(s.service, "你好")
        settle { first.composings.isNotEmpty() }

        val next = editor(fieldId = 22)
        s.service.onFinishInput()
        val second = Connection(s.view)
        install(s.service, second)
        s.service.onStartInput(next, false)
        s.service.onStartInputView(next, false)
        assertEquals("", s.view.translateText())

        type(s.service, "你好")
        settle { second.composings.isNotEmpty() }
        assertEquals(listOf("Hello"), first.composings)
        assertEquals(listOf("Hello"), second.composings)
    }
}
