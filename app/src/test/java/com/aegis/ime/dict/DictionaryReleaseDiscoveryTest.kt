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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictionaryReleaseDiscoveryTest {

    private val sha0 = "0".repeat(64)
    private val sha1 = "1".repeat(64)
    private val sha2 = "2".repeat(64)
    private val sha3 = "3".repeat(64)

    @Test
    fun selectsNewestPrereleaseDictionaryAssetByPublishedAt() {
        val result = ModelDownload.dictionaryUpdateFromReleasesJson(
            releases(
                release("v0.1.0", prerelease = false, published = "2026-07-03T00:00:00Z", asset("aegis_dict_pack_v0.1.0.zip", "v0.1.0", sha3)),
                release("v0.1.0-debug.44", prerelease = true, published = "2026-07-02T00:00:00Z", asset("aegis_dict_pack_debug44.zip", "v0.1.0-debug.44", sha2)),
                release("v0.1.0-debug.13", prerelease = true, published = "2026-06-28T18:46:56Z", asset("aegis_dict_pack_debug13.zip", "v0.1.0-debug.13", sha1)),
            ),
            ModelDownload.DictionaryInstallMetadata(),
        )

        assertEquals(ModelDownload.UpdateCheck.UPDATE, result.state)
        assertEquals("v0.1.0-debug.44", result.asset?.releaseTag)
        assertEquals("aegis_dict_pack_debug44.zip", result.asset?.assetName)
        assertNotEquals("dictionary discovery must not stay pinned to debug.13", "v0.1.0-debug.13", result.asset?.releaseTag)
    }

    @Test
    fun fallsBackToNormalReleaseWhenPrereleasesProvideNoUpdate() {
        val result = ModelDownload.dictionaryUpdateFromReleasesJson(
            releases(
                release("v0.1.0-debug.44", prerelease = true, published = "2026-07-02T00:00:00Z", asset("aegis_dict_pack_debug44.zip", "v0.1.0-debug.44", sha1)),
                release("v0.1.1", prerelease = false, published = "2026-07-03T00:00:00Z", asset("aegis_dict_pack_v0.1.1.zip", "v0.1.1", sha2)),
            ),
            ModelDownload.DictionaryInstallMetadata(sha256 = sha1, publishedAt = "2026-07-02T00:00:00Z"),
        )

        assertEquals(ModelDownload.UpdateCheck.UPDATE, result.state)
        assertEquals("v0.1.1", result.asset?.releaseTag)
        assertFalse(result.asset?.prerelease ?: true)
    }

    @Test
    fun apkAssetsAreIgnoredForDictionaryDiscovery() {
        val result = ModelDownload.dictionaryUpdateFromReleasesJson(
            releases(
                release("v0.1.0-debug.44", prerelease = true, published = "2026-07-02T00:00:00Z", apkAsset("aegis-v0.1.0-debug.44-arm64-v8a.apk", "v0.1.0-debug.44", sha1)),
                release("v0.1.1", prerelease = false, published = "2026-07-03T00:00:00Z", asset("aegis_dict_pack_v0.1.1.zip", "v0.1.1", sha2)),
            ),
            ModelDownload.DictionaryInstallMetadata(),
        )

        assertEquals(ModelDownload.UpdateCheck.UPDATE, result.state)
        assertEquals("v0.1.1", result.asset?.releaseTag)
        assertFalse(result.asset?.url.orEmpty().endsWith(".apk"))
    }

    @Test
    fun sourceRepoUrlIsNotThePhysicalDownloadUrl() {
        val result = ModelDownload.dictionaryUpdateFromReleasesJson(
            releases(release("v0.1.0-debug.44", prerelease = true, published = "2026-07-02T00:00:00Z", asset("aegis_dict_pack_debug44.zip", "v0.1.0-debug.44", sha2))),
            ModelDownload.DictionaryInstallMetadata(),
        )

        val physical = result.asset?.url.orEmpty()
        assertEquals("https://github.com/amzxyz/rime-wanxiang", ModelDownload.DICT_REPO_URL)
        assertTrue(physical.startsWith("https://github.com/lurixo/Aegis/releases/download/"))
        assertNotEquals(ModelDownload.DICT_REPO_URL, physical)
    }

    @Test
    fun staleValidatorDoesNotMaskNewerValidResourceRelease() {
        assertNull("old ETag validators are not accepted as installed package hashes", ModelDownload.normalizeSha256("\"0x8DED545B2B0A224\""))

        val result = ModelDownload.dictionaryUpdateFromReleasesJson(
            releases(release("v0.1.0-debug.44", prerelease = true, published = "2026-07-02T00:00:00Z", asset("aegis_dict_pack_debug44.zip", "v0.1.0-debug.44", sha2))),
            ModelDownload.DictionaryInstallMetadata(sha256 = null, publishedAt = null),
        )

        assertEquals(ModelDownload.UpdateCheck.UPDATE, result.state)
        assertEquals(sha2, result.asset?.sha256)
    }

    @Test
    fun sameLatestKnownDictionaryReportsUpToDateWithoutDowngrading() {
        val result = ModelDownload.dictionaryUpdateFromReleasesJson(
            releases(
                release("v0.1.0-debug.44", prerelease = true, published = "2026-07-02T00:00:00Z", asset("aegis_dict_pack_debug44.zip", "v0.1.0-debug.44", sha2)),
                release("v0.1.0-debug.13", prerelease = true, published = "2026-06-28T18:46:56Z", asset("aegis_dict_pack_debug13.zip", "v0.1.0-debug.13", sha1)),
                release("v0.1.0", prerelease = false, published = "2026-06-26T00:17:36Z", asset("aegis_dict_pack_v0.1.0.zip", "v0.1.0", sha0)),
            ),
            ModelDownload.DictionaryInstallMetadata(sha256 = sha2, publishedAt = "2026-07-02T00:00:00Z"),
        )

        assertEquals(ModelDownload.UpdateCheck.UP_TO_DATE, result.state)
        assertNull(result.asset)
    }

    @Test
    fun debug13FallbackRemainsAvailableAsHistory() {
        val fallback = ModelDownload.FALLBACK_DICT_ASSET
        assertEquals("v0.1.0-debug.13", fallback.releaseTag)
        assertEquals("aegis_dict_pack_debug13.zip", fallback.assetName)
        assertEquals(ModelDownload.FALLBACK_DICT_SHA256, fallback.sha256)
        assertFalse(fallback.url.endsWith(".apk"))
    }

    private fun releases(vararg items: String): String = items.joinToString(prefix = "[", postfix = "]")

    private fun release(tag: String, prerelease: Boolean, published: String, vararg assets: String): String =
        """
        {
          "tag_name": "$tag",
          "html_url": "https://github.com/lurixo/Aegis/releases/tag/$tag",
          "prerelease": $prerelease,
          "published_at": "$published",
          "assets": [${assets.joinToString(",")}]
        }
        """.trimIndent()

    private fun asset(name: String, tag: String, sha256: String): String =
        """
        {
          "name": "$name",
          "size": 123456,
          "digest": "sha256:$sha256",
          "browser_download_url": "https://github.com/lurixo/Aegis/releases/download/$tag/$name"
        }
        """.trimIndent()

    private fun apkAsset(name: String, tag: String, sha256: String): String =
        """
        {
          "name": "$name",
          "size": 654321,
          "digest": "sha256:$sha256",
          "browser_download_url": "https://github.com/lurixo/Aegis/releases/download/$tag/$name"
        }
        """.trimIndent()
}
