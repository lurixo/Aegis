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
import android.content.res.Configuration
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.aegis.ime.dict.ModelDownload
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.ClipboardView
import com.aegis.ime.ime.CustomSymbolPanel
import com.aegis.ime.ime.DecodeLane
import com.aegis.ime.ime.EditAction
import com.aegis.ime.ime.EditPanelView
import com.aegis.ime.ime.EmojiView
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.LargeCommit
import com.aegis.ime.ime.LayoutPanelView
import com.aegis.ime.ime.SymbolsView
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.ui.DictDownloadWork
import com.aegis.ime.user.ClipboardStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
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
        var finishes = 0

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
            if (id == android.R.id.paste) commitText("SYSTEM_CLIPBOARD", 1)
            return true
        }
    }

    private enum class StateKind { COMPOSITION, INLINE_EDIT, PHRASES_PANEL }

    private data class SeededState(
        val kind: StateKind,
        val snapshot: AegisInputMethodService.TransientStateSnapshot,
        val clipboard: ClipboardView? = null,
        val phraseCategory: String? = null,
    )

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

    private fun setCachedPanel(service: AegisInputMethodService, fieldName: String, value: Any) {
        service.javaClass.getDeclaredField(fieldName).run {
            isAccessible = true
            set(service, value)
        }
    }

    private fun capturedDensity(panel: Any): Float = panel.javaClass.getDeclaredField("density").run {
        isAccessible = true
        getFloat(panel)
    }

    private fun capturedInputDensity(view: InputView): Float = view.javaClass.getDeclaredField("keyboardView").run {
        isAccessible = true
        capturedDensity(requireNotNull(get(view)))
    }

    private fun installFrameworkInputFrame(f: Fixture): FrameLayout {
        val frame = FrameLayout(f.service).apply { addView(f.view) }
        val framework = requireNotNull(f.service.javaClass.superclass)
        framework.getDeclaredField("mInputFrame").apply { isAccessible = true; set(f.service, frame) }
        framework.getDeclaredField("mInputView").apply { isAccessible = true; set(f.service, f.view) }
        return frame
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

    private fun clipboardStore(service: AegisInputMethodService): ClipboardStore {
        val delegate = service.javaClass.getDeclaredField("clipboardStore\$delegate").run {
            isAccessible = true
            get(service) as Lazy<*>
        }
        return delegate.value as ClipboardStore
    }

    private fun handleEdit(service: AegisInputMethodService, action: EditAction) {
        service.javaClass.getDeclaredMethod("handleEdit", EditAction::class.java).apply {
            isAccessible = true
            invoke(service, action)
        }
    }

    private fun clipboard(service: AegisInputMethodService): ClipboardView {
        service.javaClass.getDeclaredMethod("showClipboardPanel").apply { isAccessible = true }.invoke(service)
        return service.javaClass.getDeclaredField("clipboardView").run {
            isAccessible = true
            get(service) as ClipboardView
        }
    }

    @Test fun split_selection_composes_into_one_region_and_finishes_when_the_popup_closes() {
        val f = fixture()
        val connection = RecordingInputConnection(FrameLayout(f.service))
        installInputConnection(f.service, connection)
        val cv = clipboard(f.service)
        cv.showSplitForTest("检查一下，检查")

        cv.onSplitSelectionChanged("检查")
        cv.onSplitSelectionChanged("检查，")
        cv.onSplitSelectionChanged("检查一下，")
        cv.onSplitSelectionChanged("一下，")

        assertEquals(listOf("检查", "检查，", "检查一下，", "一下，"), connection.composingUpdates)
        assertEquals("一下，", connection.editable.toString())
        assertEquals(0, connection.finishes)
        cv.hideOverlayForTest()
        assertEquals(1, connection.finishes)
        assertEquals("一下，", connection.editable.toString())
        cv.finishSplitSelection()
        assertEquals("the ended session must not finish twice", 1, connection.finishes)
    }

    @Test fun active_split_selection_finishes_once_when_the_input_target_ends() {
        val f = fixture()
        val connection = RecordingInputConnection(FrameLayout(f.service))
        installInputConnection(f.service, connection)
        val cv = clipboard(f.service)
        cv.showSplitForTest("检查一下，检查")
        cv.onSplitSelectionChanged("检查")

        assertEquals(0, connection.finishes)
        f.service.onFinishInput()
        assertEquals(1, connection.finishes)
        assertEquals("检查", connection.editable.toString())
        cv.finishSplitSelection()
        assertEquals("the ended target must not finish the split session twice", 1, connection.finishes)
    }

    @Test fun edit_paste_replaces_the_selection_with_the_latest_aegis_entry_without_system_paste() {
        val f = fixture()
        val connection = RecordingInputConnection(FrameLayout(f.service))
        installInputConnection(f.service, connection)
        val systemClipboard = f.service.getSystemService(ClipboardManager::class.java)
        systemClipboard.setPrimaryClip(ClipData.newPlainText("system", "SYSTEM_CLIPBOARD"))
        clipboardStore(f.service).apply {
            clearHistory()
            record("older")
            record("Aegis latest")
        }
        connection.commitText("before TARGET after", 1)
        connection.committedChunks.clear()
        connection.setSelection(7, 13)

        handleEdit(f.service, EditAction.PASTE)

        assertEquals("before Aegis latest after", connection.editable.toString())
        assertEquals(listOf("Aegis latest"), connection.committedChunks)
        assertTrue(connection.contextMenuActions.isEmpty())
    }

    @Test fun edit_paste_keeps_large_aegis_entries_on_the_chunked_commit_path() {
        val f = fixture()
        val connection = RecordingInputConnection(FrameLayout(f.service))
        installInputConnection(f.service, connection)
        val big = "大".repeat(LargeCommit.CHUNK + 1)
        clipboardStore(f.service).apply {
            clearHistory()
            record(big)
        }

        handleEdit(f.service, EditAction.PASTE)

        assertEquals(listOf(LargeCommit.CHUNK, 1), connection.committedChunks.map { it.length })
        assertEquals(big, connection.committedChunks.joinToString(""))
        assertEquals(big, connection.editable.toString())
        assertTrue(connection.contextMenuActions.isEmpty())
    }

    @Test fun edit_paste_does_nothing_when_aegis_history_is_empty() {
        val f = fixture()
        val connection = RecordingInputConnection(FrameLayout(f.service))
        installInputConnection(f.service, connection)
        f.service.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("system", "SYSTEM_CLIPBOARD"))
        clipboardStore(f.service).clearHistory()

        handleEdit(f.service, EditAction.PASTE)

        assertEquals("", connection.editable.toString())
        assertTrue(connection.committedChunks.isEmpty())
        assertTrue(connection.contextMenuActions.isEmpty())
    }

    private fun seed(f: Fixture, kind: StateKind): SeededState = when (kind) {
        StateKind.COMPOSITION -> {

            f.view.onKey(Key("6", output = "6"))
            f.view.onKey(Key("4", output = "4"))
            val state = f.service.transientStateForTest()
            assertTrue("composition must be concrete", state.composition.isNotEmpty())
            assertTrue(f.view.isComposing())
            SeededState(kind, state)
        }
        StateKind.INLINE_EDIT -> {
            val cv = clipboard(f.service)
            cv.onAddCategory()
            f.service.commitText("lifecycle-draft")
            val state = f.service.transientStateForTest()
            assertTrue(state.editActive)
            assertEquals("lifecycle-draft", state.editText)
            assertEquals("ADD_CATEGORY", state.editPurpose)
            assertTrue(f.view.isEditBarShowing())
            SeededState(kind, state, cv)
        }
        StateKind.PHRASES_PANEL -> {
            val cv = clipboard(f.service)
            cv.switchTabForTest(false)
            cv.onAddCategory()
            f.service.commitText("lifecycle-category")
            f.view.onEditConfirm()
            val state = f.service.transientStateForTest()
            assertEquals("CLIPBOARD", state.panel)
            assertEquals("PHRASES", state.panelDetail)
            assertFalse(cv.isClipboardTabForTest())
            assertEquals("lifecycle-category", cv.phraseCatForTest())
            assertTrue(f.view.isPanelShowing(cv))
            SeededState(kind, state, cv, cv.phraseCatForTest())
        }
    }

    private fun assertPreserved(f: Fixture, seeded: SeededState) {
        val now = f.service.transientStateForTest()
        when (seeded.kind) {
            StateKind.COMPOSITION -> {
                assertEquals(seeded.snapshot.composition, now.composition)
                assertTrue(f.view.isComposing())
            }
            StateKind.INLINE_EDIT -> {
                assertTrue(now.editActive)
                assertEquals(seeded.snapshot.editText, now.editText)
                assertEquals(seeded.snapshot.editPurpose, now.editPurpose)
                assertTrue(f.view.isEditBarShowing())
                assertFalse(f.view.panelShown)
            }
            StateKind.PHRASES_PANEL -> {
                val cv = requireNotNull(seeded.clipboard)
                assertEquals("CLIPBOARD", now.panel)
                assertEquals("PHRASES", now.panelDetail)
                assertFalse(cv.isClipboardTabForTest())
                assertEquals(seeded.phraseCategory, cv.phraseCatForTest())
                assertTrue(f.view.isPanelShowing(cv))
            }
        }
    }

    private fun rotateThroughRealServiceCallbacks(f: Fixture, seeded: SeededState) {
        val oldView = f.view
        f.service.onConfigurationChanged(
            Configuration(f.service.resources.configuration).apply { orientation = Configuration.ORIENTATION_PORTRAIT },
        )

        f.service.onStartInput(editor(), true)
        f.view = f.service.onCreateInputView() as InputView
        f.service.onStartInputView(editor(), false)
        assertNotSame("configuration must exercise replacement, not detach/reattach", oldView, f.view)
        assertPreserved(f, seeded)
    }

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

    @Test fun composition_survives_same_editor_restart_real_rotation_and_real_hide_show() {
        val f = fixture()
        val seeded = seed(f, StateKind.COMPOSITION)

        f.service.onStartInput(editor(), true)
        f.service.onStartInputView(editor(), true)
        assertPreserved(f, seeded)
        rotateThroughRealServiceCallbacks(f, seeded)
        hideShowThroughRealServiceCallbacks(f)
        assertPreserved(f, seeded)

        f.view.onKey(Key("6", output = "6"))
        assertTrue("restored controller remains live", f.service.transientStateForTest().composition.isNotEmpty())
    }

    @Test fun inline_edit_buffer_and_purpose_survive_real_rotation_but_clear_after_real_hide_show() {
        val f = fixture()
        val seeded = seed(f, StateKind.INLINE_EDIT)

        rotateThroughRealServiceCallbacks(f, seeded)
        f.view.onKey(Key("6", output = "6"))
        assertTrue(f.service.transientStateForTest().composition.isNotEmpty())
        hideShowThroughRealServiceCallbacks(f)

        assertCleared(f, seeded, "inline edit window hide")
        assertTrue(f.service.transientStateForTest().inputActive)
        assertEquals(f.info.packageName, f.service.transientStateForTest().targetPackage)
    }

    @Test fun inline_cancel_and_confirm_attach_only_the_final_phrase_panel_state() {
        for (purpose in listOf("CATEGORY", "RENAME", "ADD_PHRASE", "PHRASE", "NOTE")) {
            for (confirm in listOf(false, true)) {
                val f = fixture()
                Settings.Global.putFloat(f.service.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                val activity = Robolectric.buildActivity(Activity::class.java).setup()
                try {
                    val host = FrameLayout(activity.get())
                    activity.get().setContentView(host)
                    host.addView(f.view)
                    f.view.measure(
                        View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(582, View.MeasureSpec.AT_MOST),
                    )
                    f.view.layout(0, 0, f.view.measuredWidth, f.view.measuredHeight)
                    val cv = clipboard(f.service)
                    cv.switchTabForTest(false)
                    when (purpose) {
                        "CATEGORY" -> cv.onAddCategory()
                        "RENAME" -> cv.onRenameCategory("默认")
                        "ADD_PHRASE" -> cv.onAddPhrase("默认")
                        "PHRASE" -> cv.onEditPhrase("默认", "原文")
                        else -> cv.onEditNote("默认", "原文")
                    }
                    shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
                    f.view.onKey(Key("6", output = "6"))
                    assertTrue(f.controller.preeditForTest().isNotEmpty())
                    f.service.commitText("final-state-$purpose-$confirm")
                    var overlayChanges = 0
                    f.view.onOverlayChanged = { overlayChanges++ }
                    if (confirm) f.view.onEditConfirm() else f.view.onEditCancel()

                    val state = f.service.transientStateForTest()
                    assertFalse(state.editActive)
                    assertEquals("", state.composition)
                    assertFalse(f.view.isEditBarShowing())
                    assertEquals("CLIPBOARD", state.panel)
                    assertEquals("PHRASES", state.panelDetail)
                    assertFalse(cv.isClipboardTabForTest())
                    assertTrue(f.view.isPanelShowing(cv))
                    assertEquals(1, overlayChanges)
                    shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
                    assertFalse(f.view.isEditBarShowing())
                    assertTrue(f.view.isPanelShowing(cv))
                } finally {
                    activity.pause().stop().destroy()
                }
            }
        }
    }

    @Test fun density_change_during_inline_edit_recreates_the_final_phrase_panel() {
        val f = fixture()
        val oldClipboard = clipboard(f.service)
        oldClipboard.switchTabForTest(false)
        oldClipboard.onEditNote("默认", "原文")
        f.service.commitText("density-draft")
        val frameworkInputFrame = installFrameworkInputFrame(f)

        try {
            RuntimeEnvironment.setQualifiers("w320dp-h200dp-land-mdpi")
            f.service.onConfigurationChanged(
                Configuration(f.service.resources.configuration).apply {
                    densityDpi = 160
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                },
            )
            f.view = cachedPanel(f.service, "inputView") as InputView
            assertTrue(frameworkInputFrame.getChildAt(0) === f.view)
            assertNull(cachedPanel(f.service, "clipboardView"))
            assertTrue(f.service.transientStateForTest().editActive)

            f.view.onEditCancel()

            val rebuiltClipboard = cachedPanel(f.service, "clipboardView") as ClipboardView
            assertFalse(f.view.isEditBarShowing())
            assertNotSame(oldClipboard, rebuiltClipboard)
            assertEquals(1f, capturedDensity(rebuiltClipboard), 0.001f)
            assertFalse(rebuiltClipboard.isClipboardTabForTest())
            assertTrue(f.view.isPanelShowing(rebuiltClipboard))
            assertEquals("PHRASES", f.service.transientStateForTest().panelDetail)
        } finally {
            RuntimeEnvironment.setQualifiers("w853dp-h388dp-land-hdpi")
        }
    }

    @Test fun inline_exit_drops_a_decode_result_already_queued_for_main_delivery() {
        val worker = ArrayDeque<Runnable>()
        val main = ArrayDeque<Runnable>()
        val lane = DecodeLane(Executor { worker.add(it) }, Executor { main.add(it) })
        val f = fixture(decodeLane = lane)
        val cv = clipboard(f.service)
        cv.onEditNote("默认", "原文")
        f.view.onKey(Key("6", output = "6"))
        assertTrue(worker.isNotEmpty())
        while (worker.isNotEmpty()) worker.removeFirst().run()
        assertTrue(main.isNotEmpty())

        f.view.onEditCancel()
        assertEquals("", f.controller.preeditForTest())
        assertTrue(f.view.isPanelShowing(cv))
        while (main.isNotEmpty()) main.removeFirst().run()

        assertEquals("", f.controller.preeditForTest())
        assertTrue(f.controller.candidateWords().isEmpty())
        assertTrue(f.view.isPanelShowing(cv))
    }

    @Test fun phrases_panel_survives_real_rotation_but_category_move_clears_after_real_hide_show() {
        val f = fixture()
        val seeded = seed(f, StateKind.PHRASES_PANEL)
        val cv = requireNotNull(seeded.clipboard)

        rotateThroughRealServiceCallbacks(f, seeded)
        cv.enterCategorySortModeForTest()
        assertTrue(cv.isCategorySortModeForTest())
        hideShowThroughRealServiceCallbacks(f)

        assertCleared(f, seeded, "phrases panel window hide")
        assertFalse(cv.isCategorySortModeForTest())
        assertTrue(f.service.transientStateForTest().inputActive)
    }

    @Test fun window_hidden_preserves_last_copy_and_restores_the_copy_bar() {
        val f = fixture()
        val copied = "copied-lifecycle-content"
        setCachedPanel(f.service, "lastCopy", copied)
        f.view.showCopyBar(copied)
        assertTrue(f.view.copyBarShown)

        hideShowThroughRealServiceCallbacks(f)

        assertEquals(copied, cachedPanel(f.service, "lastCopy"))
        assertTrue(f.view.copyBarShown)
        assertTrue(f.service.transientStateForTest().inputActive)
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

    @Test fun density_change_rebuilds_every_cached_panel_and_restores_phrases_category_semantically() {
        val f = fixture()
        val seeded = seed(f, StateKind.PHRASES_PANEL)
        val oldClipboard = requireNotNull(seeded.clipboard)
        val stalePanels = mapOf(
            "emojiView" to EmojiView(f.service),
            "symbolsView" to SymbolsView(f.service),
            "editPanelView" to EditPanelView(f.service),
            "layoutPanelView" to LayoutPanelView(f.service),
            "customSymbolView" to CustomSymbolPanel(f.service),
            "customOperatorView" to CustomSymbolPanel(f.service),
        )
        stalePanels.forEach { (name, panel) -> setCachedPanel(f.service, name, panel) }
        assertEquals(1.5f, capturedDensity(oldClipboard), 0.001f)
        assertEquals(1.5f, capturedInputDensity(f.view), 0.001f)
        val frameworkInputFrame = installFrameworkInputFrame(f)

        try {
            RuntimeEnvironment.setQualifiers("w320dp-h200dp-land-mdpi")
            val mdpi = Configuration(f.service.resources.configuration).apply {
                densityDpi = 160
                orientation = Configuration.ORIENTATION_LANDSCAPE
            }

            val oldDensityView = f.view
            f.service.onConfigurationChanged(mdpi)
            f.view = cachedPanel(f.service, "inputView") as InputView
            assertNotSame(oldDensityView, f.view)
            assertTrue(frameworkInputFrame.getChildAt(0) === f.view)
            assertEquals(1f, capturedInputDensity(f.view), 0.001f)

            val rebuiltClipboard = cachedPanel(f.service, "clipboardView") as ClipboardView
            assertNotSame(oldClipboard, rebuiltClipboard)
            assertEquals(1f, capturedDensity(rebuiltClipboard), 0.001f)
            assertFalse(rebuiltClipboard.isClipboardTabForTest())
            assertEquals(seeded.phraseCategory, rebuiltClipboard.phraseCatForTest())
            assertTrue(f.view.isPanelShowing(rebuiltClipboard))
            stalePanels.forEach { (name, _) ->
                assertNull("$name must not retain an old-density instance", cachedPanel(f.service, name))
            }

            f.service.onStartInput(editor(), true)
            val oldView = f.view
            f.view = f.service.onCreateInputView() as InputView
            f.service.onStartInputView(editor(), false)
            assertNotSame(oldView, f.view)
            assertTrue(f.view.isPanelShowing(rebuiltClipboard))
            assertFalse(rebuiltClipboard.isClipboardTabForTest())
            assertEquals(seeded.phraseCategory, rebuiltClipboard.phraseCatForTest())
        } finally {
            RuntimeEnvironment.setQualifiers("w853dp-h388dp-land-hdpi")
        }
    }

    @Test fun a_same_editor_restart_keeps_the_layout_panel_open() {
        val f = fixture()
        f.service.javaClass.getDeclaredMethod("showLayoutPanel").apply { isAccessible = true }.invoke(f.service)
        val panel = requireNotNull(cachedPanel(f.service, "layoutPanelView")) as LayoutPanelView
        assertTrue(f.view.isPanelShowing(panel))
        assertEquals("LAYOUT", f.service.transientStateForTest().panel)

        f.service.onStartInput(f.info, true)
        f.service.onStartInputView(f.info, true)

        assertTrue(
            "restore while the layout panel is showing must keep it open",
            f.view.isPanelShowing(panel),
        )
    }

    @Test fun anonymous_editor_restart_preserves_but_new_session_clears_synchronously() {
        fun anonymous() = editor(fieldId = View.NO_ID, fieldName = null)

        val f = fixture(anonymous())
        val seeded = seed(f, StateKind.COMPOSITION)
        val oldView = f.view

        f.service.onStartInput(anonymous(), true)
        f.view = f.service.onCreateInputView() as InputView
        f.service.onStartInputView(anonymous(), false)
        assertNotSame(oldView, f.view)
        assertPreserved(f, seeded)

        f.service.onStartInput(anonymous(), false)
        assertCleared(f, seeded, "anonymous restarting=false")
    }

    @Test fun secure_session_density_change_replaces_retained_root_without_restoring_transient_state() {
        val password = editor(
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        val f = fixture(password)
        val oldDensityView = f.view
        assertEquals(1.5f, capturedInputDensity(oldDensityView), 0.001f)
        val frameworkInputFrame = installFrameworkInputFrame(f)

        try {
            RuntimeEnvironment.setQualifiers("w320dp-h200dp-land-mdpi")
            f.service.onConfigurationChanged(
                Configuration(f.service.resources.configuration).apply {
                    densityDpi = 160
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                },
            )
            f.view = cachedPanel(f.service, "inputView") as InputView
            assertNotSame(oldDensityView, f.view)
            assertTrue(frameworkInputFrame.getChildAt(0) === f.view)
            assertEquals(1f, capturedInputDensity(f.view), 0.001f)
            val state = f.service.transientStateForTest()
            assertTrue(state.secure)
            assertEquals("", state.composition)
            assertFalse(state.editActive)
            assertNull(state.panel)
            assertFalse(f.view.hasOverlay())
        } finally {
            RuntimeEnvironment.setQualifiers("w853dp-h388dp-land-hdpi")
        }
    }

    private fun assertCleared(f: Fixture, seeded: SeededState, boundary: String) {
        val state = f.service.transientStateForTest()
        assertEquals("$boundary: composition", "", state.composition)
        assertFalse("$boundary: edit active", state.editActive)
        assertEquals("$boundary: edit text", "", state.editText)
        assertNull("$boundary: edit purpose", state.editPurpose)
        assertNull("$boundary: panel", state.panel)
        assertNull("$boundary: panel detail", state.panelDetail)
        assertFalse("$boundary: view composition", f.view.isComposing())
        assertFalse("$boundary: edit bar", f.view.isEditBarShowing())
        assertFalse("$boundary: panel view", f.view.panelShown)
        seeded.clipboard?.let {
            assertTrue("$boundary: closed Clipboard panel resets to its safe default tab", it.isClipboardTabForTest())
            assertEquals("$boundary: selected phrase category", "", it.phraseCatForTest())
        }
    }

    @Test fun new_different_secure_unknown_and_finishing_targets_clear_every_state_class() {
        for (kind in StateKind.entries) {
            fixture().also { f ->
                val seeded = seed(f, kind)
                f.service.onStartInput(editor(), false)
                f.service.onStartInputView(editor(), false)
                assertCleared(f, seeded, "$kind same-target restarting=false")
            }
            fixture().also { f ->
                val seeded = seed(f, kind)
                val differentField = editor(fieldId = 202)
                f.service.onStartInput(differentField, true)
                f.service.onStartInputView(differentField, true)
                assertCleared(f, seeded, "$kind different field")
            }
            fixture().also { f ->
                val seeded = seed(f, kind)
                val differentApp = editor(packageName = "com.other.editor")
                f.service.onStartInput(differentApp, true)
                f.service.onStartInputView(differentApp, true)
                assertCleared(f, seeded, "$kind different app")
            }
            fixture().also { f ->
                val seeded = seed(f, kind)
                val password = editor(
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                )
                f.service.onStartInput(password, true)
                f.service.onStartInputView(password, true)
                assertCleared(f, seeded, "$kind secure target")
                assertTrue(f.service.transientStateForTest().secure)
            }
            fixture().also { f ->
                val seeded = seed(f, kind)
                f.service.onStartInput(null, true)
                f.service.onStartInputView(null, true)
                assertCleared(f, seeded, "$kind unknown target")
            }
            fixture().also { f ->
                val seeded = seed(f, kind)
                f.service.onFinishInputView(true)
                f.service.onFinishInput()
                assertCleared(f, seeded, "$kind finishing teardown")
                assertFalse(f.service.transientStateForTest().inputActive)
            }
        }
    }

    @Test fun hard_target_boundary_scrubs_attached_panel_and_edit_bar_synchronously() {
        val f = fixture()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val host = FrameLayout(activity.get())
        activity.get().setContentView(host)
        host.addView(f.view)
        f.view.measure(
            View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(582, View.MeasureSpec.AT_MOST),
        )
        f.view.layout(0, 0, f.view.measuredWidth, f.view.measuredHeight)
        assertTrue("regression must exercise the attached/animated path", f.view.isAttachedToWindow)

        val panelState = seed(f, StateKind.PHRASES_PANEL)
        val cv = requireNotNull(panelState.clipboard)
        assertTrue(f.view.isPanelShowing(cv))
        f.service.onStartInput(editor(packageName = "com.other.editor"), false)
        assertFalse("old panel must be gone in the same callback frame", f.view.panelShown)
        assertNull("old panel must not remain touchable in the new target", cv.parent)
        assertFalse(f.view.hasOverlay())

        f.service.onStartInput(editor(), false)
        f.service.onStartInputView(editor(), false)
        seed(f, StateKind.INLINE_EDIT)
        assertTrue(f.view.isEditBarShowing())
        f.service.onStartInput(
            editor(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            true,
        )
        assertFalse("old edit text must be hidden in the same callback frame", f.view.isEditBarShowing())
        assertFalse(f.view.hasOverlay())
        assertEquals("", f.service.transientStateForTest().editText)

        activity.pause().stop().destroy()
    }
}
