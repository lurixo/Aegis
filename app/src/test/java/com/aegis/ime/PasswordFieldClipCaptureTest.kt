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

import android.content.ClipData
import android.content.ClipboardManager
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.historyText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PasswordFieldClipCaptureTest {

    @Before fun clean() {
        LiveUserData.clipboardHost = null
        LiveUserData.restoreInProgress = false
    }

    @After fun letGo() {
        LiveUserData.clipboardHost = null
    }

    private fun field(inputType: Int) = EditorInfo().apply {
        packageName = "com.example.editor"
        fieldId = 7
        this.inputType = inputType
    }

    private fun password() = field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

    private fun ordinary() = field(InputType.TYPE_CLASS_TEXT)

    private fun service(): AegisInputMethodService {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val engine = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        }
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, KeyboardController(service, engine, null))
        }
        return service
    }

    private fun store(service: AegisInputMethodService): ClipboardStore {
        val delegate = service.javaClass.getDeclaredField("clipboardStore\$delegate").run {
            isAccessible = true
            get(service) as Lazy<*>
        }
        return (delegate.value as ClipboardStore).also { it.clearHistory() }
    }

    private fun copyOutside(service: AegisInputMethodService, text: String) {
        service.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("outside", text))
    }

    private fun systemClipChanged(service: AegisInputMethodService) {
        service.javaClass.getDeclaredMethod("onSystemClipChanged").apply { isAccessible = true }.invoke(service)
    }

    private fun captureClip(service: AegisInputMethodService) {
        service.javaClass.getDeclaredMethod("captureClip").apply { isAccessible = true }.invoke(service)
    }

    @Test fun a_clip_copied_in_a_password_field_never_reaches_the_history() {
        val service = service()
        val store = store(service)
        service.onStartInput(password(), false)

        copyOutside(service, "hunter2-from-the-password-box")
        systemClipChanged(service)

        assertTrue(
            "what the user copies inside a password field must not be kept",
            store.historyText().isEmpty(),
        )
    }

    @Test fun opening_the_clipboard_panel_in_a_password_field_does_not_swallow_the_password() {
        val service = service()
        val store = store(service)
        service.onStartInput(password(), false)
        copyOutside(service, "hunter2-already-on-the-system-clip")

        captureClip(service)

        assertTrue(
            "the panel picks up whatever the system clipboard holds, so it must respect the same gate",
            store.historyText().isEmpty(),
        )
    }

    @Test fun an_ordinary_field_still_keeps_what_the_user_copies() {
        val service = service()
        val store = store(service)
        service.onStartInput(ordinary(), false)

        copyOutside(service, "ordinary text worth keeping")
        systemClipChanged(service)

        assertEquals(listOf("ordinary text worth keeping"), store.historyText())
    }

    @Test fun an_ordinary_field_still_picks_up_the_clip_when_the_panel_opens() {
        val service = service()
        val store = store(service)
        service.onStartInput(ordinary(), false)
        copyOutside(service, "already copied before the panel opened")

        captureClip(service)

        assertEquals(listOf("already copied before the panel opened"), store.historyText())
    }

    @Test fun leaving_the_password_field_lets_the_history_work_again() {
        val service = service()
        val store = store(service)
        service.onStartInput(password(), false)
        copyOutside(service, "hunter2-while-secure")
        systemClipChanged(service)
        assertTrue("precondition: the secure field kept it out", store.historyText().isEmpty())

        service.onStartInput(ordinary(), false)
        copyOutside(service, "back in an ordinary field")
        systemClipChanged(service)

        assertEquals(
            "the gate follows the field the user is in, it is not a one-way switch",
            listOf("back in an ordinary field"),
            store.historyText(),
        )
    }

    @Test fun a_password_field_does_not_stage_the_copy_bar_either() {
        val service = service()
        store(service)
        service.onStartInput(password(), false)

        copyOutside(service, "hunter2-must-not-be-offered-back")
        systemClipChanged(service)

        val lastCopy = service.javaClass.getDeclaredField("lastCopy").run {
            isAccessible = true
            get(service)
        }
        assertFalse(
            "a password the keyboard never recorded must not be sitting in the paste affordance either",
            "hunter2-must-not-be-offered-back" == lastCopy,
        )
    }
}
