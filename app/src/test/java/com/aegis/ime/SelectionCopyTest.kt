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

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.ChunkedRead
import com.aegis.ime.ime.EditAction
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.user.ClipboardStore
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectionCopyTest {

    private val app = RuntimeEnvironment.getApplication()

    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    private class SelectionEditor(target: View) : BaseInputConnection(target, true) {
        lateinit var service: AegisInputMethodService
        private val main = Handler(Looper.getMainLooper())
        var parcel = Int.MAX_VALUE
        var reportsLeft = Int.MAX_VALUE
        var selectedCalls = 0
        var extractedCalls = 0

        fun hold(text: CharSequence, from: Int, to: Int) {
            val content = requireNotNull(editable)
            content.replace(0, content.length, text)
            Selection.setSelection(content, from, to)
        }

        fun held(): String = requireNotNull(editable).toString()

        override fun setSelection(start: Int, end: Int): Boolean {
            val done = super.setSelection(start, end)
            if (reportsLeft > 0) {
                reportsLeft--
                main.post { service.onUpdateSelection(0, 0, start, end, -1, -1) }
            }
            return done
        }

        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? =
            if (length > parcel) null else super.getTextBeforeCursor(length, flags)

        override fun getSelectedText(flags: Int): CharSequence? {
            selectedCalls++
            return super.getSelectedText(flags)?.takeIf { it.length <= parcel }
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            extractedCalls++
            return super.getExtractedText(request, flags)
        }
    }

    private class Fixture(val service: AegisInputMethodService, val editor: SelectionEditor)

    private fun fixture(inputType: Int = InputType.TYPE_CLASS_TEXT): Fixture {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, KeyboardController(service, engine, null))
        }
        val info = EditorInfo().apply {
            packageName = "com.example.editor"
            fieldId = 101
            fieldName = "message"
            this.inputType = inputType
        }
        service.onStartInput(info, false)
        service.onCreateInputView() as InputView
        service.onStartInputView(info, false)
        val editor = SelectionEditor(FrameLayout(service))
        editor.service = service
        val framework = requireNotNull(service.javaClass.superclass)
        for (fieldName in listOf("mInputConnection", "mStartedInputConnection")) {
            framework.getDeclaredField(fieldName).apply {
                isAccessible = true
                set(service, editor)
            }
        }
        return Fixture(service, editor)
    }

    private fun select(f: Fixture, text: CharSequence, from: Int, to: Int) {
        f.editor.hold(text, from, to)
        f.service.onUpdateSelection(0, 0, from, to, -1, -1)
    }

    private fun edit(service: AegisInputMethodService, action: EditAction) {
        service.javaClass.getDeclaredMethod("handleEdit", EditAction::class.java).apply {
            isAccessible = true
            invoke(service, action)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitOutTheRead() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_600))
    }

    private fun stored(service: AegisInputMethodService): String? {
        val store = service.javaClass.getDeclaredMethod("getClipboardStore").run {
            isAccessible = true
            invoke(service)
        } as ClipboardStore
        return store.latest()
    }

    private fun systemClip(): String? {
        val manager = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = manager.primaryClip ?: return null
        return if (clip.itemCount == 0) null else clip.getItemAt(0).text?.toString()
    }

    private fun body(chars: Int): String = String(CharArray(chars) { '一' + (it % 2048) })

    @Test fun a_copy_lands_in_the_aegis_clipboard_and_never_in_the_system_one() {
        val f = fixture()
        select(f, "把这一段带走，剩下的留着", 1, 6)

        edit(f.service, EditAction.COPY)

        assertEquals("这一段带走", stored(f.service))
        assertNull("the system clipboard must stay untouched", systemClip())
        assertEquals("a copy leaves the field alone", "把这一段带走，剩下的留着", f.editor.held())
        assertEquals(app.getString(R.string.edit_copy_done), f.service.toastTextForTest())
    }

    @Test fun a_cut_takes_the_selection_out_of_the_field_and_keeps_it_in_aegis() {
        val f = fixture()
        select(f, "把这一段带走，剩下的留着", 1, 6)

        edit(f.service, EditAction.CUT)

        assertEquals("这一段带走", stored(f.service))
        assertNull("the system clipboard must stay untouched", systemClip())
        assertEquals("把，剩下的留着", f.editor.held())
        assertEquals(app.getString(R.string.edit_cut_done), f.service.toastTextForTest())
    }

    @Test fun a_selection_no_single_parcel_can_carry_still_comes_back_whole() {
        val f = fixture()
        val written = body(ChunkedRead.CHUNK * 2 + 321)
        f.editor.parcel = ChunkedRead.CHUNK
        select(f, written, 0, written.length)

        edit(f.service, EditAction.COPY)

        assertEquals(written, stored(f.service))
        assertNull("the system clipboard must stay untouched", systemClip())
        assertEquals(app.getString(R.string.edit_copy_done), f.service.toastTextForTest())
    }

    @Test fun a_cut_no_single_parcel_can_carry_empties_the_field_it_read() {
        val f = fixture()
        val written = body(ChunkedRead.CHUNK * 2 + 321)
        f.editor.parcel = ChunkedRead.CHUNK
        select(f, written, 0, written.length)

        edit(f.service, EditAction.CUT)

        assertEquals(written, stored(f.service))
        assertEquals("", f.editor.held())
    }

    @Test fun only_the_selected_part_of_a_long_field_is_taken() {
        val f = fixture()
        val written = body(ChunkedRead.CHUNK * 3)
        f.editor.parcel = ChunkedRead.CHUNK
        val from = ChunkedRead.CHUNK / 2
        val to = ChunkedRead.CHUNK * 2 + 7
        select(f, written, from, to)

        edit(f.service, EditAction.CUT)

        assertEquals(written.substring(from, to), stored(f.service))
        assertEquals(written.substring(0, from) + written.substring(to), f.editor.held())
    }

    @Test fun an_editor_that_never_reports_the_caret_stores_nothing_and_leaves_the_field_alone() {
        val f = fixture()
        val written = body(ChunkedRead.CHUNK * 2)
        f.editor.parcel = ChunkedRead.CHUNK
        select(f, written, 0, written.length)
        f.editor.reportsLeft = 0

        edit(f.service, EditAction.COPY)
        waitOutTheRead()

        assertNull("nothing was read, so nothing may be stored", stored(f.service))
        assertEquals(app.getString(R.string.edit_copy_failed), f.service.toastTextForTest())
        assertEquals("and the field must be left exactly as it was", written, f.editor.held())
    }

    @Test fun a_cut_that_only_reached_part_of_the_selection_leaves_the_rest_in_place() {
        val f = fixture()
        val written = body(ChunkedRead.CHUNK * 3)
        f.editor.parcel = ChunkedRead.CHUNK
        select(f, written, 0, written.length)
        f.editor.reportsLeft = 2

        edit(f.service, EditAction.CUT)
        waitOutTheRead()

        val reached = ChunkedRead.CHUNK * 2
        assertEquals("what was read has to be kept", written.substring(0, reached), stored(f.service))
        assertEquals("what was not read has to stay in the field", written.substring(reached), f.editor.held())
        assertEquals(
            app.resources.getQuantityString(R.plurals.edit_cut_partial, reached, reached),
            f.service.toastTextForTest(),
        )
    }

    @Test fun a_copy_with_the_history_switched_off_stores_nothing_and_says_why() {
        val f = fixture()
        app.getSharedPreferences("aegis", Context.MODE_PRIVATE)
            .edit().putBoolean("clip_history", false).commit()
        select(f, "不该被记下的内容", 0, 4)

        edit(f.service, EditAction.COPY)

        assertNull(stored(f.service))
        assertNull("and least of all in the system clipboard", systemClip())
        assertEquals(app.getString(R.string.edit_copy_needs_history), f.service.toastTextForTest())
        app.getSharedPreferences("aegis", Context.MODE_PRIVATE)
            .edit().putBoolean("clip_history", true).commit()
    }

    @Test fun a_password_field_copies_exactly_like_any_other_field() {
        val f = fixture(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        select(f, "hunter2-and-then-some", 0, 7)

        edit(f.service, EditAction.COPY)

        assertEquals("a password field is an ordinary field", "hunter2", stored(f.service))
        assertNull("and it still never reaches the system clipboard", systemClip())
        assertEquals(app.getString(R.string.edit_copy_done), f.service.toastTextForTest())
    }

    @Test fun a_read_the_input_session_ended_under_is_dropped_instead_of_settling_on_a_dead_field() {
        val f = fixture()
        val written = body(ChunkedRead.CHUNK * 3)
        f.editor.parcel = ChunkedRead.CHUNK
        select(f, written, 0, written.length)
        f.editor.reportsLeft = 1

        edit(f.service, EditAction.COPY)
        f.service.onFinishInput()
        waitOutTheRead()

        assertNull("a read the session ended under must store nothing", stored(f.service))
        assertEquals(app.getString(R.string.edit_copy_failed), f.service.toastTextForTest())
        assertEquals("and it must leave the field exactly as it was", written, f.editor.held())
    }

    @Test fun a_cut_the_input_session_ended_under_never_reaches_the_field_it_was_reading() {
        val f = fixture()
        val written = body(ChunkedRead.CHUNK * 3)
        f.editor.parcel = ChunkedRead.CHUNK
        select(f, written, 0, written.length)
        f.editor.reportsLeft = 1

        edit(f.service, EditAction.CUT)
        f.service.onFinishInput()
        waitOutTheRead()

        assertNull("a cut the session ended under must store nothing", stored(f.service))
        assertEquals(app.getString(R.string.edit_cut_failed), f.service.toastTextForTest())
        assertEquals("and it must not delete anything either", written, f.editor.held())
    }

    @Test fun a_selection_past_the_direct_bound_is_never_asked_for_whole() {
        val f = fixture()
        val written = body(ChunkedRead.DIRECT_MAX + ChunkedRead.CHUNK)
        select(f, written, 0, written.length)
        f.editor.selectedCalls = 0

        edit(f.service, EditAction.COPY)
        waitOutTheRead()

        assertEquals(
            "a selection past the direct bound must go straight to the chunked read",
            0,
            f.editor.selectedCalls,
        )
        assertEquals(written, stored(f.service))
    }

    private fun readPending(service: AegisInputMethodService): Boolean =
        service.javaClass.getDeclaredField("chunkedRead").run {
            isAccessible = true
            (get(service) as ChunkedRead?)?.pending == true
        }

    @Test fun another_edit_action_taken_mid_read_is_refused_instead_of_derailing_it() {
        val f = fixture()
        val written = body(ChunkedRead.CHUNK * 3)
        f.editor.parcel = ChunkedRead.CHUNK
        select(f, written, 0, written.length)
        f.editor.reportsLeft = 1

        edit(f.service, EditAction.COPY)
        assertTrue("precondition: the read must still be running", readPending(f.service))

        edit(f.service, EditAction.CUT)

        assertTrue("the read already running must survive another action", readPending(f.service))
        assertEquals("and the cut it refused must not take anything out", written, f.editor.held())
        assertNull("nor store anything of its own", stored(f.service))
    }
}
