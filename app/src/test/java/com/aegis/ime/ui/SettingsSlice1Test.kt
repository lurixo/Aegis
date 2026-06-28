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

import androidx.core.content.edit
import com.aegis.ime.dict.ModelDownload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSlice1Test {

    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun resource_update_sources_are_not_app_update_wiring() {
        assertEquals(
            "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/${ModelDownload.GRAM_NAME}",
            ModelDownload.GRAM_URL,
        )
        assertEquals("https://github.com/amzxyz/RIME-LMDG", ModelDownload.REPO_URL)
        assertEquals("dict-latest", ModelDownload.DICT_LATEST_TAG)
        assertEquals(
            "https://github.com/lurixo/Aegis/releases/download/dict-latest/aegis-dictionary-update.json",
            ModelDownload.DICT_UPDATE_URL,
        )
        assertEquals("https://github.com/amzxyz/rime-wanxiang", ModelDownload.DICT_REPO_URL)
        assertFalse("model updates must not point at the Aegis app repo", ModelDownload.REPO_URL.contains("lurixo/Aegis"))
        assertFalse("dictionary source link must not point at the Aegis app repo", ModelDownload.DICT_REPO_URL.contains("lurixo/Aegis"))
        assertFalse("resource downloads must not be APK self-update assets", ModelDownload.GRAM_URL.endsWith(".apk"))
        assertFalse("resource discovery must not be an APK endpoint", ModelDownload.DICT_UPDATE_URL.endsWith(".apk"))
    }


    @Test fun independent_association_preferences_keep_legacy_defaults_until_explicitly_changed() {
        val prefs = ctx.getSharedPreferences("aegis", android.content.Context.MODE_PRIVATE)
        prefs.edit { clear() }
        val readers = listOf(
            PREF_CN_ASSOCIATIONS_ON to com.aegis.ime.SettingsHotApply.Companion::cnAssociationsOn,
            PREF_EN_ASSOCIATIONS_ON to com.aegis.ime.SettingsHotApply.Companion::enAssociationsOn,
            PREF_EMAIL_ASSOCIATIONS_ON to com.aegis.ime.SettingsHotApply.Companion::emailAssociationsOn,
        )
        for ((_, read) in readers) assertFalse("fresh installations start off", read(prefs))
        prefs.edit { putBoolean(PREF_ASSOCIATIONS_ON, true) }
        for ((_, read) in readers) assertTrue("old ON remains effective when unset", read(prefs))
        for ((key, read) in readers) {
            prefs.edit { putBoolean(key, false) }
            assertFalse("explicit OFF wins over legacy ON", read(prefs))
        }
        assertTrue("per-channel choices preserve the legacy preference", prefs.getBoolean(PREF_ASSOCIATIONS_ON, false))
    }
}
