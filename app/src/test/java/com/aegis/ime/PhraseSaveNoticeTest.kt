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
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.ClipboardView
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.LiveUserData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhraseSaveNoticeTest {

    private val app = RuntimeEnvironment.getApplication()
    private val phraseFile = File(app.filesDir, "phrases.txt")

    @Before fun start() {
        ShadowToast.reset()
        LiveUserData.clipboardHost = null
        phraseFile.setReadable(true, false)
        phraseFile.delete()
    }

    @After fun clean() {
        LiveUserData.clipboardHost = null
        phraseFile.setReadable(true, false)
        phraseFile.delete()
        ShadowToast.reset()
    }

    private fun sealPhrases() {
        phraseFile.parentFile?.mkdirs()
        phraseFile.writeText("C\t${ClipboardStore.DEFAULT_CATEGORY_ID}\n")
        assertTrue("precondition: phrases.txt cannot be read back", phraseFile.setReadable(false, false))
    }

    private fun started(): AegisInputMethodService {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val engine = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        }
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, KeyboardController(service, engine, null))
        }
        val info = EditorInfo().apply {
            packageName = "com.example.editor"
            fieldId = 11
            inputType = InputType.TYPE_CLASS_TEXT
        }
        service.onStartInput(info, false)
        service.onCreateInputView() as InputView
        service.onStartInputView(info, false)
        return service
    }

    private fun clipboard(service: AegisInputMethodService): ClipboardView {
        service.javaClass.getDeclaredMethod("showClipboardPanel").apply { isAccessible = true }.invoke(service)
        return service.javaClass.getDeclaredField("clipboardView").run {
            isAccessible = true
            get(service) as ClipboardView
        }
    }

    private fun inputView(service: AegisInputMethodService): InputView =
        service.javaClass.getDeclaredField("inputView").run {
            isAccessible = true
            get(service) as InputView
        }

    private fun store(service: AegisInputMethodService): ClipboardStore {
        val delegate = service.javaClass.getDeclaredField("clipboardStore\$delegate").run {
            isAccessible = true
            get(service) as Lazy<*>
        }
        return delegate.value as ClipboardStore
    }

    private fun settle(service: AegisInputMethodService) {
        store(service).flushPendingWrites()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun addInline(service: AegisInputMethodService, text: String) {
        service.javaClass.getDeclaredMethod("beginInlineAddPhrase", String::class.java)
            .apply { isAccessible = true }
            .invoke(service, ClipboardStore.DEFAULT_CATEGORY_ID)
        service.javaClass.getDeclaredMethod("commitExternalText", CharSequence::class.java)
            .apply { isAccessible = true }
            .invoke(service, text)
        service.javaClass.getDeclaredMethod("confirmInlineInput")
            .apply { isAccessible = true }
            .invoke(service)
    }

    private fun allViews(root: View): List<View> =
        if (root is ViewGroup) listOf(root) + (0 until root.childCount).flatMap { allViews(root.getChildAt(it)) }
        else listOf(root)

    private fun labels(root: View): List<String> =
        allViews(root).filterIsInstance<TextView>().mapNotNull { it.text?.toString() }

    private fun label(id: Int, vararg args: Any) = app.getString(id, *args)

    @Test fun a_phrase_the_panel_could_not_save_is_not_reported_as_one_that_already_exists() {
        sealPhrases()
        val service = started()
        val panel = clipboard(service)

        panel.onSaveAsPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("要存的常用语"))
        settle(service)

        assertTrue(
            "a phrase that was never written must not look to the user like one that was already there",
            label(R.string.clip_phrases_not_saved, 1) in labels(panel),
        )
        assertFalse(label(R.string.clip_phrases_exist, 1) in labels(panel))
    }

    @Test fun a_phrase_the_panel_really_did_save_is_reported_only_once_the_write_landed() {
        val service = started()
        val panel = clipboard(service)

        panel.onSaveAsPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("要存的常用语"))

        assertNull(
            "the panel may not promise the file took it before the writer got there",
            ShadowToast.getTextOfLatestToast(),
        )

        settle(service)

        assertEquals(label(R.string.clip_phrases_saved, 1), ShadowToast.getTextOfLatestToast())
    }

    @Test fun a_phrase_the_panel_already_holds_is_still_reported_as_one_that_exists() {
        val service = started()
        val panel = clipboard(service)

        panel.onSaveAsPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("要存的常用语"))
        settle(service)
        panel.onSaveAsPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("要存的常用语"))
        settle(service)

        assertEquals(label(R.string.clip_phrases_exist, 1), ShadowToast.getTextOfLatestToast())
    }

    @Test fun a_batch_the_panel_only_partly_saved_says_how_much_of_it_was_saved() {
        val service = started()
        val panel = clipboard(service)

        panel.onSaveAsPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("已经存过的"))
        settle(service)
        panel.onSaveAsPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("已经存过的", "新来的"))
        settle(service)

        assertEquals(
            "the clip that was already in the category is not one the write left out",
            label(R.string.clip_phrases_saved_existing, 1, 1),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test fun a_typed_phrase_that_could_not_be_saved_is_not_reported_as_one_that_already_exists() {
        sealPhrases()
        val service = started()
        val panel = clipboard(service)

        addInline(service, "手打的常用语")
        settle(service)

        assertTrue(
            "a phrase that was never written must not look to the user like one that was already there",
            label(R.string.clip_phrases_not_saved, 1) in labels(panel),
        )
    }

    @Test fun a_typed_phrase_that_only_reached_memory_is_not_reported_as_added() {
        val service = started()
        val panel = clipboard(service)
        val blocker = store(service).tempFileFor(phraseFile)
        assertTrue("precondition: the write cannot reach the disk", blocker.mkdirs())
        try {
            addInline(service, "写不进去的常用语")
            settle(service)

            assertTrue(
                "a phrase that only reached the list must not be reported as one that was saved",
                label(R.string.clip_phrases_not_saved, 1) in labels(panel),
            )
            assertNull(ShadowToast.getTextOfLatestToast())
        } finally {
            blocker.deleteRecursively()
        }
    }

    @Test fun a_typed_phrase_that_really_was_saved_is_still_reported_as_added() {
        val service = started()
        clipboard(service)

        addInline(service, "手打的常用语")
        settle(service)

        assertEquals(label(R.string.clip_phrases_saved, 1), ShadowToast.getTextOfLatestToast())
    }

    @Test fun a_typed_phrase_that_is_already_there_is_still_reported_as_one_that_exists() {
        val service = started()
        clipboard(service)

        addInline(service, "手打的常用语")
        settle(service)
        addInline(service, "手打的常用语")
        settle(service)

        assertEquals(label(R.string.clip_phrases_exist, 1), ShadowToast.getTextOfLatestToast())
    }

    @Test fun a_write_that_came_back_after_the_panel_closed_is_carried_by_the_candidate_bar() {
        val service = started()
        val panel = clipboard(service)
        val view = inputView(service)
        val blocker = store(service).tempFileFor(phraseFile)
        assertTrue("precondition: the write cannot reach the disk", blocker.mkdirs())
        try {
            panel.onSaveAsPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("面板关了才知道的"))
            view.showPanel(null)
            assertFalse("precondition: the panel is gone before the write answers", view.isPanelShowing(panel))

            settle(service)

            assertEquals(
                "a failure the panel is no longer there to show must still reach the user",
                label(R.string.clip_phrases_not_saved, 1),
                view.candidateRestoreNoticeForTest(),
            )
        } finally {
            blocker.deleteRecursively()
        }
    }

    @Test fun opening_the_clipboard_panel_again_takes_the_bar_notice_down() {
        val service = started()
        val view = inputView(service)
        view.showPhraseNotice(label(R.string.clip_phrases_not_saved, 1))

        clipboard(service)

        assertNull(view.candidateRestoreNoticeForTest())
    }
}
