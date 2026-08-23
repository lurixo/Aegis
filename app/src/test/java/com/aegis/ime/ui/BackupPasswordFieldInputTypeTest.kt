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

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.aegis.ime.R
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupPasswordFieldInputTypeTest {

    @get:Rule
    val compose = createComposeRule()

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun the_backup_password_field_declares_a_password_input_type() {
        var captured: EditorInfo? = null
        compose.setContent {
            InterceptPlatformTextInput(
                interceptor = { request, _ ->
                    val info = EditorInfo()
                    request.createInputConnection(info)
                    captured = info
                    awaitCancellation()
                },
            ) {
                PasswordTextField(value = "", onValueChange = {}, labelRes = R.string.backup_password_label)
            }
        }
        compose.onAllNodes(hasSetTextAction()).onFirst().performClick()
        compose.waitForIdle()
        val type = requireNotNull(captured) { "the field never requested an input session" }.inputType
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, type and InputType.TYPE_MASK_VARIATION)
        assertEquals(InputType.TYPE_CLASS_TEXT, type and InputType.TYPE_MASK_CLASS)
    }
}
