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
        assertEquals("aegis_dict_pack_debug13.zip", asset.getString("name"))
        assertEquals(ModelDownload.FALLBACK_DICT_ASSET.url, asset.getString("url"))
        assertEquals(ModelDownload.FALLBACK_DICT_SHA256, asset.getString("sha256"))
        assertEquals(98_214_288L, asset.getLong("size_bytes"))
        assertEquals("v0.1.0-debug.13", asset.getString("release_tag"))
        assertTrue(asset.getBoolean("prerelease"))
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
        assertEquals("wanxiang", source.getString("branch"))
        assertEquals("0d4eec1e7631dd55de27d2fc281a48639fec6e67", source.getString("commit"))
        assertEquals("tools/src/main/kotlin/com/aegis/tools/DictBuilder.kt", build.getString("builder_path"))
        assertEquals(1, build.getJSONObject("full_pack_parameters").getInt("min_freq"))
        assertTrue(build.getJSONObject("full_pack_parameters").isNull("max_per_key"))
        assertEquals(400, build.getJSONObject("seed_parameters").getInt("min_freq"))
        assertTrue(build.getJSONObject("seed_parameters").isNull("max_per_key"))
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
        assertEquals("partial_trace_not_fully_reproducible", attestation.getString("reproducibility_status"))
        assertTrue(missing.contains("input YAML SHA-256"))
        assertTrue(missing.contains("deterministic build recipe"))
        assertTrue(missing.contains("signature or attestation"))
        assertFalse(dictionary.getString("provenance_conclusion").contains("fully reproducible supply-chain artifact is complete"))
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
