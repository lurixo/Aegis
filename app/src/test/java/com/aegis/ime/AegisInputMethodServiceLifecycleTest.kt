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
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import com.aegis.ime.dict.ModelDownload
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.BackspaceGesture
import com.aegis.ime.ime.DecodeLane
import com.aegis.ime.ime.EditAction
import com.aegis.ime.ime.EditPanelView
import com.aegis.ime.ime.EmojiView
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.ui.DictDownloadWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w853dp-h388dp-land-hdpi")
class AegisInputMethodServiceLifecycleTest {

    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> =
            if (composing.isEmpty()) emptyList() else listOf("候选")
    }

    private data class Fixture(
        val service: AegisInputMethodService,
        val controller: KeyboardController,
        val info: EditorInfo,
        var view: InputView,
    )

    private class RecordingInputConnection(target: View) : BaseInputConnection(target, true) {
        val composingUpdates = ArrayList<String>()
        val committedChunks = ArrayList<String>()
        val contextMenuActions = ArrayList<Int>()
        val sentKeyCodes = ArrayList<Int>()
        val sentKeyMetas = ArrayList<Int>()
        val sentKeyEvents = ArrayList<Pair<Int, Int>>()
        val surroundingDeletes = ArrayList<Int>()
        var onContextMenuAction: (Int) -> Unit = {}
        var finishes = 0
        var hidesSelection = false
        var hidesExtractedSelection = false
        var hidesExtractedText = false
        var hidesSurroundingOffset = false
        var contextMenuAccepted = true

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            surroundingDeletes.add(beforeLength)
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun getSelectedText(flags: Int): CharSequence? =
            if (hidesSelection) null else super.getSelectedText(flags)

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            sentKeyEvents.add(event.action to event.keyCode)
            if (event.action != KeyEvent.ACTION_DOWN) return super.sendKeyEvent(event)
            sentKeyCodes.add(event.keyCode)
            sentKeyMetas.add(event.metaState)
            if (event.keyCode == KeyEvent.KEYCODE_DEL) backspaceEditable()
            return super.sendKeyEvent(event)
        }

        private fun backspaceEditable() {
            val content = editable ?: return
            val start = Selection.getSelectionStart(content)
            val end = Selection.getSelectionEnd(content)
            if (start < 0 || end < 0) return
            val lo = minOf(start, end)
            val hi = maxOf(start, end)
            if (lo != hi) {
                content.delete(lo, hi)
                Selection.setSelection(content, lo)
                return
            }
            if (lo == 0) return
            val from = if (lo >= 2 && Character.isSurrogatePair(content[lo - 2], content[lo - 1])) lo - 2 else lo - 1
            content.delete(from, lo)
            Selection.setSelection(content, from)
        }

        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
            if (hidesExtractedText) return null
            val content = editable ?: return null
            val a = Selection.getSelectionStart(content)
            val b = Selection.getSelectionEnd(content)
            if (a < 0 || b < 0) return null
            val selStart = minOf(a, b)
            val selEnd = maxOf(a, b)
            val from = maxOf(0, selStart - beforeLength)
            val to = minOf(content.length, selEnd + afterLength)
            val offset = if (hidesSurroundingOffset) -1 else from
            return SurroundingText(content.subSequence(from, to), selStart - from, selEnd - from, offset)
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            if (hidesExtractedText) return null
            val content = editable ?: return null
            return ExtractedText().apply {
                startOffset = 0
                partialStartOffset = -1
                partialEndOffset = -1
                text = content.subSequence(0, content.length)
                val end = Selection.getSelectionEnd(content)
                selectionStart = if (hidesExtractedSelection) end else Selection.getSelectionStart(content)
                selectionEnd = end
            }
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composingUpdates.add(text?.toString().orEmpty())
            return super.setComposingText(text, newCursorPosition)
        }

        override fun finishComposingText(): Boolean {
            finishes++
            return super.finishComposingText()
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            committedChunks.add(text?.toString().orEmpty())
            return super.commitText(text, newCursorPosition)
        }

        override fun performContextMenuAction(id: Int): Boolean {
            contextMenuActions.add(id)
            onContextMenuAction(id)
            if (id == android.R.id.paste) commitText("SYSTEM_CLIPBOARD", 1)
            return contextMenuAccepted
        }
    }

    private fun editor(
        packageName: String = "com.example.editor",
        fieldId: Int = 101,
        fieldName: String? = "message",
        inputType: Int = InputType.TYPE_CLASS_TEXT,
    ) = EditorInfo().apply {
        this.packageName = packageName
        this.fieldId = fieldId
        this.fieldName = fieldName
        this.inputType = inputType
    }

    private fun userModelOf(service: AegisInputMethodService) =
        service.javaClass.getDeclaredField("userModel").apply { isAccessible = true }
            .get(service) as com.aegis.ime.user.UserModel

    @Test fun the_automatic_learning_switch_reaches_the_user_dictionary_on_both_paths() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("aegis", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(com.aegis.ime.ui.PREF_AUTO_LEARN_ON, false).commit()

        val f = fixture()
        val model = userModelOf(f.service)
        assertFalse("starting an input session must carry the switch to the user dictionary", model.autoLearnEnabled)
        model.recordWord("ninen", "你呢嗯", 1L, incrementCount = true)
        assertTrue("and nothing may be recorded while it is off", model.isEmpty())

        prefs.edit().putBoolean(com.aegis.ime.ui.PREF_AUTO_LEARN_ON, true).commit()
        val hot = f.service.javaClass.getDeclaredField("settingsHotApply").apply { isAccessible = true }
            .get(f.service) as android.content.SharedPreferences.OnSharedPreferenceChangeListener
        hot.onSharedPreferenceChanged(prefs, com.aegis.ime.ui.PREF_AUTO_LEARN_ON)

        assertTrue("turning it back on must reach the user dictionary too", model.autoLearnEnabled)
        model.recordWord("ninen", "你呢嗯", 1L, incrementCount = true)
        assertFalse("and recording resumes", model.isEmpty())
        prefs.edit().remove(com.aegis.ime.ui.PREF_AUTO_LEARN_ON).commit()
    }

    @Test fun saving_learned_data_must_not_swallow_a_user_dictionary_changed_outside() {
        val f = fixture()
        val service = f.service
        val model = userModelOf(service)
        val userDb = java.io.File(service.filesDir, "userdb.txt")
        com.aegis.ime.user.UserModel().apply { addManualWord("nihao", "你好", 1L) }.save(userDb)
        val loadedFrom = userDb.lastModified()
        service.javaClass.getDeclaredField("userStoresLoaded").apply { isAccessible = true }.setBoolean(service, true)
        service.javaClass.getDeclaredField("userDbMtime").apply { isAccessible = true }.setLong(service, loadedFrom)

        val learning = service.javaClass.getDeclaredField("userLearning").apply { isAccessible = true }
            .get(service) as com.aegis.ime.user.UserLearning
        repeat(8) {
            var prev: String? = null
            for ((word, reading) in listOf("你" to "ni", "呢" to "ne", "嗯" to "n")) {
                learning.observeCommit(prev, word, reading, 1_700_000_000_000L)
                prev = word
            }
            learning.observeBreak()
        }
        assertTrue("the learning store must have something to write", learning.dirty)

        com.aegis.ime.user.UserModel().apply {
            load(userDb)
            addManualWord("waibu", "外部", 2L)
        }.save(userDb)
        userDb.setLastModified(loadedFrom + 5_000L)

        assertTrue(liveUserDictHost(service).clearLearned())
        service.onStartInput(editor(), false)
        drainWriteLane(service)

        assertEquals(
            "a word restored into userdb.txt from outside must still reach the running keyboard",
            listOf("外部"),
            model.readingSnapshot()["waibu"],
        )
    }

    private fun liveUserDictHost(service: AegisInputMethodService): com.aegis.ime.user.UserDictHot.Host {
        val delegate = service.javaClass.getDeclaredField("liveUserDictHost\$delegate").run {
            isAccessible = true
            get(service) as Lazy<*>
        }
        return delegate.value as com.aegis.ime.user.UserDictHot.Host
    }

    private fun drainWriteLane(service: AegisInputMethodService) {
        val host = liveUserDictHost(service)
        val io = host.javaClass.getDeclaredField("io").run {
            isAccessible = true
            get(host) as java.util.concurrent.ExecutorService
        }
        io.submit { }.get(10, TimeUnit.SECONDS)
    }

    private fun fixture(info: EditorInfo = editor(), decodeLane: DecodeLane? = null): Fixture {

        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val controller = KeyboardController(service, engine, decodeLane)
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, controller)
        }
        service.onStartInput(info, false)
        val view = service.onCreateInputView() as InputView
        service.onStartInputView(info, false)
        return Fixture(service, controller, info, view)
    }

    private fun cachedPanel(service: AegisInputMethodService, fieldName: String): Any? =
        service.javaClass.getDeclaredField(fieldName).run {
            isAccessible = true
            get(service)
        }

    private fun installInputConnection(service: AegisInputMethodService, connection: RecordingInputConnection) {
        val framework = requireNotNull(service.javaClass.superclass)
        for (fieldName in listOf("mInputConnection", "mStartedInputConnection")) {
            framework.getDeclaredField(fieldName).apply {
                isAccessible = true
                set(service, connection)
            }
        }
    }

    private enum class AnchorEffect { RESYNC, HOST_NEUTRAL, SELECTION_OWNED }

    private val editActionAnchorEffect: Map<EditAction, AnchorEffect> = mapOf(
        EditAction.UNDO to AnchorEffect.SELECTION_OWNED,
        EditAction.DELETE to AnchorEffect.RESYNC,
        EditAction.TAB to AnchorEffect.RESYNC,
        EditAction.FORWARD_DELETE to AnchorEffect.RESYNC,
        EditAction.CUT to AnchorEffect.RESYNC,
        EditAction.SELECT_ALL to AnchorEffect.RESYNC,
        EditAction.PASTE to AnchorEffect.RESYNC,
        EditAction.COPY to AnchorEffect.HOST_NEUTRAL,
        EditAction.UP to AnchorEffect.SELECTION_OWNED,
        EditAction.DOWN to AnchorEffect.SELECTION_OWNED,
        EditAction.LEFT to AnchorEffect.SELECTION_OWNED,
        EditAction.RIGHT to AnchorEffect.SELECTION_OWNED,
        EditAction.HOME to AnchorEffect.SELECTION_OWNED,
        EditAction.END to AnchorEffect.SELECTION_OWNED,
        EditAction.START_SELECT to AnchorEffect.SELECTION_OWNED,
        EditAction.BACK to AnchorEffect.SELECTION_OWNED,
    )

    @Test fun every_edit_action_declares_its_selection_anchor_effect() {
        assertEquals(
            "a new EditAction must declare whether it invalidates the selection anchor",
            EditAction.entries.toSet(),
            editActionAnchorEffect.keys,
        )
    }

    @Test fun the_panel_input_surface_stays_within_the_classified_paths() {
        assertEquals(
            "a new edit panel callback must be classified in the selection anchor inventory",
            setOf("onAction", "onBackspaceSwipe", "backspaceSwipeAvailable"),
            callbackNames(EditPanelView::class.java),
        )
        assertEquals(
            "a new backspace gesture outcome must be classified in the selection anchor inventory",
            setOf("onRepeat", "onSwipe", "canSwipe"),
            callbackNames(BackspaceGesture::class.java),
        )
    }

    private fun callbackNames(type: Class<*>): Set<String> = type.declaredMethods
        .filter { method ->
            method.name.startsWith("set") &&
                method.parameterTypes.size == 1 &&
                kotlin.Function::class.java.isAssignableFrom(method.parameterTypes[0])
        }
        .map { it.name.removePrefix("set").replaceFirstChar { first -> first.lowercase() } }
        .toSet()

    private fun selectionStart(connection: RecordingInputConnection): Int =
        Selection.getSelectionStart(requireNotNull(connection.editable))

    private fun selectionEnd(connection: RecordingInputConnection): Int =
        Selection.getSelectionEnd(requireNotNull(connection.editable))

    private fun hideShowThroughRealServiceCallbacks(f: Fixture) {
        val sameView = f.view
        f.service.onFinishInputView(false)
        f.service.onWindowHidden()

        f.service.onStartInputView(f.info, false)
        assertTrue("hide/show keeps the framework view", sameView === f.view)
    }

    @Test fun starting_the_keyboard_with_no_dictionary_starts_no_download() {
        val f = fixture()

        f.service.onStartInputView(f.info, true)
        f.service.onFinishInputView(false)
        f.service.onStartInputView(f.info, false)

        assertFalse(ModelDownload.isDictDownloaded(f.service.filesDir))
        assertFalse("the keyboard must not start the dictionary download on its own", DictDownloadWork.snapshot(f.service).downloading)
        assertFalse(ModelDownload.dictZipFile(f.service.filesDir).exists())
        assertFalse(ModelDownload.dictPartFile(f.service.filesDir).exists())
    }

    @Test fun window_hidden_restores_nine_twenty_six_and_english_base_keyboards() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("aegis", 0)
        val hadLayout = prefs.contains("cn_layout")
        val previousLayout = prefs.getString("cn_layout", "nine")
        try {
            prefs.edit().putString("cn_layout", "nine").commit()
            fixture().also { f ->
                f.controller.onKey(Key("", action = KeyAction.SWITCH_NUMPAD))
                hideShowThroughRealServiceCallbacks(f)
                assertEquals(LayoutId.NINE, f.controller.activeLayoutId())
            }

            prefs.edit().putString("cn_layout", "alpha").commit()
            fixture().also { f ->
                f.controller.onKey(Key("", action = KeyAction.SWITCH_NUMPAD))
                hideShowThroughRealServiceCallbacks(f)
                assertEquals(LayoutId.ALPHA, f.controller.activeLayoutId())
            }

            prefs.edit().putString("cn_layout", "nine").commit()
            fixture().also { f ->
                f.controller.onKey(Key("", action = KeyAction.TOGGLE_LANG))
                f.controller.onKey(Key("", action = KeyAction.SWITCH_NUMPAD))
                hideShowThroughRealServiceCallbacks(f)
                assertEquals(LayoutId.ALPHA, f.controller.activeLayoutId())
                f.controller.onKey(Key("a", output = "a"))
                assertEquals("", f.controller.preeditForTest())
            }
        } finally {
            val edit = prefs.edit()
            if (hadLayout) edit.putString("cn_layout", previousLayout) else edit.remove("cn_layout")
            edit.commit()
        }
    }

    @Test fun a_new_app_session_starts_in_the_configured_default_language() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("aegis", 0)
        val hadLang = prefs.contains("pref_default_lang")
        val previousLang = prefs.getString("pref_default_lang", "cn")
        try {
            prefs.edit().putString("pref_default_lang", "en").commit()
            fixture().also { f ->
                assertEquals("the EN default opens the English 26-key", LayoutId.ALPHA, f.controller.activeLayoutId())
                f.controller.onKey(Key("a", output = "a"))
                assertEquals("", f.controller.preeditForTest())

                f.controller.onKey(Key("", action = KeyAction.TOGGLE_LANG))
                assertEquals(LayoutId.NINE, f.controller.activeLayoutId())
                val sameAppField = editor(fieldId = 202)
                f.service.onStartInput(sameAppField, true)
                f.service.onStartInputView(sameAppField, true)
                assertEquals("same-package continuity keeps the manual Chinese", LayoutId.NINE, f.controller.activeLayoutId())

                val differentApp = editor(packageName = "com.other.editor")
                f.service.onStartInput(differentApp, true)
                f.service.onStartInputView(differentApp, true)
                assertEquals("a different app starts back on the EN default", LayoutId.ALPHA, f.controller.activeLayoutId())
                f.controller.onKey(Key("a", output = "a"))
                assertEquals("", f.controller.preeditForTest())
            }

            prefs.edit().putString("pref_default_lang", "cn").commit()
            fixture().also { f ->
                f.controller.onKey(Key("", action = KeyAction.TOGGLE_LANG))
                assertEquals(LayoutId.ALPHA, f.controller.activeLayoutId())
                val differentApp = editor(packageName = "com.other.editor")
                f.service.onStartInput(differentApp, true)
                f.service.onStartInputView(differentApp, true)
                assertEquals("the CN default overrides the remembered EN in a new app", LayoutId.NINE, f.controller.activeLayoutId())
            }
        } finally {
            val edit = prefs.edit()
            if (hadLang) edit.putString("pref_default_lang", previousLang) else edit.remove("pref_default_lang")
            edit.commit()
        }
    }

    @Test fun a_same_editor_start_keeps_the_emoji_clear_confirmation_up() {
        val f = fixture()
        f.service.javaClass.getDeclaredMethod("showEmojiPanel").apply { isAccessible = true }.invoke(f.service)
        val panel = requireNotNull(cachedPanel(f.service, "emojiView")) as EmojiView
        panel.clearBtnForTest().performClick()
        assertEquals(View.VISIBLE, panel.clearDialogForTest().visibility)

        f.service.onStartInput(f.info, true)
        f.service.onStartInputView(f.info, true)
        assertTrue(f.view.isPanelShowing(panel))
        assertEquals("a same-editor restart keeps the confirmation up", View.VISIBLE, panel.clearDialogForTest().visibility)

        f.service.onStartInput(f.info, false)
        f.service.onStartInputView(f.info, false)
        assertTrue(f.view.isPanelShowing(panel))
        assertEquals("a stable same-editor start without the restart flag keeps it too", View.VISIBLE, panel.clearDialogForTest().visibility)
    }

    @Test fun symbol_panel_and_candidate_pairs_follow_the_current_paragraph_on_both_layouts() {
        for (nine in listOf(false, true)) {
            for ((initial, caret, expected) in listOf(
                Triple("文字", 2, "文字“”"),
                Triple("文字\n下一段", 2, "文字“”\n下一段"),
                Triple("文字\r\n下一段", 2, "文字“”\r\n下一段"),
                Triple("文字后文", 2, "文字“后文"),
            )) {
                for (candidate in listOf(false, true)) {
                    val f = fixture()
                    val connection = RecordingInputConnection(FrameLayout(f.service))
                    installInputConnection(f.service, connection)
                    connection.commitText(initial, 1)
                    connection.setSelection(caret, caret)
                    f.controller.switchTextLayoutForTest(nine)
                    if (candidate) {
                        val input = if (nine) com.aegis.ime.decoder.T9Pinyin.toT9("yinhao") else "yin'hao"
                        input.forEach { f.controller.onKey(Key(it.toString(), output = it.toString())) }
                        if (nine) for (syllable in listOf("yin", "hao")) {
                            val index = f.controller.expandedReadings().indexOfLast { it == syllable }
                            assertTrue("$syllable reading available", index >= 0)
                            f.controller.onPickReadingIndex(index)
                        }
                        val index = f.controller.candidateWords().indexOf("“")
                        assertTrue("quote available on nine=$nine", index >= 0)
                        f.controller.onPickCandidate(index)
                    } else f.service.commitSymbol("“")
                    assertEquals("nine=$nine candidate=$candidate", expected, connection.editable.toString())
                    assertEquals(caret + 1, selectionStart(connection))
                    assertEquals(caret + 1, selectionEnd(connection))
                }
            }
        }
    }

}
