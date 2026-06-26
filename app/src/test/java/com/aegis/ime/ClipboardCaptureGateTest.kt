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
import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.historyText
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardCaptureGateTest {

    private val app = RuntimeEnvironment.getApplication()

    @Before fun start() {
        LiveUserData.clipboardHost = null
        LiveUserData.restoreInProgress = false
        File(app.filesDir, "clipboard.txt").delete()
    }

    @After fun clean() {
        LiveUserData.clipboardHost = null
        LiveUserData.restoreInProgress = false
        File(app.filesDir, "clipboard.txt").delete()
    }

    private fun ordinary() = EditorInfo().apply {
        packageName = "com.example.editor"
        fieldId = 21
        inputType = InputType.TYPE_CLASS_TEXT
    }

    private fun startedIn(info: EditorInfo): AegisInputMethodService {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val engine = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        }
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, KeyboardController(service, engine, null))
        }
        service.onStartInput(info, false)
        service.onCreateInputView() as InputView
        service.onStartInputView(info, false)
        return service
    }

    private fun store(service: AegisInputMethodService): ClipboardStore {
        val delegate = service.javaClass.getDeclaredField("clipboardStore\$delegate").run {
            isAccessible = true
            get(service) as Lazy<*>
        }
        return delegate.value as ClipboardStore
    }

    private fun copyBlocks(service: AegisInputMethodService, blocks: List<String>) {
        service.javaClass
            .getDeclaredMethod("copyBlocksToAegis", List::class.java)
            .apply { isAccessible = true }
            .invoke(service, blocks)
    }

    private fun captureClip(service: AegisInputMethodService) {
        service.javaClass.getDeclaredMethod("captureClip").apply { isAccessible = true }.invoke(service)
    }

    private fun systemClip(text: String) {
        val manager = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("label", text))
    }

    @Test fun split_blocks_copied_in_an_ordinary_field_are_still_kept() {
        val service = startedIn(ordinary())

        copyBlocks(service, listOf("拆出来的一块"))

        assertEquals(listOf("拆出来的一块"), store(service).historyText())
    }

    @Test fun split_blocks_copied_during_a_restore_are_never_kept() {
        val service = startedIn(ordinary())
        LiveUserData.restoreInProgress = true

        copyBlocks(service, listOf("恢复期拆出来的"))

        assertTrue(
            "a restore owns the history file, so nothing may be written beside it",
            store(service).historyText().isEmpty(),
        )
    }

    @Test fun the_clip_on_the_system_board_is_kept_in_an_ordinary_field() {
        val service = startedIn(ordinary())
        systemClip("系统剪贴板上的")

        captureClip(service)

        assertEquals(listOf("系统剪贴板上的"), store(service).historyText())
    }

    @Test fun a_copy_keeps_its_whitespace_exactly_as_copied() {
        val service = startedIn(ordinary())
        systemClip(" \u524d\u540e\u6709\u7a7a\u767d\t\n")

        service.javaClass.getDeclaredMethod("onSystemClipChanged").apply { isAccessible = true }.invoke(service)

        assertEquals(listOf(" \u524d\u540e\u6709\u7a7a\u767d\t\n"), store(service).historyText())
    }

    @Test fun a_copy_made_while_an_ordinary_field_is_open_is_still_kept() {
        val service = startedIn(ordinary())
        systemClip("在普通框里复制的")

        service.javaClass.getDeclaredMethod("onSystemClipChanged").apply { isAccessible = true }.invoke(service)

        assertEquals(listOf("在普通框里复制的"), store(service).historyText())
    }
}
