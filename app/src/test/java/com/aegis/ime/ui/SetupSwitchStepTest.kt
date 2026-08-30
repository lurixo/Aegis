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

package com.aegis.ime.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aegis.ime.R
import com.aegis.ime.ui.theme.AegisTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SetupSwitchStepTest {
    @get:Rule val compose = createComposeRule()

    private val ctx = RuntimeEnvironment.getApplication()
    private fun text(id: Int): String = ctx.getString(id)

    @Test fun the_switch_step_starts_a_silent_input_session_and_then_opens_the_picker() {
        val events = ArrayList<String>()
        val started = ArrayList<View>()
        compose.setContent {
            AegisTheme {
                AboutPage(
                    resumeSignal = 0,
                    onBack = {},
                    onOpenLicenses = {},
                    startSilentInput = { events.add("start"); started.add(it) },
                    showInputMethodPicker = { events.add("picker") },
                )
            }
        }

        compose.onNodeWithText(text(R.string.setup_switch_button)).performScrollTo().performClick()
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()

        assertEquals(listOf("start", "picker"), events)
        val editor = started.single()
        assertTrue("the session starts on an editor", editor is EditText)
        assertTrue("the editor holds focus for the session", editor.isFocused)
        assertFalse("the editor never asks for the keyboard", (editor as EditText).showSoftInputOnFocus)
        assertEquals("the editor stays invisible", 0f, editor.alpha, 0f)
        compose.onNodeWithText(text(R.string.setup_try_field_label)).assertIsNotFocused()
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        assertFalse("no keyboard is raised for the switch step", shadowOf(imm).isSoftInputVisible)
    }

    private fun settle() {
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun cycleWindowFocus(view: View) {
        val dispatch = ViewTreeObserver::class.java.getDeclaredMethod("dispatchOnWindowFocusChange", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        dispatch.invoke(view.viewTreeObserver, false)
        settle()
        dispatch.invoke(view.viewTreeObserver, true)
        settle()
    }

    @Test fun closing_the_picker_hands_focus_back_to_the_try_field_that_summoned_the_keyboard() {
        val started = ArrayList<View>()
        compose.setContent {
            AegisTheme {
                AboutPage(resumeSignal = 0, onBack = {}, onOpenLicenses = {}, startSilentInput = { started.add(it) }, showInputMethodPicker = {})
            }
        }
        compose.onNodeWithText(text(R.string.setup_try_field_label)).performScrollTo().performClick()
        settle()
        compose.onNodeWithText(text(R.string.setup_try_field_label)).assertIsFocused()

        compose.onNodeWithText(text(R.string.setup_switch_button)).performScrollTo().performClick()
        settle()
        val editor = started.single()
        assertTrue(editor.isFocused)
        compose.onNodeWithText(text(R.string.setup_try_field_label)).assertIsNotFocused()

        cycleWindowFocus(editor)
        assertFalse("the invisible editor must not keep the input session", editor.isFocused)
        compose.onNodeWithText(text(R.string.setup_try_field_label)).assertIsFocused()
    }

    @Test fun closing_the_picker_releases_the_invisible_editor_when_nothing_was_focused() {
        val started = ArrayList<View>()
        compose.setContent {
            AegisTheme {
                AboutPage(resumeSignal = 0, onBack = {}, onOpenLicenses = {}, startSilentInput = { started.add(it) }, showInputMethodPicker = {})
            }
        }
        compose.onNodeWithText(text(R.string.setup_switch_button)).performScrollTo().performClick()
        settle()
        val editor = started.single()
        assertTrue(editor.isFocused)

        cycleWindowFocus(editor)
        assertFalse("the invisible editor must not keep the input session", editor.isFocused)
        compose.onNodeWithText(text(R.string.setup_try_field_label)).assertIsNotFocused()
    }

    @Test fun the_switch_step_opens_the_picker_once_per_tap() {
        var picker = 0
        compose.setContent {
            AegisTheme {
                AboutPage(resumeSignal = 0, onBack = {}, onOpenLicenses = {}, startSilentInput = {}, showInputMethodPicker = { picker += 1 })
            }
        }
        repeat(2) {
            compose.onNodeWithText(text(R.string.setup_switch_button)).performScrollTo().performClick()
            compose.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
        assertEquals(2, picker)
    }
}
