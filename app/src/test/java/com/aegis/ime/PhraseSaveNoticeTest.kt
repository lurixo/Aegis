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
import android.view.inputmethod.EditorInfo
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.ClipboardView
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.LiveUserData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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

    private fun addInline(service: AegisInputMethodService, text: String) {
        service.javaClass
            .getDeclaredMethod("addSinglePhraseWithToast", String::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(service, ClipboardStore.DEFAULT_CATEGORY_ID, text)
    }

    private fun label(id: Int) = app.getString(id)

    @Test fun a_phrase_the_panel_could_not_save_is_not_reported_as_one_that_already_exists() {
        sealPhrases()
        val service = started()

        clipboard(service).onSaveAsPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("要存的常用语"))

        assertEquals(
            "a phrase that was never written must not look to the user like one that was already there",
            label(R.string.svc_phrase_not_added),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test fun a_phrase_the_panel_really_did_save_is_still_reported_as_added() {
        val service = started()

        clipboard(service).onSaveAsPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("要存的常用语"))

        assertEquals(label(R.string.svc_phrase_added), ShadowToast.getTextOfLatestToast())
    }

    @Test fun a_phrase_the_panel_already_holds_is_still_reported_as_one_that_exists() {
        val service = started()
        val panel = clipboard(service)

        panel.onSaveAsPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("要存的常用语"))
        panel.onSaveAsPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("要存的常用语"))

        assertEquals(label(R.string.svc_phrase_exists), ShadowToast.getTextOfLatestToast())
    }

    @Test fun a_typed_phrase_that_could_not_be_saved_is_not_reported_as_one_that_already_exists() {
        sealPhrases()
        val service = started()

        addInline(service, "手打的常用语")

        assertEquals(
            "a phrase that was never written must not look to the user like one that was already there",
            label(R.string.svc_phrase_not_added),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test fun a_typed_phrase_that_really_was_saved_is_still_reported_as_added() {
        val service = started()

        addInline(service, "手打的常用语")

        assertEquals(label(R.string.svc_phrase_added), ShadowToast.getTextOfLatestToast())
    }

    @Test fun a_typed_phrase_that_is_already_there_is_still_reported_as_one_that_exists() {
        val service = started()

        addInline(service, "手打的常用语")
        addInline(service, "手打的常用语")

        assertEquals(label(R.string.svc_phrase_exists), ShadowToast.getTextOfLatestToast())
    }
}
