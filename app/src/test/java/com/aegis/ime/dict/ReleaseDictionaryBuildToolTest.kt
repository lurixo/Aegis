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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseDictionaryBuildToolTest {

    @Test
    fun releaseDictionaryToolBuildsLatestFullPackMetadataWithoutUploading() {
        val script = File("../tools/release/build_dictionary_pack.py").readText()

        listOf("zi", "jichu", "lianxiang", "cuoyin", "duoyin", "shici", "diming", "yixue", "huaxue", "yaopin", "mingren", "yiren", "wuzhong", "renming").forEach {
            assertTrue("missing table $it", script.contains("\"$it\""))
        }
        assertTrue(script.contains("\"--min-freq\""))
        assertTrue(script.contains("\"1\""))
        assertTrue(script.contains("\"--keytype\""))
        assertTrue(script.contains("aegis_dict_pack_debug"))
        assertTrue(script.contains("dict-latest"))
        assertTrue(script.contains("intermediate four-component pack"))
        assertTrue(script.contains("finalize_main"))
        assertTrue(script.contains("Beta.31 LM byte reproduction failed"))
        assertTrue(script.contains("aegis_lm.bin"))
        assertFalse(script.contains(".idx"))
        assertFalse(script.contains("prefix-index"))
        assertFalse(script.contains("AEGP"))
        assertTrue(script.contains("pack_state"))
        assertTrue(script.contains("aegis-build-info.json"))
        assertTrue(script.contains("aegis-dictionary-update.json"))
        assertTrue(script.contains("sha256_file(zip_path)"))
        assertTrue(script.contains("input_yaml_sha256"))
        assertFalse(script.contains("gh release"))
        assertFalse(script.contains("same GitHub release as the APK"))
        assertFalse(script.contains("upload_url"))
        assertFalse(script.contains("GITHUB_TOKEN"))
        assertFalse(script.contains("GH_TOKEN"))
    }
}
