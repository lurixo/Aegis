package com.aegis.ime

import android.app.Activity
import android.content.res.Configuration
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.ClipboardView
import com.aegis.ime.ime.CustomSymbolPanel
import com.aegis.ime.ime.EditPanelView
import com.aegis.ime.ime.EmojiView
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.SymbolsView
import com.aegis.ime.layout.Key
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
import org.robolectric.annotation.Config

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

    private fun fixture(info: EditorInfo = editor()): Fixture {

        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val controller = KeyboardController(service, engine)
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

    private fun clipboard(service: AegisInputMethodService): ClipboardView {
        service.javaClass.getDeclaredMethod("showClipboardPanel").apply { isAccessible = true }.invoke(service)
        return service.javaClass.getDeclaredField("clipboardView").run {
            isAccessible = true
            get(service) as ClipboardView
        }
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

    private fun hideShowThroughRealServiceCallbacks(f: Fixture, seeded: SeededState) {
        val sameView = f.view
        f.service.onFinishInputView(false)

        f.service.onStartInputView(editor(), false)
        assertTrue("temporary hide/show keeps the framework view", sameView === f.view)
        assertPreserved(f, seeded)
    }

    @Test fun composition_survives_same_editor_restart_real_rotation_and_real_hide_show() {
        val f = fixture()
        val seeded = seed(f, StateKind.COMPOSITION)

        f.service.onStartInput(editor(), true)
        f.service.onStartInputView(editor(), true)
        assertPreserved(f, seeded)
        rotateThroughRealServiceCallbacks(f, seeded)
        hideShowThroughRealServiceCallbacks(f, seeded)

        f.view.onKey(Key("6", output = "6"))
        assertTrue("restored controller remains live", f.service.transientStateForTest().composition.isNotEmpty())
    }

    @Test fun inline_edit_buffer_and_purpose_survive_real_rotation_and_real_hide_show() {
        val f = fixture()
        val seeded = seed(f, StateKind.INLINE_EDIT)

        rotateThroughRealServiceCallbacks(f, seeded)
        hideShowThroughRealServiceCallbacks(f, seeded)

        f.service.commitText("-after")
        assertEquals("lifecycle-draft-after", f.service.transientStateForTest().editText)
    }

    @Test fun phrases_panel_tab_and_category_survive_real_rotation_and_real_hide_show() {
        val f = fixture()
        val seeded = seed(f, StateKind.PHRASES_PANEL)

        rotateThroughRealServiceCallbacks(f, seeded)
        hideShowThroughRealServiceCallbacks(f, seeded)
    }

    @Test fun density_change_rebuilds_every_cached_panel_and_restores_phrases_category_semantically() {
        val f = fixture()
        val seeded = seed(f, StateKind.PHRASES_PANEL)
        val oldClipboard = requireNotNull(seeded.clipboard)
        val stalePanels = mapOf(
            "emojiView" to EmojiView(f.service),
            "symbolsView" to SymbolsView(f.service),
            "editPanelView" to EditPanelView(f.service),
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
