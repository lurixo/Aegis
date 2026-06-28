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
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * debug.13 slice-1 logic guards: the dict-pack download surface added to [ModelDownload] (B2) and the 联想
 * toggle pref (D1). The Compose layout itself (model card above dict card, the toggle Switch) is verified by
 * render/eyeball; this pins the non-UI contracts the cards depend on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSlice1Test {

    private val ctx = RuntimeEnvironment.getApplication()

    // ---- B2: the dict pack is a SEPARATE asset from the .gram model (so the two cards never collide) ----

    @Test fun dict_surface_is_independent_of_the_gram_surface() {
        assertNotEquals(ModelDownload.DICT_NAME, ModelDownload.GRAM_NAME)
        assertNotEquals(ModelDownload.DICT_VALIDATOR_PREF, ModelDownload.VALIDATOR_PREF)
        assertNotEquals(ModelDownload.DICT_URL, ModelDownload.GRAM_URL)
        assertNotEquals(
            ModelDownload.dictZipFile(ctx.filesDir).absolutePath,
            ModelDownload.destFile(ctx.filesDir).absolutePath,
        )
        // both land in the same downloaded/ dir (picked up like the model)
        assertEquals(ModelDownload.destFile(ctx.filesDir).parentFile, ModelDownload.dictZipFile(ctx.filesDir).parentFile)
    }

    // ---- B2 (slice-3): the dict pack is a ZIP → sha256-verify → unzip 3 .bin → rename ----

    /** Build a zip with the 3 pack entries (named to exercise the keyword→target mapping). */
    private fun writePackZip(dest: File, dict: String, t9: String, jianpin: String) {
        dest.parentFile?.mkdirs()
        ZipOutputStream(dest.outputStream()).use { z ->
            for ((name, body) in listOf("aegis_dict.bin" to dict, "aegis_t9.bin" to t9, "aegis_jianpin.bin" to jianpin)) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
            }
        }
    }

    @Test fun extract_dict_pack_writes_the_three_renamed_bins() {
        val dir = File(ctx.filesDir, "downloaded").apply { mkdirs() }
        val zip = File(dir, "pack.zip")
        writePackZip(zip, "DICT".repeat(400), "T9".repeat(700), "JP".repeat(700)) // each > 1KB
        val produced = ModelDownload.extractDictPack(zip, dir)
        assertEquals(ModelDownload.DICT_PACK_FILES.toSet(), produced)
        assertTrue("3 bins present", ModelDownload.isDictDownloaded(ctx.filesDir))
        assertEquals("DICT".repeat(400), File(dir, "aegis_dict.bin").readText())
    }

    @Test fun install_rejects_a_zip_whose_sha256_does_not_match() {
        ModelDownload.purgeDict(ctx.filesDir)
        val zip = ModelDownload.dictZipFile(ctx.filesDir)
        writePackZip(zip, "x".repeat(2048), "y".repeat(2048), "z".repeat(2048)) // valid zip, but WRONG sha256
        assertFalse("sha256 mismatch → install rejected", ModelDownload.installDictPack(ctx.filesDir))
        assertFalse("nothing extracted", ModelDownload.isDictDownloaded(ctx.filesDir))
        assertFalse("rejected zip is removed", zip.exists())
    }

    @Test fun sha256Of_matches_a_known_digest() {
        val f = File(ctx.filesDir, "sha.txt").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ModelDownload.sha256Of(f))
    }

    @Test fun purge_clears_all_three_extracted_bins() {
        val dir = File(ctx.filesDir, "downloaded").apply { mkdirs() }
        ModelDownload.DICT_PACK_FILES.forEach { File(dir, it).writeBytes(ByteArray(2048)) }
        assertTrue("downloaded before purge", ModelDownload.isDictDownloaded(ctx.filesDir))
        assertTrue("purge removed something", ModelDownload.purgeDict(ctx.filesDir))
        assertFalse("all 3 bins gone", ModelDownload.isDictDownloaded(ctx.filesDir))
        ModelDownload.DICT_PACK_FILES.forEach { assertFalse(File(dir, it).exists()) }
    }

    @Test fun updateAvailable_semantics_reused_for_the_dict_card() {
        // B5: same rule as the model — only an exact validator match suppresses 更新.
        assertTrue("never recorded → offer", ModelDownload.updateAvailable(null, "etag-1"))
        assertFalse("identical → suppress", ModelDownload.updateAvailable("etag-1", "etag-1"))
        assertTrue("differ → offer", ModelDownload.updateAvailable("etag-1", "etag-2"))
        assertTrue("remote unknown → fall back to offer", ModelDownload.updateAvailable("etag-1", null))
    }

    // ---- D1: 联想 toggle pref — default ON, persists ----

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
