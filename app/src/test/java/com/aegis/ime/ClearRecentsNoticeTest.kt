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
import com.aegis.ime.ime.EmojiView
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import android.os.Looper
import com.aegis.ime.ime.SymbolsView
import com.aegis.ime.user.SymbolUsageStore
import org.junit.After
import org.junit.Assert.assertEquals
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
class ClearRecentsNoticeTest {

    private val app = RuntimeEnvironment.getApplication()
    private val symbolFile = File(app.filesDir, "symbol_usage.txt")
    private val emojiFile = File(File(app.filesDir, "emoji"), "symbol_usage.txt")

    @Before fun start() {
        ShadowToast.reset()
        readAgain()
        symbolFile.delete()
        emojiFile.delete()
    }

    @After fun clean() {
        readAgain()
        symbolFile.delete()
        emojiFile.delete()
        ShadowToast.reset()
    }

    private fun readAgain() {
        symbolFile.setReadable(true, false)
        emojiFile.setReadable(true, false)
    }

    private fun sealed(file: File) {
        file.parentFile?.mkdirs()
        file.writeText("★\t符号\n")
        assertTrue("precondition: ${file.name} cannot be read back", file.setReadable(false, false))
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
            fieldId = 9
            inputType = InputType.TYPE_CLASS_TEXT
        }
        service.onStartInput(info, false)
        service.onCreateInputView() as InputView
        service.onStartInputView(info, false)
        return service
    }

    private fun open(service: AegisInputMethodService, method: String) {
        service.javaClass.getDeclaredMethod(method).apply { isAccessible = true }.invoke(service)
    }

    private fun panel(service: AegisInputMethodService, field: String): Any =
        service.javaClass.getDeclaredField(field).run {
            isAccessible = true
            get(service)!!
        }

    private fun store(service: AegisInputMethodService, getter: String): SymbolUsageStore =
        service.javaClass.getDeclaredMethod(getter).run {
            isAccessible = true
            invoke(service) as SymbolUsageStore
        }

    private fun notice() = app.getString(R.string.svc_recents_not_cleared)

    private fun settle() {
        SymbolUsageStore.flushPendingWrites()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun a_symbol_clear_that_could_not_be_done_says_so() {
        sealed(symbolFile)
        val service = started()

        open(service, "showSymbolsPanel")
        (panel(service, "symbolsView") as SymbolsView).onClearRecents()
        settle()

        assertEquals(
            "a clear that never happened must not look to the user like one that did",
            notice(),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test fun a_symbol_clear_that_was_done_says_nothing() {
        val service = started()

        open(service, "showSymbolsPanel")
        val panel = panel(service, "symbolsView") as SymbolsView
        panel.onSymbol("€", null)
        panel.onClearRecents()
        settle()

        assertNull("a clear that happened must not be reported as a failure", ShadowToast.getTextOfLatestToast())
    }

    @Test fun a_symbol_clear_that_could_not_be_written_leaves_the_recents_on_the_panel() {
        symbolFile.writeText("★\t符号\n")
        val service = started()

        open(service, "showSymbolsPanel")
        val panel = panel(service, "symbolsView") as SymbolsView
        assertEquals("precondition: the panel is showing the recent symbol", listOf("★"), panel.recentProvider())
        assertTrue(
            "precondition: the clear cannot reach the disk",
            store(service, "getSymbolUsageStore").tempFile().mkdir(),
        )

        panel.onClearRecents()
        settle()

        assertEquals(
            "a clear that never happened must not look to the user like one that did",
            notice(),
            ShadowToast.getTextOfLatestToast(),
        )
        assertEquals(
            "what the notice says is still there must still be on the panel",
            listOf("★"),
            panel.recentProvider(),
        )
        assertEquals("and it must still be on the disk", "★\t符号\n", symbolFile.readText())
    }

    @Test fun an_emoji_clear_that_could_not_be_done_says_so() {
        sealed(emojiFile)
        val service = started()

        open(service, "showEmojiPanel")
        (panel(service, "emojiView") as EmojiView).onClearRecents()
        settle()

        assertEquals(
            "a clear that never happened must not look to the user like one that did",
            notice(),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test fun an_emoji_clear_that_was_done_says_nothing() {
        val service = started()

        open(service, "showEmojiPanel")
        val panel = panel(service, "emojiView") as EmojiView
        panel.onEmoji("😀")
        panel.onClearRecents()
        settle()

        assertNull("a clear that happened must not be reported as a failure", ShadowToast.getTextOfLatestToast())
    }
}
