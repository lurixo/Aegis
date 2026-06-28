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

import android.view.inputmethod.EditorInfo
import com.aegis.ime.ime.InputView
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.KeyboardController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeLocaleTest {

    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        override fun englishCompletions(typed: String): List<String> = emptyList()
    }

    private fun service(tags: String?): AegisInputMethodService {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        service.appLocaleTags = { tags }
        val controller = KeyboardController(service, engine)
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, controller)
        }
        service.onStartInput(EditorInfo(), false)
        return service
    }

    @Test fun the_keyboard_reads_its_strings_through_the_app_locale() {
        val service = service("zh-CN")
        val view = service.onCreateInputView() as InputView
        assertEquals("空格", view.context.getString(R.string.kbd_space))
    }

    @Test fun a_locale_change_rebuilds_the_keyboard_on_the_next_show() {
        val service = service("zh-CN")
        val first = service.onCreateInputView() as InputView
        service.onStartInputView(EditorInfo(), false)
        service.appLocaleTags = { null }
        service.onStartInputView(EditorInfo(), false)
        val field = service.javaClass.getDeclaredField("inputView").apply { isAccessible = true }
        val current = field.get(service) as InputView
        assertNotSame(first, current)
        assertEquals("Space", current.context.getString(R.string.kbd_space))
    }
}
