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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BuildInfoJsonTest {

    private val buildInfo = JSONObject(File("../aegis-build-info.json").readText())

    @Test
    fun buildInfoJsonHasExpectedSchemaAndDictionaryAssetMetadata() {
        val dictionary = dictionaryResource()
        val asset = dictionary.getJSONObject("physical_asset")
        val source = dictionary.getJSONObject("source")

        assertEquals(1, buildInfo.getInt("schema_version"))
        assertEquals("dictionary", dictionary.getString("kind"))
        assertEquals("aegis_dict_pack_dict-latest.zip", asset.getString("name"))
        assertEquals(
            "https://github.com/lurixo/Aegis/releases/download/dict-latest/aegis_dict_pack_dict-latest.zip",
            asset.getString("url"),
        )
        assertEquals("02c38590541921e17a36c81c1630fbffeaa6ddfbbcd8f9fbbc8396908dc75ee4", asset.getString("sha256"))
        assertEquals(98_360_489L, asset.getLong("size_bytes"))
        assertEquals("dict-latest", asset.getString("release_tag"))
        assertFalse("the rolling dictionary release is a full release", asset.getBoolean("prerelease"))
        assertEquals(ModelDownload.DICT_REPO_URL, source.getString("repo"))
        assertNotEquals("source URL and physical download URL must stay separate", source.getString("repo"), asset.getString("url"))
    }

    @Test
    fun buildInfoPinsTheVerifiedFourteenWanxiangTablesAndBuildParameters() {
        val dictionary = dictionaryResource()
        val source = dictionary.getJSONObject("source")
        val build = dictionary.getJSONObject("build")
        val tables = source.getJSONArray("tables")
        val actualTables = (0 until tables.length()).map { tables.getString(it) }

        assertEquals(
            listOf("zi", "jichu", "lianxiang", "cuoyin", "duoyin", "shici", "diming", "yixue", "huaxue", "yaopin", "mingren", "yiren", "wuzhong", "renming"),
            actualTables,
        )
        assertEquals("tag", source.getString("ref_type"))
        assertEquals("v16.2.3", source.getString("tag"))
        assertTrue("branch is null when pinned to a tag", source.isNull("branch"))
        assertEquals("c22ed3e474b67100ad4e938ee96e3b570fdc6bf1", source.getString("commit"))
        assertEquals("tools/src/main/kotlin/com/aegis/tools/DictBuilder.kt", build.getString("builder_path"))
        assertEquals(1, build.getJSONObject("full_pack_parameters").getInt("min_freq"))
        assertTrue(build.getJSONObject("full_pack_parameters").isNull("max_per_key"))
        assertEquals(400, build.getJSONObject("seed_parameters").getInt("min_freq"))
        assertTrue(build.getJSONObject("seed_parameters").isNull("max_per_key"))

        val fullCommands = build.getJSONObject("full_pack_parameters").getJSONArray("commands")
        for (i in 0 until fullCommands.length()) {
            assertTrue(fullCommands.getString(i).contains("--t2s-data tools/t2s-data"))
        }
        assertEquals(3, build.getJSONObject("seed_parameters").getInt("keep_syllable_singles"))
        assertEquals("tools/t2s-data", build.getJSONObject("t2s_data").getString("path"))
        assertTrue(build.getJSONObject("t2s_data").getString("license").contains("Apache-2.0"))
        assertTrue(build.has("builder_tree_dirty"))
        val yamlShas = source.getJSONArray("input_yaml_sha256")
        assertEquals(14, yamlShas.length())
        for (i in 0 until yamlShas.length()) {
            assertTrue(yamlShas.getJSONObject(i).getString("sha256").matches(Regex("[0-9a-f]{64}")))
        }
    }

    @Test
    fun buildInfoContainsOutputHashesAndDoesNotClaimFullReproducibility() {
        val dictionary = dictionaryResource()
        val bins = dictionary.getJSONObject("build").getJSONArray("output_bins")
        val names = (0 until bins.length()).map { bins.getJSONObject(it).getString("runtime_name") }.toSet()
        val attestation = dictionary.getJSONObject("attestation")
        val missing = attestation.getJSONArray("missing").join(" ")

        assertEquals(ModelDownload.DICT_PACK_FILES.toSet(), names)
        assertEquals(3, bins.length())
        for (i in 0 until bins.length()) {
            val bin = bins.getJSONObject(i)
            assertTrue(bin.getString("sha256").matches(Regex("[0-9a-f]{64}")))
            assertTrue(bin.getLong("size_bytes") > 1024L)
        }
        assertEquals("not_attested", attestation.getString("status"))
        assertEquals("build_inputs_recorded_but_unsigned", attestation.getString("reproducibility_status"))
        assertTrue(missing.contains("signature or attestation"))
        assertTrue(missing.contains("independent external rebuild"))
        val zip = dictionary.getJSONObject("build").getJSONObject("zip_packaging")
        assertEquals("1980-01-01T00:00:00Z", zip.getString("timestamp_utc"))
        assertEquals("zip_deflated_level_9", zip.getString("compression"))
        assertEquals("NOTICE.txt", zip.getJSONArray("file_order").getString(0))
        assertEquals("NOTICE.txt", dictionary.getJSONObject("source").getString("attribution_file_in_pack"))
    }

    @Test
    fun grammarModelReferenceRemainsAResourceReferenceNotAnAppUpdate() {
        val refs = buildInfo.getJSONArray("external_resource_references")
        val grammar = (0 until refs.length())
            .map { refs.getJSONObject(it) }
            .first { it.getString("kind") == "grammar_model" }
        val asset = grammar.getJSONObject("physical_asset")

        assertEquals(ModelDownload.GRAM_NAME, asset.getString("name"))
        assertEquals(ModelDownload.GRAM_URL, asset.getString("url"))
        assertFalse(asset.getString("url").endsWith(".apk"))
    }

    private fun dictionaryResource(): JSONObject {
        val resources = buildInfo.getJSONArray("resources")
        return (0 until resources.length())
            .map { resources.getJSONObject(it) }
            .first { it.getString("kind") == "dictionary" }
    }
}
