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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BuildInfoJsonTest {

    private val buildInfo: JSONObject by lazy {
        val path = System.getenv("AEGIS_BUILD_INFO")?.takeIf { it.isNotBlank() }
        if (System.getenv("AEGIS_DICTIONARY_RELEASE_VERIFY") == "1") {
            assertTrue("release verification requires AEGIS_BUILD_INFO", path != null)
        }
        assumeTrue("set AEGIS_BUILD_INFO to validate an external release artifact", path != null)
        val file = File(path!!)
        assertTrue("AEGIS_BUILD_INFO points to a file", file.isFile)
        JSONObject(file.readText())
    }

    @Test
    fun buildInfoJsonHasExpectedSchemaAndDictionaryAssetMetadata() {
        val dictionary = dictionaryResource()
        val asset = dictionary.getJSONObject("physical_asset")
        val source = dictionary.getJSONObject("source")
        val releaseTag = asset.getString("release_tag")
        val assetName = asset.getString("name")

        assertEquals(1, buildInfo.getInt("schema_version"))
        assertEquals("aegis.resource-build-info", buildInfo.getString("schema_name"))
        assertEquals("dictionary", dictionary.getString("kind"))
        assertEquals(ModelDownload.DICT_LATEST_TAG, releaseTag)
        assertEquals("aegis_dict_pack_$releaseTag.zip", assetName)
        assertEquals(
            "https://github.com/lurixo/Aegis/releases/download/$releaseTag/$assetName",
            asset.getString("url"),
        )
        assertTrue(asset.getString("sha256").matches(Regex("[0-9a-f]{64}")))
        assertTrue(asset.getLong("size_bytes") > 1024L)
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
        assertTrue(source.getString("tag").matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")))
        assertTrue("branch is null when pinned to a tag", source.isNull("branch"))
        assertTrue(source.getString("commit").matches(Regex("[0-9a-f]{40}")))
        assertEquals("tools/src/main/kotlin/com/aegis/tools/DictBuilder.kt", build.getString("builder_path"))
        assertEquals(1, build.getJSONObject("full_pack_parameters").getInt("min_freq"))
        assertTrue(build.getJSONObject("full_pack_parameters").isNull("max_per_key"))
        assertFalse("no seed pack is built, so no seed parameters are published", build.has("seed_parameters"))

        val fullCommands = build.getJSONObject("full_pack_parameters").getJSONArray("commands")
        for (i in 0 until fullCommands.length()) {
            assertTrue(fullCommands.getString(i).contains("--t2s-data tools/t2s-data"))
        }
        assertEquals("tools/t2s-data", build.getJSONObject("t2s_data").getString("path"))
        assertTrue(build.getJSONObject("t2s_data").getString("license").contains("Apache-2.0"))
        assertTrue(build.has("builder_tree_dirty"))
        assertTrue(build.getString("builder_commit").matches(Regex("[0-9a-f]{40}")))
        val languageModel = build.getJSONObject("language_model")
        assertEquals("AEGL v1", languageModel.getString("format"))
        assertEquals(1, languageModel.getInt("min_bigram"))
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

        assertTrue("every file the pack must carry is described", names.containsAll(ModelDownload.DICT_PACK_FILES))
        assertTrue("no bin outside the ones the app installs", ModelDownload.DICT_MANAGED_FILES.containsAll(names))
        assertEquals(names.size, bins.length())
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
        assertTrue(asset.getString("sha256").matches(Regex("[0-9a-f]{64}")))
        assertTrue(asset.getLong("size_bytes") > 1024L)
    }

    @Test
    fun currentGrammarLockMatchesTheActualTestModel() {
        val path = System.getenv("AEGIS_GRAMMAR_LOCK")?.takeIf { it.isNotBlank() }
        if (System.getenv("AEGIS_DICTIONARY_RELEASE_VERIFY") == "1") {
            assertTrue("release verification requires AEGIS_GRAMMAR_LOCK", path != null)
        }
        assumeTrue("set AEGIS_GRAMMAR_LOCK to validate current grammar", path != null)
        val lock = JSONObject(File(path!!).readText())
        assertEquals(1, lock.getInt("schema_version"))
        assertEquals("aegis.grammar-lock", lock.getString("kind"))
        assertEquals("LTS", lock.getString("release_tag"))
        val asset = lock.getJSONObject("asset")
        assertEquals(ModelDownload.GRAM_NAME, asset.getString("name"))
        assertEquals(ModelDownload.GRAM_URL, asset.getString("url"))
        assertTrue(asset.getLong("github_asset_id") > 0)
        verifyActualFile("AEGIS_GRAM", asset.getString("sha256"), asset.getLong("size_bytes"))
    }

    @Test
    fun outputComponentHashesMatchTheActualTestAssets() {
        val bins = dictionaryResource().getJSONObject("build").getJSONArray("output_bins")
        val directory = System.getenv("AEGIS_FULLDICT_DIR")
        if (System.getenv("AEGIS_DICTIONARY_RELEASE_VERIFY") == "1") {
            assertTrue("release verification requires AEGIS_FULLDICT_DIR", !directory.isNullOrBlank())
        }
        assumeTrue("set AEGIS_FULLDICT_DIR to verify dictionary bytes", !directory.isNullOrBlank())
        for (index in 0 until bins.length()) {
            val bin = bins.getJSONObject(index)
            val name = bin.getString("runtime_name")
            if (name == "aegis_english.bin") {
                verifyActualFile("AEGIS_ENGLISH", bin.getString("sha256"), bin.getLong("size_bytes"))
            } else {
                verifyFile(File(directory!!, name), bin.getString("sha256"), bin.getLong("size_bytes"))
            }
        }
    }

    @Test
    fun postprocessorProvenanceAcceptsPublishedArtifactsAndValidatesContentIdentities() {
        val build = dictionaryResource().getJSONObject("build")
        for ((section, name) in mapOf(
            "reading_gate" to "apply_reading_gate.py",
            "gb18030_level2_pinyin_support" to "inject_gb18030_pinyin_support.py",
            "english_table" to "attach_english_table.py",
        )) {
            val postprocessor = build.getJSONObject(section).getJSONObject("postprocessor")
            if (postprocessor.has("name")) {
                val expected = if (section == "english_table") setOf("name", "sha256", "builder") else setOf("name", "sha256")
                assertEquals(expected, postprocessor.keys().asSequence().toSet())
                assertEquals(name, postprocessor.getString("name"))
                assertTrue(postprocessor.getString("sha256").matches(Regex("[0-9a-f]{64}")))
                if (section == "english_table") {
                    val builder = postprocessor.getJSONObject("builder")
                    assertEquals(setOf("name", "sha256"), builder.keys().asSequence().toSet())
                    assertEquals("build_english_table.py", builder.getString("name"))
                    assertTrue(builder.getString("sha256").matches(Regex("[0-9a-f]{64}")))
                }
            } else {
                assertTrue("published legacy provenance identifies its processor", postprocessor.getString("path").endsWith("/$name"))
            }
        }
    }

    private fun verifyActualFile(variable: String, expectedHash: String, expectedSize: Long) {
        val path = System.getenv(variable)?.takeIf { it.isNotBlank() }
        if (System.getenv("AEGIS_DICTIONARY_RELEASE_VERIFY") == "1") {
            assertTrue("release verification requires $variable", path != null)
        }
        assumeTrue("set $variable to verify the external model", path != null)
        verifyFile(File(path!!), expectedHash, expectedSize)
    }

    private fun verifyFile(file: File, expectedHash: String, expectedSize: Long) {
        assertTrue("actual component exists: ${file.name}", file.isFile)
        assertEquals("actual component size: ${file.name}", expectedSize, file.length())
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        assertEquals("actual component hash: ${file.name}", expectedHash,
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }

    private fun dictionaryResource(): JSONObject {
        val resources = buildInfo.getJSONArray("resources")
        return (0 until resources.length())
            .map { resources.getJSONObject(it) }
            .first { it.getString("kind") == "dictionary" }
    }
}
