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

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Selection
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.SurroundingText
import android.widget.FrameLayout
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.EditAction
import com.aegis.ime.ime.EditPanelView
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.LayoutChoice
import com.aegis.ime.ime.PanelEditable
import com.aegis.ime.ime.PanelTextInput
import com.aegis.ime.user.ClipboardImages
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.LiveUserData
import androidx.core.content.FileProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

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
    private fun edit(f: Fixture, action: EditAction) {
        f.service.javaClass.getDeclaredMethod("handleEdit", EditAction::class.java).apply { isAccessible = true }.invoke(f.service, action)
    }
    private fun usePanelEditor(f: Fixture) {
        val input = f.service.javaClass.getDeclaredField("panelInput").apply { isAccessible = true }.get(f.service) as PanelTextInput
        input.begin(object : PanelEditable {
            override fun snapshot(): String = f.connection.editable.toString()
            override fun selectionStart(): Int = Selection.getSelectionStart(f.connection.editable)
            override fun selectionEnd(): Int = Selection.getSelectionEnd(f.connection.editable)
            override fun setSelection(start: Int, end: Int) { f.connection.setSelection(start, end) }
            override fun replace(start: Int, end: Int, text: CharSequence) {
                f.connection.setSelection(start, end)
                f.connection.commitText(text, 1)
            }
        })
    }
    private fun syncSelection(f: Fixture, panel: EditPanelView? = null) {
        val start = Selection.getSelectionStart(f.connection.editable)
        val end = Selection.getSelectionEnd(f.connection.editable)
        f.service.onUpdateSelection(-1, -1, start, end, -1, -1)
        panel?.setHasSelection(start != end)
    }
    private fun withEditPanel(f: Fixture, widthDp: Int, heightDp: Int, block: (EditPanelView) -> Unit) {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            invoke(f.service, "showEditPanel")
            val panel = f.service.javaClass.getDeclaredField("editPanelView").apply { isAccessible = true }.get(f.service) as EditPanelView
            (panel.parent as ViewGroup).removeView(panel)
            host.get().setContentView(panel)
            shadowOf(Looper.getMainLooper()).idle()
            val density = panel.resources.displayMetrics.density
            val root = requireNotNull(host.get().findViewById<ViewGroup>(android.R.id.content))
            root.measure(
                View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((heightDp * density).toInt(), View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            block(panel)
        } finally {
            host.pause().stop().destroy()
        }
    }
    private fun tapEdit(panel: EditPanelView, action: EditAction) {
        val target = requireNotNull(panel.actionViewForTest(action))
        assertTrue("$action must be enabled", target.isEnabled)
        val hit = Rect(0, 0, target.width, target.height)
        panel.offsetDescendantRectToMyCoords(target, hit)
        for ((eventAction, time) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 10L)) {
            val event = MotionEvent.obtain(0, time, eventAction, hit.exactCenterX(), hit.exactCenterY(), 0)
            try {
                assertTrue("$action receives the touch", panel.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun store(f: Fixture): ClipboardStore {
        val lazy = f.service.javaClass.getDeclaredField("clipboardStore\$delegate").apply { isAccessible = true }.get(f.service) as Lazy<*>
        return lazy.value as ClipboardStore
    }
    private fun clipboard(f: Fixture) = f.service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private fun finishImage(f: Fixture) { store(f).flushPendingWrites(); shadowOf(Looper.getMainLooper()).idle() }
    private fun image(f: Fixture): ClipData {
        val file = File(f.service.filesDir, "clips/images/source.png")
        file.parentFile!!.mkdirs()
        file.writeBytes(Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGMwKj/zHwAEyQJ1hXFYwAAAAABJRU5ErkJggg=="))
        return ClipData("image", arrayOf("image/png"), ClipData.Item(FileProvider.getUriForFile(f.service, f.service.packageName + ".clipboard.images", file)))
    }

    @Test fun edit_panel_back_remains_available_while_text_is_being_restored() {
        for (choice in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            val f = fixture(choice)
            invoke(f.service, "showEditPanel")
            val input = f.service.javaClass.getDeclaredField("inputView").apply { isAccessible = true }.get(f.service) as InputView
            val panel = f.service.javaClass.getDeclaredField("editPanelView").apply { isAccessible = true }.get(f.service) as EditPanelView
            assertTrue(input.isPanelShowing(panel))
            field(f.service, "restoring", true)
            edit(f, EditAction.BACK)
            assertFalse(input.isPanelShowing(panel))
            assertTrue(f.service.javaClass.getDeclaredField("restoring").apply { isAccessible = true }.getBoolean(f.service))
        }
    }

    @Test fun web_cut_waits_for_the_matching_clipboard_and_remains_undoable() {
        val f = fixture(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)
        f.connection.commitText("prefix two words suffix", 1)
        f.connection.setSelection(7, 16)
        syncSelection(f)
        clipboard(f).setPrimaryClip(ClipData.newPlainText("old", "old clipboard"))
        var nativeCut: Triple<String, Int, Int>? = null
        f.connection.onMenu = { id ->
            if (id == android.R.id.copy) Handler(Looper.getMainLooper()).postDelayed({
                clipboard(f).setPrimaryClip(ClipData.newPlainText("copied", "two words"))
            }, 100L)
            if (id == android.R.id.cut) nativeCut = Triple(f.connection.editable.toString(),
                Selection.getSelectionStart(f.connection.editable), Selection.getSelectionEnd(f.connection.editable))
        }
        f.connection.onKey = { event ->
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_Z && event.isCtrlPressed) {
                val saved = requireNotNull(nativeCut)
                Handler(Looper.getMainLooper()).postDelayed({
                    val body = requireNotNull(f.connection.editable)
                    body.replace(0, body.length, saved.first)
                    f.connection.setSelection(saved.second, saved.third)
                }, 80L)
            }
        }
        edit(f, EditAction.CUT)
        assertEquals("copy has not completed", "prefix two words suffix", f.connection.editable.toString())
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
        assertEquals("prefix  suffix", f.connection.editable.toString())
        assertEquals(listOf(android.R.id.copy, android.R.id.cut), f.connection.menus)
        edit(f, EditAction.UNDO)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        assertEquals("prefix two words suffix", f.connection.editable.toString())
        assertEquals(7, Selection.getSelectionStart(f.connection.editable))
        assertEquals(16, Selection.getSelectionEnd(f.connection.editable))
        assertEquals(1, f.connection.keys.count { it.action == KeyEvent.ACTION_DOWN && it.keyCode == KeyEvent.KEYCODE_Z && it.isCtrlPressed })
    }

    @Test fun delayed_web_copy_never_cuts_a_changed_selection() {
        val f = fixture(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)
        f.connection.commitText("prefix two words suffix", 1)
        f.connection.setSelection(7, 16)
        syncSelection(f)
        clipboard(f).setPrimaryClip(ClipData.newPlainText("old", "old clipboard"))
        f.connection.onMenu = { id ->
            if (id == android.R.id.copy) Handler(Looper.getMainLooper()).postDelayed({
                clipboard(f).setPrimaryClip(ClipData.newPlainText("copied", "two words"))
            }, 100L)
        }
        edit(f, EditAction.CUT)
        f.connection.setSelection(0, 6)
        syncSelection(f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        assertEquals("prefix two words suffix", f.connection.editable.toString())
        assertEquals(listOf(android.R.id.copy), f.connection.menus)
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

    @Test fun a_consumed_clear_is_not_replayed_again_after_undoing_its_restoration() {
        for (layout in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            val f = fixture(layout)
            val text = "怎么就改不对呢？"
            f.connection.commitText(text, 1)
            val swipe = f.service.javaClass.getDeclaredMethod("backspaceSwipe", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            swipe.invoke(f.service, true)
            assertEquals("", f.connection.editable.toString())
            swipe.invoke(f.service, false)
            assertEquals(text, f.connection.editable.toString())
            edit(f, EditAction.UNDO)
            assertEquals("", f.connection.editable.toString())
            swipe.invoke(f.service, false)
            assertEquals("", f.connection.editable.toString())
        }
    }

    @Test fun both_keyboard_layouts_undo_insert_tab_delete_cut_paste_and_clear_in_order() {
        for (layout in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            val f = fixture(layout)
            val c = f.connection
            c.commitText("甲😀乙", 1)
            f.service.commitText("新增")
            edit(f, EditAction.UNDO)
            assertEquals("甲😀乙", c.editable.toString())
            c.setSelection(1, 1)
            edit(f, EditAction.FORWARD_DELETE)
            assertEquals("甲乙", c.editable.toString())
            edit(f, EditAction.UNDO)
            assertEquals("甲😀乙", c.editable.toString())
            assertEquals(1, Selection.getSelectionStart(c.editable))
            edit(f, EditAction.TAB)
            assertEquals("甲\t😀乙", c.editable.toString())
            edit(f, EditAction.UNDO)
            assertEquals("甲😀乙", c.editable.toString())
            c.setSelection(1, 3)
            f.service.onUpdateSelection(1, 1, 1, 3, -1, -1)
            edit(f, EditAction.CUT)
            assertEquals("甲乙", c.editable.toString())
            edit(f, EditAction.UNDO)
            assertEquals("甲😀乙", c.editable.toString())
            assertEquals(1, Selection.getSelectionStart(c.editable))
            assertEquals(3, Selection.getSelectionEnd(c.editable))
            store(f).record("粘贴")
            clipboard(f).setPrimaryClip(ClipData.newPlainText("text", "粘贴"))
            edit(f, EditAction.PASTE)
            assertEquals("甲粘贴乙", c.editable.toString())
            edit(f, EditAction.UNDO)
            assertEquals("甲😀乙", c.editable.toString())
            f.service.javaClass.getDeclaredMethod("handleBackspaceSwipe", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(f.service, true)
            assertEquals("", c.editable.toString())
            edit(f, EditAction.UNDO)
            assertEquals("甲😀乙", c.editable.toString())
            invoke(f.service, "showEditPanel")
            val panel = f.service.javaClass.getDeclaredField("editPanelView").apply { isAccessible = true }.get(f.service) as EditPanelView
            assertNotNull(panel.actionViewForTest(EditAction.UNDO))
            assertFalse(c.menus.contains(android.R.id.undo))
            store(f).stopSaving()
        }
    }

    @Test fun right_side_actions_exit_selection_mode_and_keep_their_selection_semantics() {
        val original = "abc\ndef"
        val actions = listOf(
            EditAction.TAB, EditAction.DELETE, EditAction.UNDO, EditAction.FORWARD_DELETE,
            EditAction.SELECT_ALL, EditAction.COPY, EditAction.CUT, EditAction.PASTE,
        )
        for (layout in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            for (internal in listOf(false, true)) {
                for ((width, height) in listOf(411 to 324, 640 to 220)) {
                    for (action in actions) {
                        val f = fixture(layout)
                        val c = f.connection
                        c.commitText(original, 1)
                        syncSelection(f)
                        if (internal) usePanelEditor(f)
                        f.service.commitText("!")
                        syncSelection(f)
                        store(f).record("P")
                        clipboard(f).setPrimaryClip(ClipData.newPlainText("paste", "P"))
                        c.onMenu = { id ->
                            when (id) {
                                android.R.id.selectAll -> c.setSelection(0, c.editable!!.length)
                                android.R.id.copy -> clipboard(f).setPrimaryClip(ClipData.newPlainText("copy", c.getSelectedText(0)))
                            }
                        }
                        c.setSelection(1, 1)
                        syncSelection(f)
                        withEditPanel(f, width, height) { panel ->
                            val select = requireNotNull(panel.actionViewForTest(EditAction.START_SELECT))
                            val message = "$layout internal=$internal $width x $height $action"
                            tapEdit(panel, EditAction.START_SELECT)
                            tapEdit(panel, EditAction.RIGHT)
                            syncSelection(f, panel)
                            assertTrue(message, select.isSelected)
                            assertEquals(message, "b", c.getSelectedText(0).toString())
                            tapEdit(panel, action)
                            syncSelection(f, panel)
                            assertFalse(message, select.isSelected)
                            val expected = when (action) {
                                EditAction.TAB -> "a\tc\ndef!"
                                EditAction.DELETE, EditAction.FORWARD_DELETE, EditAction.CUT -> "ac\ndef!"
                                EditAction.PASTE -> "aPc\ndef!"
                                EditAction.UNDO -> original
                                else -> original + "!"
                            }
                            assertEquals(message, expected, c.editable.toString())
                            when (action) {
                                EditAction.COPY -> {
                                    assertEquals(message, "b", clipboard(f).primaryClip!!.getItemAt(0).text.toString())
                                    assertEquals(message, "b", c.getSelectedText(0).toString())
                                }
                                EditAction.SELECT_ALL -> assertEquals(message, expected, c.getSelectedText(0).toString())
                                else -> Unit
                            }
                            tapEdit(panel, EditAction.LEFT)
                            syncSelection(f, panel)
                            assertEquals(message, Selection.getSelectionStart(c.editable), Selection.getSelectionEnd(c.editable))
                        }
                        store(f).stopSaving()
                    }
                }
            }
        }
    }

    @Test fun navigation_and_document_jumps_continue_extending_selection_in_both_editors() {
        for (layout in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            for (internal in listOf(false, true)) {
                val f = fixture(layout)
                val c = f.connection
                c.commitText("abc\ndef", 1)
                c.setSelection(5, 5)
                syncSelection(f)
                if (internal) usePanelEditor(f)
                withEditPanel(f, 411, 324) { panel ->
                    val select = requireNotNull(panel.actionViewForTest(EditAction.START_SELECT))
                    tapEdit(panel, EditAction.START_SELECT)
                    for (action in listOf(EditAction.HOME, EditAction.END, EditAction.LEFT, EditAction.RIGHT, EditAction.UP, EditAction.DOWN)) {
                        tapEdit(panel, action)
                        syncSelection(f, panel)
                        assertTrue("$layout internal=$internal $action", select.isSelected)
                        if (action == EditAction.HOME) assertEquals("abc\nd", c.getSelectedText(0).toString())
                        if (action == EditAction.END) assertEquals("ef", c.getSelectedText(0).toString())
                    }
                    tapEdit(panel, EditAction.START_SELECT)
                    assertFalse(select.isSelected)
                }
            }
        }
    }

    @Test fun successive_undo_keeps_cursor_navigation_and_stops_at_external_changes() {
        val f = fixture()
        f.service.commitText("one")
        f.service.commitText("two")
        f.connection.setSelection(0, 0)
        edit(f, EditAction.UNDO)
        assertEquals("one", f.connection.editable.toString())
        edit(f, EditAction.UNDO)
        assertEquals("", f.connection.editable.toString())
        f.service.commitText("mine")
        f.connection.commitText("external", 1)
        edit(f, EditAction.UNDO)
        assertEquals("mineexternal", f.connection.editable.toString())
    }

    @Test fun password_and_field_switch_never_restore_a_previous_field() {
        val password = fixture(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        password.service.commitText("secret")
        edit(password, EditAction.UNDO)
        assertEquals("secret", password.connection.editable.toString())
        val f = fixture()
        f.service.commitText("old")
        f.info.fieldId = 43
        f.service.onStartInput(f.info, false)
        edit(f, EditAction.UNDO)
        assertEquals("old", f.connection.editable.toString())
    }

    @Test fun image_capture_and_paste_preserve_mime_and_uri_grant_without_text_fallback() {
        val f = fixture()
        clipboard(f).setPrimaryClip(image(f))
        invoke(f.service, "captureClip")
        finishImage(f)
        assertTrue(store(f).latestEntry()!!.isImage)
        val tracked = f.service.currentInputConnection
        assertNotSame(f.connection, tracked)
        f.connection.onContent = { assertSame(f.connection, f.service.currentInputConnection) }
        edit(f, EditAction.PASTE)
        finishImage(f)
        assertNotSame(f.connection, f.service.currentInputConnection)
        val content = requireNotNull(f.connection.content)
        assertTrue(content.description.hasMimeType("image/png"))
        assertEquals(1, f.connection.contentFlags)
        assertNotNull(f.service.contentResolver.openInputStream(content.contentUri)?.use { it.read() })
        assertEquals("", f.connection.editable.toString())
        store(f).clearHistory()
        store(f).flushPendingWrites()
        assertEquals(content.contentUri, clipboard(f).primaryClip!!.getItemAt(0).uri)
        assertNotNull(f.service.contentResolver.openInputStream(content.contentUri)?.use { it.read() })
    }

    @Test fun image_paste_entry_points_wait_for_pending_text_replay() {
        val original = "原文 restore🙂\r\n".repeat(20_000)
        for (inserting in listOf(false, true)) for (entryPoint in listOf("pasteImage", "requestImageClipboardPaste")) {
            val label = "$entryPoint inserting=$inserting"
            val f = fixture()
            clipboard(f).setPrimaryClip(image(f))
            invoke(f.service, "captureClip")
            finishImage(f)
            val entry = requireNotNull(store(f).latestEntry())
            val history = f.service.javaClass.getDeclaredField("editorUndo").apply { isAccessible = true }
                .get(f.service) as com.aegis.ime.ime.EditorUndoHistory
            history.selectionProvider = {
                Selection.getSelectionStart(f.connection.editable) to Selection.getSelectionEnd(f.connection.editable)
            }
            val connection = requireNotNull(f.service.currentInputConnection)
            if (inserting) {
                f.connection.setSelection(0, 0)
                assertTrue(label, history.commitCapturedText(connection, original) { error("unexpected inline insertion") })
                assertTrue(label, history.hasPendingInsertion)
            } else {
                f.connection.commitText(original, 1)
                f.connection.setSelection(0, original.length)
                assertTrue(label, history.replaceCapturedSelection(connection, 0, original, "", 0, original.length))
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(30))
                assertEquals(label, "", f.connection.editable.toString())
                assertFalse(label, history.undo(connection))
                assertTrue(label, history.hasPendingUndo)
            }
            val before = f.connection.editable.toString()
            if (entryPoint == "pasteImage") {
                f.service.javaClass.getDeclaredMethod(entryPoint, entry.javaClass)
                    .apply { isAccessible = true }.invoke(f.service, entry)
            } else {
                f.service.javaClass.getDeclaredMethod(entryPoint, entry.javaClass, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(f.service, entry, false)
            }
            assertNull(label, f.connection.content)
            assertTrue(label, f.connection.keys.none { it.keyCode == KeyEvent.KEYCODE_PASTE })
            assertEquals(label, before, f.connection.editable.toString())
            assertTrue(label, history.hasPendingUndo || history.hasPendingInsertion)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(30))
            assertFalse(label, history.hasPendingUndo || history.hasPendingInsertion)
            assertEquals(label, original, f.connection.editable.toString())
            f.service.onFinishInput()
            store(f).stopSaving()
        }
    }

    @Test fun image_paste_reaches_receivers_without_a_matching_mime_declaration() {
        val f = fixture()
        clipboard(f).setPrimaryClip(image(f))
        for (types in listOf(null, emptyArray(), arrayOf("image/jpeg"))) {
            f.info.contentMimeTypes = types
            f.connection.content = null
            f.connection.onContent = { assertSame(f.connection, f.service.currentInputConnection) }
            edit(f, EditAction.PASTE)
            finishImage(f)
            val content = requireNotNull(f.connection.content)
            assertTrue(content.description.hasMimeType("image/png"))
            assertEquals(1, f.connection.contentFlags)
            assertNotNull(f.service.contentResolver.openInputStream(content.contentUri)?.use { it.read() })
            assertNotSame(f.connection, f.service.currentInputConnection)
            assertEquals("", f.connection.editable.toString())
        }
    }

    @Test fun rejected_image_paste_preserves_selected_text_without_context_menu_fallback() {
        val f = fixture()
        clipboard(f).setPrimaryClip(image(f))
        f.info.contentMimeTypes = emptyArray()
        f.connection.acceptImage = false
        f.connection.commitText("keep selected text", 1)
        f.connection.setSelection(5, 13)
        edit(f, EditAction.PASTE)
        finishImage(f)
        assertNotNull(f.connection.content)
        assertEquals("keep selected text", f.connection.editable.toString())
        assertEquals(5, Selection.getSelectionStart(f.connection.editable))
        assertEquals(13, Selection.getSelectionEnd(f.connection.editable))
        assertFalse(f.connection.menus.contains(android.R.id.paste))
        assertTrue(f.connection.keys.isEmpty())
        assertNotSame(f.connection, f.service.currentInputConnection)
        f.service.commitText("tracked")
        edit(f, EditAction.UNDO)
        assertEquals("keep selected text", f.connection.editable.toString())
    }

    @Test fun image_cut_saves_readable_content_before_removing_the_selected_image() {
        val f = fixture()
        val clip = image(f)
        f.connection.commitText("\uFFFC", 1)
        f.connection.setSelection(0, 1)
        f.service.onUpdateSelection(1, 1, 0, 1, -1, -1)
        clipboard(f).setPrimaryClip(ClipData.newPlainText("old", "old"))
        f.connection.onMenu = { id ->
            if (id == android.R.id.copy) clipboard(f).setPrimaryClip(clip)
            if (id == android.R.id.cut) assertTrue(store(f).latestEntry()!!.imageFile()!!.isFile)
        }
        edit(f, EditAction.CUT)
        assertEquals("\uFFFC", f.connection.editable.toString())
        finishImage(f)
        assertEquals(listOf(android.R.id.copy, android.R.id.cut), f.connection.menus)
        assertEquals("", f.connection.editable.toString())
    }

    @Test fun image_paste_publishes_the_picked_image_before_requesting_native_clipboard_paste() {
        val f = fixture()
        clipboard(f).setPrimaryClip(image(f))
        invoke(f.service, "captureClip")
        finishImage(f)
        val entry = store(f).latestEntry()!!
        clipboard(f).setPrimaryClip(ClipData.newPlainText("unrelated", "do not paste this"))
        f.info.contentMimeTypes = null
        f.connection.acceptImage = false
        f.connection.onKey = { event ->
            assertEquals(KeyEvent.KEYCODE_PASTE, event.keyCode)
            val clip = clipboard(f).primaryClip!!
            assertTrue(clip.description.hasMimeType("image/png"))
            assertNull(clip.getItemAt(0).text)
            assertEquals(ClipboardImages.uri(f.service, entry), clip.getItemAt(0).uri)
            assertNotNull(f.service.contentResolver.openInputStream(clip.getItemAt(0).uri)?.use { it.read() })
        }
        f.service.javaClass.getDeclaredMethod("pasteImage", entry.javaClass).apply { isAccessible = true }.invoke(f.service, entry)
        assertNotNull(f.connection.content)
        assertTrue(f.connection.menus.isEmpty())
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), f.connection.keys.map { it.action })
        assertEquals("", f.connection.editable.toString())
        assertEquals(f.service.getString(R.string.clip_image_paste_requested), f.service.toastTextForTest())
    }

    @Test fun image_paste_does_not_fall_back_after_native_acceptance_or_an_exception() {
        val f = fixture(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)
        clipboard(f).setPrimaryClip(image(f))
        f.info.contentMimeTypes = null
        edit(f, EditAction.PASTE)
        finishImage(f)
        assertNotNull(f.connection.content)
        assertTrue(f.connection.menus.isEmpty())
        assertTrue(f.connection.keys.isEmpty())
        f.connection.onContent = { throw IllegalStateException("ambiguous receiver failure") }
        edit(f, EditAction.PASTE)
        finishImage(f)
        assertTrue(f.connection.menus.isEmpty())
        assertTrue(f.connection.keys.isEmpty())
    }

    @Test fun web_image_paste_preserves_selection_when_native_content_is_rejected() {
        val f = fixture(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)
        clipboard(f).setPrimaryClip(image(f))
        f.info.contentMimeTypes = null
        f.connection.acceptImage = false
        f.connection.commitText("keep selected text", 1)
        f.connection.setSelection(5, 13)
        edit(f, EditAction.PASTE)
        finishImage(f)
        assertNotNull(f.connection.content)
        assertTrue(f.connection.menus.isEmpty())
        assertTrue(f.connection.keys.isEmpty())
        assertEquals("keep selected text", f.connection.editable.toString())
        assertEquals(5, Selection.getSelectionStart(f.connection.editable))
        assertEquals(13, Selection.getSelectionEnd(f.connection.editable))
        assertEquals(f.service.getString(R.string.clip_image_paste_cursor), f.service.toastTextForTest())
    }

    @Test fun web_image_paste_does_not_guess_an_unreadable_selection() {
        val f = fixture(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)
        clipboard(f).setPrimaryClip(image(f))
        f.connection.acceptImage = false
        f.connection.extractedTextAvailable = false
        f.connection.surroundingTextAvailable = false
        edit(f, EditAction.PASTE)
        finishImage(f)
        assertNotNull(f.connection.content)
        assertTrue(f.connection.menus.isEmpty())
        assertTrue(f.connection.keys.isEmpty())
        assertEquals(f.service.getString(R.string.clip_image_paste_unsupported), f.service.toastTextForTest())
    }

    @Test fun clipboard_image_paste_reads_the_cursor_from_either_supported_query() {
        for (surroundingAvailable in listOf(true, false)) {
            val f = fixture(type = if (surroundingAvailable) InputType.TYPE_CLASS_TEXT else
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)
            clipboard(f).setPrimaryClip(image(f))
            f.connection.acceptImage = false
            f.connection.commitText("Existing draft", 1)
            f.connection.surroundingTextAvailable = surroundingAvailable
            f.connection.extractedTextAvailable = !surroundingAvailable
            var pasteRequests = 0
            f.connection.onKey = { if (it.keyCode == KeyEvent.KEYCODE_PASTE && it.action == KeyEvent.ACTION_DOWN) pasteRequests++ }
            edit(f, EditAction.PASTE)
            finishImage(f)
            assertEquals(1, pasteRequests)
            assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), f.connection.keys.map { it.action })
            assertTrue(f.connection.menus.isEmpty())
            assertEquals("Existing draft", f.connection.editable.toString())
            assertEquals(14, Selection.getSelectionStart(f.connection.editable))
            assertEquals(14, Selection.getSelectionEnd(f.connection.editable))
            assertEquals(f.service.getString(R.string.clip_image_paste_requested), f.service.toastTextForTest())
            store(f).stopSaving()
        }
    }

    @Test fun image_paste_restores_tracking_after_a_receiver_exception() {
        val f = fixture()
        clipboard(f).setPrimaryClip(image(f))
        f.connection.onContent = { throw IllegalStateException("receiver failure") }
        edit(f, EditAction.PASTE)
        finishImage(f)
        assertNotSame(f.connection, f.service.currentInputConnection)
        f.service.commitText("tracked")
        edit(f, EditAction.UNDO)
        assertEquals("", f.connection.editable.toString())
    }

    @Test fun image_cut_waits_for_remote_copy_to_complete_before_reading_the_clipboard() {
        val f = fixture()
        val clip = image(f)
        f.connection.commitText("\uFFFC", 1)
        f.connection.setSelection(0, 1)
        f.service.onUpdateSelection(1, 1, 0, 1, -1, -1)
        clipboard(f).setPrimaryClip(ClipData.newPlainText("old", "old"))
        f.connection.deferCopy = true
        f.connection.onMenu = { id -> if (id == android.R.id.copy) clipboard(f).setPrimaryClip(clip) }
        edit(f, EditAction.CUT)
        finishImage(f)
        assertEquals(listOf(android.R.id.copy, android.R.id.cut), f.connection.menus)
        assertEquals("", f.connection.editable.toString())
        assertTrue(store(f).latestEntry()!!.isImage)
    }

    @Test fun delayed_image_cut_does_not_delete_a_changed_selection() {
        val f = fixture()
        val clip = image(f)
        f.connection.commitText("\uFFFCother", 1)
        f.connection.setSelection(0, 1)
        f.service.onUpdateSelection(6, 6, 0, 1, -1, -1)
        clipboard(f).setPrimaryClip(ClipData.newPlainText("old", "old"))
        f.connection.onMenu = { id -> if (id == android.R.id.copy) clipboard(f).setPrimaryClip(clip) }
        edit(f, EditAction.CUT)
        f.connection.setSelection(1, 6)
        f.service.onUpdateSelection(0, 1, 1, 6, -1, -1)
        finishImage(f)
        assertEquals("\uFFFCother", f.connection.editable.toString())
        assertFalse(f.connection.menus.contains(android.R.id.cut))
    }
    @Test fun image_copy_republishes_aegis_owned_bytes_and_survives_source_removal() {
        val f = fixture()
        val clip = image(f)
        f.connection.commitText("\uFFFC", 1)
        f.connection.setSelection(0, 1)
        f.service.onUpdateSelection(1, 1, 0, 1, -1, -1)
        clipboard(f).setPrimaryClip(ClipData.newPlainText("old", "old"))
        f.connection.onMenu = { id -> if (id == android.R.id.copy) clipboard(f).setPrimaryClip(clip) }
        edit(f, EditAction.COPY)
        finishImage(f)
        val published = clipboard(f).primaryClip!!.getItemAt(0).uri
        assertNotEquals(clip.getItemAt(0).uri, published)
        File(f.service.filesDir, "clips/images/source.png").delete()
        assertTrue(f.service.contentResolver.openInputStream(published)!!.use { it.readBytes().isNotEmpty() })
        assertEquals("\uFFFC", f.connection.editable.toString())
    }

    @Test fun native_copy_preserves_rich_clipboard_content_despite_a_false_menu_return() {
        val f = fixture()
        f.connection.commitText("opaque picture representation", 1)
        f.connection.setSelection(0, f.connection.editable!!.length)
        f.service.onUpdateSelection(0, 0, 0, f.connection.editable!!.length, -1, -1)
        val clip = image(f)
        clipboard(f).setPrimaryClip(ClipData.newPlainText("old", "old"))
        f.connection.acceptMenus = false
        f.connection.onMenu = { id -> if (id == android.R.id.copy) clipboard(f).setPrimaryClip(clip) }
        edit(f, EditAction.COPY)
        finishImage(f)
        assertTrue(store(f).latestEntry()!!.isImage)
        assertNotNull(clipboard(f).primaryClip!!.getItemAt(0).uri)
        assertNull(clipboard(f).primaryClip!!.getItemAt(0).text)
        assertEquals("opaque picture representation", f.connection.editable.toString())
    }

    @Test fun native_copy_keeps_the_hosts_html_instead_of_overwriting_it_with_plain_text() {
        val f = fixture()
        f.connection.commitText("bold", 1)
        f.connection.setSelection(0, 4)
        f.service.onUpdateSelection(4, 4, 0, 4, -1, -1)
        f.connection.onMenu = { id -> if (id == android.R.id.copy)
            clipboard(f).setPrimaryClip(ClipData.newHtmlText("host", "bold", "<b>bold</b>")) }
        edit(f, EditAction.COPY)
        assertEquals("<b>bold</b>", clipboard(f).primaryClip!!.getItemAt(0).htmlText)
        assertEquals("bold", store(f).latest())
    }

    @Test fun image_copy_and_cut_with_history_paused_use_the_hosts_system_clipboard() {
        for (cut in listOf(false, true)) {
            val f = fixture()
            f.service.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().putBoolean("clip_history", false).commit()
            f.connection.commitText("\uFFFC", 1)
            f.connection.setSelection(0, 1)
            f.service.onUpdateSelection(1, 1, 0, 1, -1, -1)
            val clip = image(f)
            clipboard(f).setPrimaryClip(ClipData.newPlainText("old", "old"))
            f.connection.onMenu = { id -> if (id == android.R.id.copy) clipboard(f).setPrimaryClip(clip) }
            edit(f, if (cut) EditAction.CUT else EditAction.COPY)
            assertEquals(clip.getItemAt(0).uri, clipboard(f).primaryClip!!.getItemAt(0).uri)
            assertNull(store(f).latestEntry())
            assertEquals(if (cut) "" else "\uFFFC", f.connection.editable.toString())
        }
    }

    @Test fun system_image_paste_with_history_paused_does_not_need_an_aegis_history_entry() {
        val f = fixture()
        f.service.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().putBoolean("clip_history", false).commit()
        val clip = image(f)
        clipboard(f).setPrimaryClip(clip)
        edit(f, EditAction.PASTE)
        finishImage(f)
        assertNull(store(f).latestEntry())
        assertNull(f.connection.content)
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), f.connection.keys.map { it.action })
        assertTrue(f.connection.keys.all { it.keyCode == KeyEvent.KEYCODE_PASTE && it.metaState == 0 })
        assertEquals(clip.getItemAt(0).uri, clipboard(f).primaryClip!!.getItemAt(0).uri)
    }

    @Test fun image_deletion_undo_replays_the_image_after_clipboard_history_and_system_clipboard_change() = restoreDeletedImage(false)

    @Test fun image_deletion_swipe_down_replays_the_image_after_clipboard_history_and_system_clipboard_change() = restoreDeletedImage(true)

    private fun restoreDeletedImage(swipeDown: Boolean) {
        for (layout in listOf(LayoutChoice.CN_NINE, LayoutChoice.CN_ALPHA)) {
            for (paused in listOf(false, true)) {
                for (native in listOf(false, true)) {
                    val f = fixture(layout)
                    f.service.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().putBoolean("clip_history", !paused).commit()
                    f.connection.acceptImage = native
                    f.connection.commitText("LEFT|RIGHT", 1)
                    f.connection.setSelection(5, 5)
                    f.service.onUpdateSelection(10, 10, 5, 5, -1, -1)
                    var received = 0
                    var latestMarker = ""
                    fun insertImage() {
                        received++
                        latestMarker = "[private-object-" + "x".repeat(received) + "]"
                        f.connection.commitText(latestMarker, 1)
                    }
                    f.connection.onContent = { if (native) insertImage() }
                    f.connection.onKey = { event ->
                        if (event.keyCode == KeyEvent.KEYCODE_PASTE && event.action == KeyEvent.ACTION_DOWN) {
                            val uri = clipboard(f).primaryClip!!.getItemAt(0).uri!!
                            assertNotNull(f.service.contentResolver.openInputStream(uri)?.use { it.readBytes() })
                            insertImage()
                        }
                    }
                    clipboard(f).setPrimaryClip(image(f))
                    edit(f, EditAction.PASTE)
                    finishImage(f)
                    assertEquals(1, received)
                    if (paused) assertNull(store(f).latestEntry())
                    val oldMarker = latestMarker
                    f.connection.setSelection(5, 5 + oldMarker.length)
                    f.service.onUpdateSelection(5 + oldMarker.length, 5 + oldMarker.length, 5, 5 + oldMarker.length, -1, -1)
                    edit(f, EditAction.DELETE)
                    assertEquals("LEFT|RIGHT", f.connection.editable.toString())
                    val entry = store(f).latestEntry()
                    if (entry != null) store(f).delete(entry.key)
                    clipboard(f).setPrimaryClip(ClipData.newPlainText("replacement", "unrelated clipboard"))
                    finishImage(f)
                    if (swipeDown) {
                        val canRestore = f.service.javaClass.getDeclaredMethod("canBackspaceSwipe", Boolean::class.javaPrimitiveType)
                            .apply { isAccessible = true }.invoke(f.service, false) as Boolean
                        assertTrue(canRestore)
                        f.service.javaClass.getDeclaredMethod("backspaceSwipe", Boolean::class.javaPrimitiveType)
                            .apply { isAccessible = true }.invoke(f.service, false)
                    } else edit(f, EditAction.UNDO)
                    assertEquals(2, received)
                    if (native) assertEquals("unrelated clipboard", clipboard(f).primaryClip!!.getItemAt(0).text.toString())
                    assertNotEquals(oldMarker, latestMarker)
                    assertEquals("LEFT|" + latestMarker + "RIGHT", f.connection.editable.toString())
                    assertFalse(f.connection.menus.contains(android.R.id.undo))
                    f.service.onFinishInput()
                    store(f).stopSaving()
                }
            }
        }
    }

    @Test fun an_uncertain_paste_key_result_still_tracks_the_image_that_was_inserted() {
        for (throwsOnUp in listOf(false, true)) {
            val f = fixture()
            f.connection.acceptImage = false
            f.connection.rejectPasteUp = true
            var received = 0
            f.connection.onKey = { event ->
                if (event.keyCode == KeyEvent.KEYCODE_PASTE) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        received++
                        f.connection.commitText("[private-image-$received]", 1)
                    } else if (throwsOnUp) throw IllegalStateException("up result unavailable")
                }
            }
            clipboard(f).setPrimaryClip(image(f))
            edit(f, EditAction.PASTE)
            finishImage(f)
            assertEquals("[private-image-1]", f.connection.editable.toString())
            f.connection.setSelection(0, f.connection.editable!!.length)
            f.service.onUpdateSelection(-1, -1, 0, f.connection.editable!!.length, -1, -1)
            edit(f, EditAction.DELETE)
            assertEquals("", f.connection.editable.toString())
            edit(f, EditAction.UNDO)
            assertEquals(2, received)
            assertEquals("[private-image-2]", f.connection.editable.toString())
            f.service.onFinishInput()
            store(f).stopSaving()
        }
    }

    @Test fun backspace_swipe_restores_images_at_the_current_selection_and_remains_undoable() {
        for (layout in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            for (native in listOf(true, false)) {
                for (paused in listOf(false, true)) {
                    val f = fixture(layout)
                    val c = f.connection
                    f.service.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().putBoolean("clip_history", !paused).commit()
                    c.acceptImage = native
                    c.commitText("LEFT|RIGHT", 1)
                    c.setSelection(5, 5)
                    f.service.onUpdateSelection(10, 10, 5, 5, -1, -1)
                    var received = 0
                    var marker = ""
                    fun insertImage() {
                        received++
                        marker = "[private-object-" + "x".repeat(received) + "]"
                        c.commitText(marker, 1)
                    }
                    c.onContent = { if (native) insertImage() }
                    c.onKey = { event ->
                        if (event.keyCode == KeyEvent.KEYCODE_PASTE && event.action == KeyEvent.ACTION_DOWN) {
                            val uri = clipboard(f).primaryClip!!.getItemAt(0).uri!!
                            assertNotNull(f.service.contentResolver.openInputStream(uri)?.use { it.readBytes() })
                            insertImage()
                        }
                    }
                    clipboard(f).setPrimaryClip(image(f))
                    edit(f, EditAction.PASTE)
                    finishImage(f)
                    assertEquals(1, received)
                    val oldMarker = marker
                    fun swipe(up: Boolean) {
                        f.service.javaClass.getDeclaredMethod("handleBackspaceSwipe", Boolean::class.javaPrimitiveType)
                            .apply { isAccessible = true }.invoke(f.service, up)
                    }
                    swipe(true)
                    assertEquals("", c.editable.toString())
                    assertFalse(File(f.service.filesDir, "cleared_text.txt").exists())
                    store(f).latestEntry()?.let { store(f).delete(it.key) }
                    clipboard(f).setPrimaryClip(ClipData.newPlainText("replacement", "unrelated clipboard"))
                    finishImage(f)
                    f.service.commitText("new [] draft")
                    c.setSelection(4, 6)
                    f.service.onUpdateSelection(12, 12, 4, 6, -1, -1)
                    swipe(false)
                    assertEquals(2, received)
                    assertNotEquals(oldMarker, marker)
                    assertEquals("new LEFT|${marker}RIGHT draft", c.editable.toString())
                    assertEquals(14 + marker.length, Selection.getSelectionStart(c.editable))
                    assertEquals(Selection.getSelectionStart(c.editable), Selection.getSelectionEnd(c.editable))
                    if (native) assertEquals("unrelated clipboard", clipboard(f).primaryClip!!.getItemAt(0).text.toString())
                    edit(f, EditAction.UNDO)
                    assertEquals("new [] draft", c.editable.toString())
                    swipe(false)
                    assertEquals("new [] draft", c.editable.toString())
                    assertEquals(2, received)
                    assertFalse(c.menus.contains(android.R.id.undo))
                    f.service.onFinishInput()
                    store(f).stopSaving()
                }
            }
        }
    }

    @Test fun rejected_image_paste_without_dispatch_preserves_existing_text_undo() {
        val f = fixture()
        f.service.commitText("keep undo")
        f.connection.setSelection(0, 4)
        f.service.onUpdateSelection(9, 9, 0, 4, -1, -1)
        f.connection.acceptImage = false
        clipboard(f).setPrimaryClip(image(f))
        edit(f, EditAction.PASTE)
        finishImage(f)
        assertTrue(f.connection.keys.isEmpty())
        edit(f, EditAction.UNDO)
        assertEquals("", f.connection.editable.toString())
    }

    @Test fun failed_image_import_does_not_cut_the_source() {
        val f = fixture()
        val clip = image(f)
        File(f.service.filesDir, "clips/images/source.png").delete()
        f.connection.commitText("\uFFFC", 1)
        f.connection.setSelection(0, 1)
        f.service.onUpdateSelection(1, 1, 0, 1, -1, -1)
        clipboard(f).setPrimaryClip(ClipData.newPlainText("old", "old"))
        f.connection.onMenu = { id -> if (id == android.R.id.copy) clipboard(f).setPrimaryClip(clip) }
        edit(f, EditAction.CUT)
        finishImage(f)
        assertEquals("\uFFFC", f.connection.editable.toString())
        assertFalse(f.connection.menus.contains(android.R.id.cut))
    }

}
