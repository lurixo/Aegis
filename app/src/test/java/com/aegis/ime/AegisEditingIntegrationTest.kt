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

import android.text.InputType
import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.SurroundingText
import android.widget.FrameLayout
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.LayoutChoice
import com.aegis.ime.user.LiveUserData
import androidx.core.content.FileProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AegisEditingIntegrationTest {
    private data class Fixture(val service: AegisInputMethodService, val controller: KeyboardController, val connection: Connection, val info: EditorInfo)

    private class Connection(view: View) : BaseInputConnection(view, true) {
        val menus = ArrayList<Int>()
        val keys = ArrayList<KeyEvent>()
        var content: InputContentInfo? = null
        var contentFlags = 0
        var acceptImage = true
        var rejectPasteUp = false
        var onMenu: ((Int) -> Unit)? = null
        var onContent: (() -> Unit)? = null
        var onKey: ((KeyEvent) -> Unit)? = null
        var deferCopy = false
        var acceptMenus = true
        var extractedTextAvailable = true
        var surroundingTextAvailable = true
        private var pendingCopy = false
        var reads = 0
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
            reads++
            return super.getTextBeforeCursor(n, flags)
        }
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
            reads++
            return super.getTextAfterCursor(n, flags)
        }
        override fun getSelectedText(flags: Int): CharSequence? {
            reads++
            if (pendingCopy) {
                pendingCopy = false
                onMenu?.invoke(android.R.id.copy)
            }
            return super.getSelectedText(flags)
        }
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            reads++
            return if (!extractedTextAvailable) null else ExtractedText().also {
            it.text = editable!!.subSequence(0, editable!!.length)
            it.startOffset = 0
            it.partialStartOffset = -1
            it.partialEndOffset = -1
            it.selectionStart = Selection.getSelectionStart(editable)
            it.selectionEnd = Selection.getSelectionEnd(editable)
        }
        }
        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? =
            if (surroundingTextAvailable) super.getSurroundingText(beforeLength, afterLength, flags) else null
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            keys.add(event)
            onKey?.invoke(event)
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL) {
                val start = Selection.getSelectionStart(editable)
                val end = Selection.getSelectionEnd(editable)
                if (start != end) commitText("", 1)
                else if (start > 0) deleteSurroundingText(1, 0)
            }
            return !(rejectPasteUp && event.keyCode == KeyEvent.KEYCODE_PASTE && event.action == KeyEvent.ACTION_UP)
        }
        override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean {
            onContent?.invoke()
            content = inputContentInfo
            contentFlags = flags
            return acceptImage
        }
        override fun performContextMenuAction(id: Int): Boolean {
            menus.add(id)
            if (deferCopy && id == android.R.id.copy) pendingCopy = true else onMenu?.invoke(id)
            if (id == android.R.id.cut) commitText("", 1)
            return acceptMenus && id != android.R.id.undo
        }
    }

    @Before fun prepare() {
        LiveUserData.clipboardHost = null
        LiveUserData.restoreInProgress = false
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            .get(null).let { (it as MutableMap<*, *>).clear() }
    }
    @After fun clean() {
        LiveUserData.clipboardHost?.stopSaving()
        LiveUserData.clipboardHost = null
        LiveUserData.restoreInProgress = false
    }

    private fun fixture(choice: LayoutChoice = LayoutChoice.CN_ALPHA, type: Int = InputType.TYPE_CLASS_TEXT): Fixture {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val engine = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        }
        val controller = KeyboardController(service, engine, null)
        field(service, "controller", controller)
        val info = EditorInfo().apply {
            packageName = "com.example.editor"
            fieldId = 42
            inputType = type
            contentMimeTypes = arrayOf("image/png")
        }
        val framework = service.javaClass.superclass!!
        framework.getDeclaredField("mInputEditorInfo").apply { isAccessible = true; set(service, info) }
        service.onStartInput(info, false)
        service.onCreateInputView() as InputView
        service.onStartInputView(info, false)
        val connection = Connection(FrameLayout(service))
        for (name in listOf("mInputConnection", "mStartedInputConnection")) {
            framework.getDeclaredField(name).apply { isAccessible = true; set(service, connection) }
        }
        controller.applyLayoutChoice(choice)
        return Fixture(service, controller, connection, info)
    }

    private fun field(service: AegisInputMethodService, name: String, value: Any) {
        service.javaClass.getDeclaredField(name).apply { isAccessible = true; set(service, value) }
    }
    private fun invoke(service: AegisInputMethodService, name: String) {
        service.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)
    }

    @Test fun checking_backspace_gesture_availability_never_queries_the_host_editor() {
        for (layout in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            val f = fixture(layout)
            f.connection.commitText("怎么就改不对呢？", 1)
            f.connection.reads = 0
            val available = f.service.javaClass.getDeclaredMethod("canBackspaceSwipe", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            repeat(100) {
                assertTrue(available.invoke(f.service, true) as Boolean)
                available.invoke(f.service, false)
            }
            assertEquals(0, f.connection.reads)
        }
    }

}
