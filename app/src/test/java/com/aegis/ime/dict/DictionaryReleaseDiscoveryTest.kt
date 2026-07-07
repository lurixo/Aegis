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

import java.net.UnknownHostException
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


    @Test
    fun discoveryReadsOneRollingTagNotAScannableList() {
        assertEquals("dict-latest", ModelDownload.DICT_LATEST_TAG)
        assertEquals(
            "https://api.github.com/repos/lurixo/Aegis/releases/tags/dict-latest",
            ModelDownload.DICT_LATEST_RELEASE_API_URL,
        )
        assertTrue(ModelDownload.DICT_LATEST_RELEASE_API_URL.endsWith("/releases/tags/${ModelDownload.DICT_LATEST_TAG}"))
        assertFalse(
            "a single-tag GET, never a paged list scan an older prerelease could top",
            ModelDownload.DICT_LATEST_RELEASE_API_URL.contains("per_page"),
        )
    }


    @Test
    fun freshInstallSelectsTheDictLatestPackNotAJsonSidecar() {
        val result = ModelDownload.dictionaryUpdateFromLatestReleaseJson(
            dictLatest(packSha = sha2),
            ModelDownload.DictionaryInstallMetadata(),
        )
        assertEquals(ModelDownload.UpdateCheck.UPDATE, result.state)
        assertEquals("dict-latest", result.asset?.releaseTag)
        assertEquals("aegis_dict_pack_dict-latest.zip", result.asset?.assetName)
        assertEquals(sha2, result.asset?.sha256)
        assertTrue("the pack is a .zip, never a .json side-car", result.asset?.url.orEmpty().endsWith(".zip"))
        assertFalse(result.asset?.url.orEmpty().endsWith(".json"))
    }

    @Test
    fun installedOldShaWithNewDictLatestShaIsAnUpdate() {
        val result = ModelDownload.dictionaryUpdateFromLatestReleaseJson(
            dictLatest(packSha = sha2),
            ModelDownload.DictionaryInstallMetadata(sha256 = sha1, publishedAt = PUBLISHED),
        )
        assertEquals(ModelDownload.UpdateCheck.UPDATE, result.state)
        assertEquals(sha2, result.asset?.sha256)
    }

    @Test
    fun sameShaIsUpToDate() {
        val result = ModelDownload.dictionaryUpdateFromLatestReleaseJson(
            dictLatest(packSha = sha2),
            ModelDownload.DictionaryInstallMetadata(sha256 = sha2, publishedAt = PUBLISHED),
        )
        assertEquals(ModelDownload.UpdateCheck.UP_TO_DATE, result.state)
        assertNull(result.asset)
    }

    @Test
    fun missingDictLatestTagDuringACheckIsServerErrorNotAFalseUpToDate() {
        val result = ModelDownload.dictionaryUpdateFromFetch(
            { throw ModelDownload.HttpStatusException(404) },
            ModelDownload.DictionaryInstallMetadata(sha256 = sha1),
        )
        assertEquals(ModelDownload.UpdateCheck.SERVER_ERROR, result.state)
        assertNotEquals("a missing tag must NOT read as already up to date", ModelDownload.UpdateCheck.UP_TO_DATE, result.state)
        assertNotEquals("a missing tag must NOT read as offline", ModelDownload.UpdateCheck.OFFLINE, result.state)
        assertNull(result.asset)
    }


    @Test
    fun inPlaceRepublishWithUnchangedTimestampStillUpdates() {
        val result = ModelDownload.dictionaryUpdateFromLatestReleaseJson(
            dictLatest(packSha = sha2, published = PUBLISHED),
            ModelDownload.DictionaryInstallMetadata(sha256 = sha1, publishedAt = PUBLISHED),
        )
        assertEquals(ModelDownload.UpdateCheck.UPDATE, result.state)
        assertEquals(sha2, result.asset?.sha256)
    }

    @Test
    fun aBackwardsPublishedAtDoesNotBlockANewSha() {
        val result = ModelDownload.dictionaryUpdateFromLatestReleaseJson(
            dictLatest(packSha = sha2, published = "2026-07-01T00:00:00Z"),
            ModelDownload.DictionaryInstallMetadata(sha256 = sha1, publishedAt = PUBLISHED),
        )
        assertEquals(ModelDownload.UpdateCheck.UPDATE, result.state)
    }

    @Test
    fun sameShaIsUpToDateEvenWhenTheTimestampMovedForward() {
        val result = ModelDownload.dictionaryUpdateFromLatestReleaseJson(
            dictLatest(packSha = sha2, published = "2026-07-09T00:00:00Z"),
            ModelDownload.DictionaryInstallMetadata(sha256 = sha2, publishedAt = PUBLISHED),
        )
        assertEquals(ModelDownload.UpdateCheck.UP_TO_DATE, result.state)
        assertNull(result.asset)
    }


    @Test
    fun apkAssetsAreNeverChosenAsTheDictionaryPack() {
        val result = ModelDownload.dictionaryUpdateFromLatestReleaseJson(
            release(
                "dict-latest", prerelease = true, published = PUBLISHED,
                apkAsset("aegis-dict-latest-arm64-v8a.apk", "dict-latest", sha1),
                packAsset("aegis_dict_pack_dict-latest.zip", "dict-latest", sha2),
            ),
            ModelDownload.DictionaryInstallMetadata(),
        )
        assertEquals(ModelDownload.UpdateCheck.UPDATE, result.state)
        assertEquals(sha2, result.asset?.sha256)
        assertFalse(result.asset?.url.orEmpty().endsWith(".apk"))
    }

    @Test
    fun sourceRepoUrlIsNotThePhysicalDownloadUrl() {
        val result = ModelDownload.dictionaryUpdateFromLatestReleaseJson(
            dictLatest(packSha = sha2),
            ModelDownload.DictionaryInstallMetadata(),
        )
        val physical = result.asset?.url.orEmpty()
        assertEquals("https://github.com/amzxyz/rime-wanxiang", ModelDownload.DICT_REPO_URL)
        assertTrue(physical.startsWith("https://github.com/lurixo/Aegis/releases/download/"))
        assertNotEquals(ModelDownload.DICT_REPO_URL, physical)
    }

    @Test
    fun staleEtagValidatorIsNotAcceptedAsAnInstalledHash() {
        assertNull("old ETag validators are not accepted as installed package hashes", ModelDownload.normalizeSha256("\"0x8DED545B2B0A224\""))

        val result = ModelDownload.dictionaryUpdateFromLatestReleaseJson(
            dictLatest(packSha = sha2),
            ModelDownload.DictionaryInstallMetadata(sha256 = null, publishedAt = null),
        )
        assertEquals(ModelDownload.UpdateCheck.UPDATE, result.state)
        assertEquals(sha2, result.asset?.sha256)
    }

    @Test
    fun aReleaseWithoutAPackDuringACheckIsServerErrorNotUpToDate() {
        val result = ModelDownload.dictionaryUpdateFromFetch(
            {
                release(
                    "dict-latest", prerelease = true, published = PUBLISHED,
                    jsonAsset("aegis-build-info.json", "dict-latest", sha0),
                    jsonAsset("aegis-dictionary-update.json", "dict-latest", sha1),
                )
            },
            ModelDownload.DictionaryInstallMetadata(sha256 = sha1),
        )
        assertEquals(ModelDownload.UpdateCheck.SERVER_ERROR, result.state)
        assertNull(result.asset)
    }


    @Test
    fun freshInstallDownloadsTheDictLatestPackWhenReachable() {
        val asset = ModelDownload.resolveDictionaryDownloadAsset { dictLatest(packSha = sha2) }
        assertEquals("dict-latest", asset.releaseTag)
        assertEquals(sha2, asset.sha256)
        assertNotEquals(
            "a reachable dict-latest must not fall back to the pinned debug.13 pack",
            ModelDownload.FALLBACK_DICT_SHA256, asset.sha256,
        )
    }

    @Test
    fun offlineFreshInstallFallsBackToThePinnedPack() {
        val asset = ModelDownload.resolveDictionaryDownloadAsset { throw UnknownHostException("api.github.com") }
        assertEquals(ModelDownload.FALLBACK_DICT_ASSET, asset)
        assertEquals("v0.1.0-debug.13", asset.releaseTag)
    }

    @Test
    fun missingDictLatestTagFallsBackToThePinnedPack() {
        val asset = ModelDownload.resolveDictionaryDownloadAsset { throw ModelDownload.HttpStatusException(404) }
        assertEquals(ModelDownload.FALLBACK_DICT_ASSET, asset)
    }

    @Test
    fun aReleaseWithoutAPackFallsBackToThePinnedPack() {
        val asset = ModelDownload.resolveDictionaryDownloadAsset {
            release(
                "dict-latest", prerelease = true, published = PUBLISHED,
                jsonAsset("aegis-build-info.json", "dict-latest", sha0),
                jsonAsset("aegis-dictionary-update.json", "dict-latest", sha1),
            )
        }
        assertEquals(ModelDownload.FALLBACK_DICT_ASSET, asset)
    }

    @Test
    fun aNonReleaseOrMalformedBodyFallsBackToThePinnedPack() {
        assertEquals(ModelDownload.FALLBACK_DICT_ASSET, ModelDownload.resolveDictionaryDownloadAsset { GITHUB_ERROR_OBJECT })
        assertEquals(ModelDownload.FALLBACK_DICT_ASSET, ModelDownload.resolveDictionaryDownloadAsset { "<html>404 Not Found</html>" })
    }


    @Test
    fun debug13FallbackRemainsThePinnedOfflinePack() {
        val fallback = ModelDownload.FALLBACK_DICT_ASSET
        assertEquals("v0.1.0-debug.13", fallback.releaseTag)
        assertEquals("aegis_dict_pack_debug13.zip", fallback.assetName)
        assertEquals(ModelDownload.FALLBACK_DICT_SHA256, fallback.sha256)
        assertFalse(fallback.url.endsWith(".apk"))
        assertTrue(fallback.url.startsWith("https://github.com/lurixo/Aegis/releases/download/v0.1.0-debug.13/"))
    }


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

    private fun assetJson(name: String, tag: String, sha256: String, size: Long): String =
        """
        {
          "name": "$name",
          "size": $size,
          "digest": "sha256:$sha256",
          "browser_download_url": "https://github.com/lurixo/Aegis/releases/download/$tag/$name"
        }
        """.trimIndent()

    private fun packAsset(name: String, tag: String, sha256: String): String = assetJson(name, tag, sha256, 97_927_377L)
    private fun jsonAsset(name: String, tag: String, sha256: String): String = assetJson(name, tag, sha256, 8_250L)
    private fun apkAsset(name: String, tag: String, sha256: String): String = assetJson(name, tag, sha256, 654_321L)

    private fun dictLatest(packSha: String, tag: String = "dict-latest", published: String = PUBLISHED, prerelease: Boolean = true): String =
        release(
            tag, prerelease, published,
            jsonAsset("aegis-build-info.json", tag, "a".repeat(64)),
            jsonAsset("aegis-dictionary-update.json", tag, "b".repeat(64)),
            packAsset("aegis_dict_pack_$tag.zip", tag, packSha),
        )

    private companion object {
        const val PUBLISHED = "2026-07-06T16:15:37Z"

        const val GITHUB_ERROR_OBJECT =
            """{"message":"Not Found","documentation_url":"https://docs.github.com/rest","status":"404"}"""
    }
}
