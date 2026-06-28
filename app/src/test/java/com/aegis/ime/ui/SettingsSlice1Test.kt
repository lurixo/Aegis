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
import org.junit.Assert.assertNotEquals
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


    @Test fun dict_surface_is_independent_of_the_gram_surface() {
        assertNotEquals(ModelDownload.DICT_NAME, ModelDownload.GRAM_NAME)
        assertNotEquals(ModelDownload.DICT_VALIDATOR_PREF, ModelDownload.VALIDATOR_PREF)
        assertNotEquals(ModelDownload.DICT_URL, ModelDownload.GRAM_URL)
        assertNotEquals(
            ModelDownload.dictDestFile(ctx.filesDir).absolutePath,
            ModelDownload.destFile(ctx.filesDir).absolutePath,
        )
        assertEquals(ModelDownload.destFile(ctx.filesDir).parentFile, ModelDownload.dictDestFile(ctx.filesDir).parentFile)
    }

    @Test fun isDictDownloaded_tracks_a_real_pack_file_and_purge_removes_it() {
        val dest = ModelDownload.dictDestFile(ctx.filesDir)
        ModelDownload.purgeDict(ctx.filesDir)
        assertFalse("absent → not downloaded", ModelDownload.isDictDownloaded(ctx.filesDir))
        dest.parentFile?.mkdirs()
        dest.writeBytes(ByteArray(2048))
        assertTrue("present (>1KB) → downloaded", ModelDownload.isDictDownloaded(ctx.filesDir))
        assertTrue("purge removed it", ModelDownload.purgeDict(ctx.filesDir))
        assertFalse(ModelDownload.isDictDownloaded(ctx.filesDir))
    }

    @Test fun updateAvailable_semantics_reused_for_the_dict_card() {
        assertTrue("never recorded → offer", ModelDownload.updateAvailable(null, "etag-1"))
        assertFalse("identical → suppress", ModelDownload.updateAvailable("etag-1", "etag-1"))
        assertTrue("differ → offer", ModelDownload.updateAvailable("etag-1", "etag-2"))
        assertTrue("remote unknown → fall back to offer", ModelDownload.updateAvailable("etag-1", null))
    }


    @Test fun associations_pref_defaults_on_and_round_trips() {
        val prefs = ctx.getSharedPreferences("aegis", android.content.Context.MODE_PRIVATE)
        prefs.edit { remove(PREF_ASSOCIATIONS_ON) }
        assertTrue("default ON when unset", prefs.getBoolean(PREF_ASSOCIATIONS_ON, true))
        prefs.edit { putBoolean(PREF_ASSOCIATIONS_ON, false) }
        assertFalse("persists OFF", prefs.getBoolean(PREF_ASSOCIATIONS_ON, true))
        prefs.edit { putBoolean(PREF_ASSOCIATIONS_ON, true) }
        assertTrue("persists ON", prefs.getBoolean(PREF_ASSOCIATIONS_ON, true))
    }
}
