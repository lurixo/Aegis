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
import com.aegis.ime.layout.Lang
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
class DefaultLangCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val ctx = RuntimeEnvironment.getApplication()
    private val prefs: SharedPreferences = ctx.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    private val applied = ArrayList<Lang>()
    private val hotApply = SettingsHotApply(
        onCnLayout = {},
        onDefaultLang = { applied += it },
        onCnAssociations = {},
        onAutoLearn = {},
        onFuzzyRules = {},
        onEngineAssetsChanged = {},
        onKeyHaptics = {},
        onKeyPreviewNine = {},
        onKeyPreviewAlpha = {},
        onLetterCase = {},
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
        for (choice in listOf(R.string.default_lang_cn, R.string.default_lang_en)) {
            if (choice == id) label(choice).assertIsSelected() else label(choice).assertIsNotSelected()
        }
    }

    private fun choose(id: Int) {
        label(id).performClick()
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun an_unset_language_shows_chinese_and_each_choice_is_stored_shown_and_hot_applied() {
        compose.setContent { AegisTheme { DefaultLangCard() } }

        label(R.string.default_lang_title).assertExists()
        assertOnlySelected(R.string.default_lang_cn)

        choose(R.string.default_lang_en)
        assertOnlySelected(R.string.default_lang_en)
        assertEquals("en", prefs.getString(PREF_DEFAULT_LANG, null))
        assertEquals(listOf(Lang.EN), applied)

        choose(R.string.default_lang_cn)
        assertOnlySelected(R.string.default_lang_cn)
        assertEquals("cn", prefs.getString(PREF_DEFAULT_LANG, null))
        assertEquals(listOf(Lang.EN, Lang.CN), applied)
    }

    @Test fun a_stored_language_is_shown_as_the_selected_choice() {
        prefs.edit().putString(PREF_DEFAULT_LANG, "en").commit()
        compose.setContent { AegisTheme { DefaultLangCard() } }

        assertOnlySelected(R.string.default_lang_en)
    }
}
