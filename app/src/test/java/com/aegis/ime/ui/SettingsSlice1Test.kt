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
import com.aegis.ime.R
import com.aegis.ime.dict.EngineAssets
import com.aegis.ime.dict.ModelDownload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
  * Chinese IME behavior note.
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
        assertNotEquals(ModelDownload.FALLBACK_DICT_ASSET.url, ModelDownload.GRAM_URL)
        assertNotEquals(
            ModelDownload.dictZipFile(ctx.filesDir).absolutePath,
            ModelDownload.destFile(ctx.filesDir).absolutePath,
        )
        // both land in the same downloaded/ dir (picked up like the model)
        assertEquals(ModelDownload.destFile(ctx.filesDir).parentFile, ModelDownload.dictZipFile(ctx.filesDir).parentFile)
    }

    // Chinese IME behavior note.

    @Test fun dict_repo_link_points_at_the_upstream_wanxiang_repo() {
        assertEquals("https://github.com/amzxyz/rime-wanxiang", ModelDownload.DICT_REPO_URL)
        // not the release-asset download URL, and not the model repo
        assertNotEquals(ModelDownload.FALLBACK_DICT_ASSET.url, ModelDownload.DICT_REPO_URL)
        assertNotEquals(ModelDownload.REPO_URL, ModelDownload.DICT_REPO_URL)
    }

    @Test fun resource_update_sources_are_not_app_update_wiring() {
        assertEquals(
            "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/${ModelDownload.GRAM_NAME}",
            ModelDownload.GRAM_URL,
        )
        assertEquals("https://github.com/amzxyz/RIME-LMDG", ModelDownload.REPO_URL)
        assertEquals(
            "https://github.com/lurixo/Aegis/releases/download/v0.1.0-debug.13/${ModelDownload.FALLBACK_DICT_NAME}",
            ModelDownload.FALLBACK_DICT_ASSET.url,
        )
        assertEquals("https://api.github.com/repos/lurixo/Aegis/releases?per_page=100", ModelDownload.DICT_RELEASES_API_URL)
        assertEquals("https://github.com/amzxyz/rime-wanxiang", ModelDownload.DICT_REPO_URL)
        assertFalse("model updates must not point at the Aegis app repo", ModelDownload.REPO_URL.contains("lurixo/Aegis"))
        assertFalse("dictionary source link must not point at the Aegis app repo", ModelDownload.DICT_REPO_URL.contains("lurixo/Aegis"))
        assertFalse("resource downloads must not be APK self-update assets", ModelDownload.GRAM_URL.endsWith(".apk"))
        assertFalse("resource downloads must not be APK self-update assets", ModelDownload.FALLBACK_DICT_ASSET.url.endsWith(".apk"))
        assertFalse("resource discovery must not be an APK endpoint", ModelDownload.DICT_RELEASES_API_URL.endsWith(".apk"))
    }

    @Test fun resource_update_labels_are_specific_to_model_and_dictionary() {
        assertEquals("Check model updates", ctx.getString(R.string.check_model_update_button))
        assertEquals("Check dictionary updates", ctx.getString(R.string.check_dict_update_button))
        assertFalse(ctx.getString(R.string.gram_status_update_current).contains("app", ignoreCase = true))
        assertFalse(ctx.getString(R.string.dict_status_update_current).contains("app", ignoreCase = true))
        assertTrue(ctx.getString(R.string.app_version_card_description).contains("separate"))
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
        assertTrue("installed dict files are tracked for engine reload", ModelDownload.DICT_PACK_FILES.all { it in EngineAssets.ASSET_NAMES })
        assertEquals(
            File(dir, "aegis_dict.bin").absolutePath,
            EngineAssets.downloadedOverride(dir, "aegis_dict.bin")?.absolutePath,
        )
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
        // Chinese IME behavior note.
        assertTrue("never recorded → offer", ModelDownload.updateAvailable(null, "etag-1"))
        assertFalse("identical → suppress", ModelDownload.updateAvailable("etag-1", "etag-1"))
        assertTrue("differ → offer", ModelDownload.updateAvailable("etag-1", "etag-2"))
        assertTrue("remote unknown → fall back to offer", ModelDownload.updateAvailable("etag-1", null))
    }

    // ---- debug.14 Bug2: explicit-check three-state (F2 offline) + delete-race guard (F1) ----

    @Test fun update_check_distinguishes_offline_uptodate_and_update() {
        // F2: offline (remote null) is its OWN outcome — NOT a phantom update that would start a doomed download.
        assertEquals(ModelDownload.UpdateCheck.OFFLINE, ModelDownload.updateAction(true, "etag-1", null))
        assertEquals(ModelDownload.UpdateCheck.UP_TO_DATE, ModelDownload.updateAction(true, "etag-1", "etag-1"))
        assertEquals(ModelDownload.UpdateCheck.UPDATE, ModelDownload.updateAction(true, "etag-1", "etag-2"))
        assertEquals("never recorded but remote present → update", ModelDownload.UpdateCheck.UPDATE, ModelDownload.updateAction(true, null, "etag-1"))
    }

    @Test fun a_check_resolving_after_delete_is_discarded_and_never_redownloads() {
        // Chinese IME behavior note.
        // (which would otherwise have started a re-download of the just-deleted pack).
        assertNull("deleted mid-check → discard", ModelDownload.updateAction(false, null, "etag-2"))
        assertNull("deleted mid-check, differing validators → still discard", ModelDownload.updateAction(false, "etag-1", "etag-2"))
    }

    // Chinese IME behavior note.

    @Test fun associations_pref_defaults_off_and_round_trips() {
        val prefs = ctx.getSharedPreferences("aegis", android.content.Context.MODE_PRIVATE)
        // debug.17: a never-toggled user resolves to OFF via the production default constant.
        assertFalse("联想 default constant is OFF", ASSOCIATIONS_DEFAULT_ON)
        prefs.edit { remove(PREF_ASSOCIATIONS_ON) }
        assertFalse("default OFF when unset", prefs.getBoolean(PREF_ASSOCIATIONS_ON, ASSOCIATIONS_DEFAULT_ON))
        // an explicit choice still wins over the default, in both directions.
        prefs.edit { putBoolean(PREF_ASSOCIATIONS_ON, true) }
        assertTrue("explicit ON persists over the OFF default", prefs.getBoolean(PREF_ASSOCIATIONS_ON, ASSOCIATIONS_DEFAULT_ON))
        prefs.edit { putBoolean(PREF_ASSOCIATIONS_ON, false) }
        // probe with a TRUE default so this distinguishes a stored false from the framework default.
        assertFalse("explicit OFF persists (wins even over a true default)", prefs.getBoolean(PREF_ASSOCIATIONS_ON, true))
    }
}
