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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegis.ime.R
import com.aegis.ime.dict.EngineAssets
import com.aegis.ime.dict.ModelDownload
import com.aegis.ime.ui.theme.AppIconMetrics
import com.aegis.ime.ui.theme.aegisShapes
import com.aegis.ime.ui.theme.aegisTypography
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSlice1Test {

    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun app_visual_metrics_are_independent_and_keep_text_and_back_affordances_accessible() {
        assertEquals(RoundedCornerShape(12.dp), aegisShapes.medium)
        assertEquals(14.sp, aegisTypography.bodySmall.fontSize)
        assertEquals(20.sp, aegisTypography.bodySmall.lineHeight)
        assertEquals(14.sp, aegisTypography.labelLarge.fontSize)
        assertEquals(17.5.dp, AppIconMetrics.backChevronHeight)
        assertEquals(48.dp, AppIconMetrics.touchTarget)
    }


    @Test fun dict_surface_is_independent_of_the_gram_surface() {
        assertNotEquals(ModelDownload.DICT_NAME, ModelDownload.GRAM_NAME)
        assertNotEquals(ModelDownload.DICT_VALIDATOR_PREF, ModelDownload.VALIDATOR_PREF)
        assertNotEquals(ModelDownload.DICT_UPDATE_URL, ModelDownload.GRAM_URL)
        assertNotEquals(
            ModelDownload.dictZipFile(ctx.filesDir).absolutePath,
            ModelDownload.destFile(ctx.filesDir).absolutePath,
        )
        assertEquals(ModelDownload.destFile(ctx.filesDir).parentFile, ModelDownload.dictZipFile(ctx.filesDir).parentFile)
    }


    @Test fun dict_repo_link_points_at_the_upstream_wanxiang_repo() {
        assertEquals("https://github.com/amzxyz/rime-wanxiang", ModelDownload.DICT_REPO_URL)
        assertNotEquals(ModelDownload.DICT_UPDATE_URL, ModelDownload.DICT_REPO_URL)
        assertNotEquals(ModelDownload.REPO_URL, ModelDownload.DICT_REPO_URL)
    }

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

    @Test fun resource_update_labels_are_specific_to_model_and_dictionary() {
        assertEquals("Check model updates", ctx.getString(R.string.check_model_update_button))
        assertEquals("Check dictionary updates", ctx.getString(R.string.check_dict_update_button))
        assertFalse(ctx.getString(R.string.gram_status_update_current).contains("app", ignoreCase = true))
        assertFalse(ctx.getString(R.string.dict_status_update_current).contains("app", ignoreCase = true))
    }


    private fun writePackZip(dest: File, dict: String, t9: String, jianpin: String) {
        writeNamedZip(
            dest,
            listOf(
                "aegis_dict.bin" to dict,
                "aegis_t9.bin" to t9,
                "aegis_jianpin.bin" to jianpin,
                "aegis_lm.bin" to "LM".repeat(700),
            ),
        )
    }

    private fun writeNamedZip(dest: File, entries: List<Pair<String, String>>) {
        dest.parentFile?.mkdirs()
        ZipOutputStream(dest.outputStream()).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
            }
        }
    }

    @Test fun extract_dict_pack_routes_release_entry_names_and_drops_the_rest() {
        val dir = File(ctx.filesDir, "downloaded").apply { mkdirs() }
        val zip = File(dir, "release-shaped.zip")
        writeNamedZip(
            zip,
            listOf(
                "NOTICE.txt" to "N".repeat(2_000),
                "aegis_dict_full.bin" to "DICT".repeat(400),
                "aegis_t9_full.bin" to "T9".repeat(700),
                "aegis_jianpin_full.bin" to "JP".repeat(700),
                "aegis_lm.bin" to "LM".repeat(700),
                "aegis_pfx_letter.idx" to "L".repeat(2_000),
                "aegis_pfx_digit.idx" to "D".repeat(2_000),
                "aegis_pfx_initials.idx" to "I".repeat(2_000),
            ),
        )
        assertEquals(ModelDownload.DICT_PACK_FILES.toSet(), ModelDownload.extractDictPack(zip, dir))
        assertEquals("LM".repeat(700), File(dir, "aegis_lm.bin").readText())
        assertEquals("JP".repeat(700), File(dir, "aegis_jianpin.bin").readText())
        listOf("aegis_pfx_letter.idx", "aegis_pfx_digit.idx", "aegis_pfx_initials.idx", "NOTICE.txt").forEach {
            assertFalse("$it must not be installed", File(dir, it).exists())
        }
    }

    @Test fun extract_dict_pack_writes_the_renamed_pack_members() {
        val dir = File(ctx.filesDir, "downloaded").apply { mkdirs() }
        val zip = File(dir, "pack.zip")
        writePackZip(zip, "DICT".repeat(400), "T9".repeat(700), "JP".repeat(700))
        val produced = ModelDownload.extractDictPack(zip, dir)
        assertEquals(ModelDownload.DICT_PACK_FILES.toSet(), produced)
        assertTrue("every pack member present", ModelDownload.isDictDownloaded(ctx.filesDir))
        assertTrue("installed dict files are tracked for engine reload", ModelDownload.DICT_PACK_FILES.all { it in EngineAssets.ASSET_NAMES })
        assertEquals(
            File(dir, "aegis_dict.bin").absolutePath,
            EngineAssets.downloadedOverride(dir, "aegis_dict.bin")?.absolutePath,
        )
        assertEquals("DICT".repeat(400), File(dir, "aegis_dict.bin").readText())
    }

    @Test fun installing_the_pack_flips_presence_and_unlocks_every_downloaded_override() {
        ModelDownload.purgeDict(ctx.filesDir)
        val dir = File(ctx.filesDir, "downloaded").apply { mkdirs() }
        assertFalse("Chinese input is locked before the pack lands", ModelDownload.isDictDownloaded(ctx.filesDir))
        val zip = File(dir, "pack.zip")
        writePackZip(zip, "DICT".repeat(400), "T9".repeat(700), "JP".repeat(700))
        assertEquals(ModelDownload.DICT_PACK_FILES.toSet(), ModelDownload.extractDictPack(zip, dir))
        assertTrue("every pack member present flips isDictDownloaded true", ModelDownload.isDictDownloaded(ctx.filesDir))
        ModelDownload.DICT_PACK_FILES.forEach { name ->
            assertEquals(
                "downloaded $name overrides the (now absent) bundled asset, so buildEngine loads Chinese",
                File(dir, name).absolutePath,
                EngineAssets.downloadedOverride(dir, name)?.absolutePath,
            )
        }
    }

    @Test fun install_rejects_a_zip_whose_sha256_does_not_match() {
        ModelDownload.purgeDict(ctx.filesDir)
        val zip = ModelDownload.dictZipFile(ctx.filesDir)
        writePackZip(zip, "x".repeat(2048), "y".repeat(2048), "z".repeat(2048))
        assertFalse("sha256 mismatch → install rejected", ModelDownload.installDictPack(ctx.filesDir, "a".repeat(64)))
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

    @Test fun validator_comparison_semantics_reused_for_the_dict_card() {
        assertEquals(
            "never recorded → unknown",
            ModelDownload.UpdateCheck.UNKNOWN,
            ModelDownload.validatorComparison(null, "etag-1"),
        )
        assertEquals(
            "identical → suppress",
            ModelDownload.UpdateCheck.UP_TO_DATE,
            ModelDownload.validatorComparison("etag-1", "etag-1"),
        )
        assertEquals(
            "differ → offer",
            ModelDownload.UpdateCheck.UPDATE,
            ModelDownload.validatorComparison("etag-1", "etag-2"),
        )
        assertEquals(
            "remote unknown → unknown",
            ModelDownload.UpdateCheck.UNKNOWN,
            ModelDownload.validatorComparison("etag-1", null),
        )
    }


    @Test fun update_check_distinguishes_offline_uptodate_and_update() {
        assertEquals(
            ModelDownload.UpdateCheck.OFFLINE,
            ModelDownload.modelUpdateAction(true, "etag-1", ModelDownload.ValidatorProbe.Failed(ModelDownload.CheckFailure.OFFLINE)),
        )
        assertEquals(
            ModelDownload.UpdateCheck.SERVER_ERROR,
            ModelDownload.modelUpdateAction(true, "etag-1", ModelDownload.ValidatorProbe.Failed(ModelDownload.CheckFailure.SERVER)),
        )
        assertEquals(
            ModelDownload.UpdateCheck.UP_TO_DATE,
            ModelDownload.modelUpdateAction(true, "etag-1", ModelDownload.ValidatorProbe.Reached("etag-1")),
        )
        assertEquals(
            ModelDownload.UpdateCheck.UPDATE,
            ModelDownload.modelUpdateAction(true, "etag-1", ModelDownload.ValidatorProbe.Reached("etag-2")),
        )
        assertEquals(
            "never recorded but remote present → unknown, not update",
            ModelDownload.UpdateCheck.UNKNOWN,
            ModelDownload.modelUpdateAction(true, null, ModelDownload.ValidatorProbe.Reached("etag-1")),
        )
    }

    @Test fun a_check_resolving_after_delete_is_discarded_and_never_redownloads() {
        assertNull(
            "deleted mid-check → discard",
            ModelDownload.modelUpdateAction(false, null, ModelDownload.ValidatorProbe.Reached("etag-2")),
        )
        assertNull(
            "deleted mid-check, differing validators → still discard",
            ModelDownload.modelUpdateAction(false, "etag-1", ModelDownload.ValidatorProbe.Reached("etag-2")),
        )
    }


    @Test fun associations_pref_defaults_off_and_round_trips() {
        val prefs = ctx.getSharedPreferences("aegis", android.content.Context.MODE_PRIVATE)
        assertFalse("联想 default constant is OFF", ASSOCIATIONS_DEFAULT_ON)
        prefs.edit { remove(PREF_ASSOCIATIONS_ON) }
        assertFalse("default OFF when unset", prefs.getBoolean(PREF_ASSOCIATIONS_ON, ASSOCIATIONS_DEFAULT_ON))
        prefs.edit { putBoolean(PREF_ASSOCIATIONS_ON, true) }
        assertTrue("explicit ON persists over the OFF default", prefs.getBoolean(PREF_ASSOCIATIONS_ON, ASSOCIATIONS_DEFAULT_ON))
        prefs.edit { putBoolean(PREF_ASSOCIATIONS_ON, false) }
        assertFalse("explicit OFF persists (wins even over a true default)", prefs.getBoolean(PREF_ASSOCIATIONS_ON, true))
    }
}
