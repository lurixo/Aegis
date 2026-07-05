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
import android.content.SharedPreferences
import android.os.Looper
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aegis.ime.R
import com.aegis.ime.SettingsHotApply
import com.aegis.ime.ui.theme.AegisTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LetterCaseCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val ctx = RuntimeEnvironment.getApplication()
    private val prefs: SharedPreferences = ctx.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    private val applied = ArrayList<LetterCase>()
    private val hotApply = SettingsHotApply(
        onCnLayout = {},
        onDefaultLang = {},
        onCnAssociations = {},
        onAutoLearn = {},
        onFuzzyRules = {},
        onEngineAssetsChanged = {},
        onKeyHaptics = {},
        onKeyPreviewNine = {},
        onKeyPreviewAlpha = {},
        onLetterCase = { applied += it },
    )

    @Before fun register() {
        prefs.edit().clear().commit()
        shadowOf(Looper.getMainLooper()).idle()
        prefs.registerOnSharedPreferenceChangeListener(hotApply)
    }

    @After fun unregister() {
        prefs.unregisterOnSharedPreferenceChangeListener(hotApply)
        prefs.edit().clear().commit()
    }

    private fun label(id: Int) = compose.onNodeWithText(ctx.getString(id))

    private fun assertOnlySelected(id: Int) {
        for (choice in listOf(R.string.letter_case_auto, R.string.letter_case_upper, R.string.letter_case_lower)) {
            if (choice == id) label(choice).assertIsSelected() else label(choice).assertIsNotSelected()
        }
    }

    private fun choose(id: Int) {
        label(id).performClick()
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun an_unset_letter_case_shows_auto_and_each_choice_is_stored_shown_and_hot_applied() {
        compose.setContent { AegisTheme { LetterCaseCard() } }

        label(R.string.letter_case_title).assertExists()
        assertOnlySelected(R.string.letter_case_auto)

        choose(R.string.letter_case_upper)
        assertOnlySelected(R.string.letter_case_upper)
        assertEquals("upper", prefs.getString(PREF_LETTER_CASE, null))
        assertEquals(listOf(LetterCase.UPPER), applied)

        choose(R.string.letter_case_lower)
        assertOnlySelected(R.string.letter_case_lower)
        assertEquals("lower", prefs.getString(PREF_LETTER_CASE, null))
        assertEquals(listOf(LetterCase.UPPER, LetterCase.LOWER), applied)

        choose(R.string.letter_case_auto)
        assertOnlySelected(R.string.letter_case_auto)
        assertEquals("auto", prefs.getString(PREF_LETTER_CASE, null))
        assertEquals(listOf(LetterCase.UPPER, LetterCase.LOWER, LetterCase.AUTO), applied)
    }

    @Test fun a_stored_letter_case_is_shown_as_the_selected_choice() {
        prefs.edit().putString(PREF_LETTER_CASE, "lower").commit()
        compose.setContent { AegisTheme { LetterCaseCard() } }

        assertOnlySelected(R.string.letter_case_lower)
    }
}
