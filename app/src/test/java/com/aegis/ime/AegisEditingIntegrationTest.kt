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

}
