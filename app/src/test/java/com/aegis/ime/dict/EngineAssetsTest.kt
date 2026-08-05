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

package com.aegis.ime.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineAssetsTest {

    private fun tempDownloadedDir(): File =
        File.createTempFile("downloaded", "").apply { delete(); mkdirs() }

    @Test
    fun tracks_exactly_the_downloaded_pack_files_plus_the_gram_model() {
        assertEquals(ModelDownload.DICT_PACK_FILES + ModelDownload.GRAM_NAME, EngineAssets.ASSET_NAMES)
        assertEquals(5, EngineAssets.ASSET_NAMES.size)
        assertTrue("aegis_dict.bin" in EngineAssets.ASSET_NAMES)
        assertTrue("wanxiang-lts-zh-hans.gram" in EngineAssets.ASSET_NAMES)
        assertTrue("aegis_lm.bin" in EngineAssets.ASSET_NAMES)
    }

    @Test
    fun stable_tree_does_not_trigger_a_reload() {
        val dir = tempDownloadedDir()
        File(dir, "aegis_dict.bin").writeText("dict")
        val built = EngineAssets.signature(dir)
        assertEquals(built, EngineAssets.signature(dir))
        assertFalse(EngineAssets.needsReload(built, EngineAssets.signature(dir)))
        dir.deleteRecursively()
    }

    @Test
    fun a_new_download_flips_the_signature() {
        val dir = tempDownloadedDir()
        val before = EngineAssets.signature(dir)
        File(dir, "aegis_dict.bin").writeText("freshly downloaded pack")
        val after = EngineAssets.signature(dir)
        assertTrue("a newly appeared file must require a reload", EngineAssets.needsReload(before, after))
        dir.deleteRecursively()
    }

    @Test
    fun downloaded_override_selects_the_same_resource_files_the_engine_tracks() {
        val dir = tempDownloadedDir()
        val dict = File(dir, "aegis_dict.bin").apply { writeText("freshly downloaded pack") }
        val gram = File(dir, "wanxiang-lts-zh-hans.gram").apply { writeBytes(ByteArray(2048)) }
        val tinyGram = File(dir, "tiny.gram").apply { writeBytes(ByteArray(1024)) }

        assertEquals(dict.absolutePath, EngineAssets.downloadedOverride(dir, "aegis_dict.bin")?.absolutePath)
        assertEquals(gram.absolutePath, EngineAssets.downloadedOverride(dir, "wanxiang-lts-zh-hans.gram", minBytes = 1025L)?.absolutePath)
        assertNull("incomplete .gram must not be activated", EngineAssets.downloadedOverride(dir, tinyGram.name, minBytes = 1025L))

        dir.deleteRecursively()
    }

    @Test
    fun a_size_change_flips_the_signature() {
        val dir = tempDownloadedDir()
        val f = File(dir, "wanxiang-lts-zh-hans.gram")
        f.writeText("small")
        val before = EngineAssets.signature(dir)
        f.writeText("a noticeably larger updated model payload")
        assertTrue("a same-second in-place size change must still reload", EngineAssets.needsReload(before, EngineAssets.signature(dir)))
        dir.deleteRecursively()
    }

    @Test
    fun an_mtime_change_flips_the_signature() {
        val dir = tempDownloadedDir()
        val f = File(dir, "aegis_t9.bin")
        f.writeText("same bytes")
        f.setLastModified(1_000_000_000_000L)
        val before = EngineAssets.signature(dir)
        f.setLastModified(1_000_000_500_000L)
        assertTrue("a re-extract with identical bytes but newer mtime must reload", EngineAssets.needsReload(before, EngineAssets.signature(dir)))
        dir.deleteRecursively()
    }

    @Test
    fun a_delete_flips_the_signature() {
        val dir = tempDownloadedDir()
        val f = File(dir, "aegis_jianpin.bin")
        f.writeText("pack")
        val present = EngineAssets.signature(dir)
        assertTrue(f.delete())
        assertTrue("removing a pack (删除) must reload (Chinese locks until re-download)", EngineAssets.needsReload(present, EngineAssets.signature(dir)))
        dir.deleteRecursively()
    }
}
