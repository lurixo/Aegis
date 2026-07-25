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
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictionaryReleaseDiscoveryTest {

    private val sha1 = "1".repeat(64)
    private val sha2 = "2".repeat(64)

    @Test
    fun discoveryReadsTheGitHubReleaseList() {
        assertEquals(
            "https://api.github.com/repos/lurixo/Aegis/releases?per_page=100",
            ModelDownload.DICT_RELEASES_URL,
        )
        assertFalse(ModelDownload.DICT_RELEASES_URL.endsWith(".apk"))
        assertFalse(ModelDownload.DICT_RELEASES_URL.contains("dict-latest"))
    }

    @Test
    fun newestPublishedVersionedReleaseResolvesItsManifestPack() {
        val requests = ArrayList<String>()
        val latestTag = "dict-v16.2.4-r1"
        val result = ModelDownload.dictionaryUpdateFromFetch(
            { url ->
                requests.add(url)
                if (url == ModelDownload.DICT_RELEASES_URL) {
                    releases(
                        release(latestTag, "2026-07-25T03:00:00Z", sha2),
                        release("dict-v16.2.3-r2", "2026-07-25T02:00:00Z", sha1),
                    )
                } else {
                    manifest(latestTag, "v16.2.4", sha2)
                }
            },
            ModelDownload.DictionaryInstallMetadata(sha256 = sha1),
        )

        assertEquals(ModelDownload.UpdateCheck.UPDATE, result.state)
        val asset = requireNotNull(result.asset)
        assertEquals("aegis_dict_pack_$latestTag.zip", asset.assetName)
        assertEquals(packUrl(latestTag), asset.url)
        assertEquals(sha2, asset.sha256)
        assertEquals(PACK_SIZE, asset.sizeBytes)
        assertEquals(latestTag, asset.releaseTag)
        assertEquals(releaseUrl(latestTag), asset.releaseUrl)
        assertFalse(asset.prerelease)
        assertEquals("2026-07-25T03:00:00Z", asset.publishedAt)
        assertEquals(
            listOf(ModelDownload.DICT_RELEASES_URL, manifestUrl(latestTag)),
            requests,
        )
    }

    @Test
    fun releaseListOrderDoesNotOverridePublishedAt() {
        val latestTag = "dict-v16.2.3-r2"
        val result = update(
            releases(
                release("dict-v16.2.3-r1", "2026-07-25T01:00:00Z", sha1),
                release(latestTag, "2026-07-25T03:00:00Z", sha2),
                release("dict-v16.2.4-r1", "2026-07-25T02:00:00Z", sha1),
            ),
            manifest(latestTag, "v16.2.3", sha2),
            ModelDownload.DictionaryInstallMetadata(sha256 = sha1),
        )

        assertEquals(ModelDownload.UpdateCheck.UPDATE, result.state)
        assertEquals(latestTag, result.asset?.releaseTag)
    }

    @Test
    fun matchingPackHashIsUpToDate() {
        val tag = "dict-v16.2.3-r1"
        val result = update(
            releases(release(tag, PUBLISHED, sha2)),
            manifest(tag, "v16.2.3", sha2),
            ModelDownload.DictionaryInstallMetadata(sha256 = sha2),
        )

        assertEquals(ModelDownload.UpdateCheck.UP_TO_DATE, result.state)
        assertNull(result.asset)
    }

    @Test
    fun malformedLocalHashOffersTheSelectedPack() {
        val tag = "dict-v16.2.3-r1"
        val result = update(
            releases(release(tag, PUBLISHED, sha2)),
            manifest(tag, "v16.2.3", sha2),
            ModelDownload.DictionaryInstallMetadata(sha256 = "not-a-sha"),
        )

        assertEquals(ModelDownload.UpdateCheck.UPDATE, result.state)
        assertEquals(sha2, result.asset?.sha256)
    }

    @Test
    fun frozenRollingReleaseIsNotADiscoveryFallback() {
        val rolling = release("dict-latest", "2026-07-25T04:00:00Z", sha2)
        val result = update(
            releases(rolling),
            manifest("dict-latest", "v16.2.3", sha2),
            ModelDownload.DictionaryInstallMetadata(),
        )

        assertEquals(ModelDownload.UpdateCheck.PARSE_ERROR, result.state)
        assertNull(result.asset)
    }

    @Test
    fun tiedLatestPublicationTimesAreAParseFailure() {
        val result = update(
            releases(
                release("dict-v16.2.3-r2", PUBLISHED, sha1),
                release("dict-v16.2.4-r1", PUBLISHED, sha2),
            ),
            manifest("dict-v16.2.4-r1", "v16.2.4", sha2),
            ModelDownload.DictionaryInstallMetadata(),
        )

        assertEquals(ModelDownload.UpdateCheck.PARSE_ERROR, result.state)
        assertNull(result.asset)
    }

    @Test
    fun invalidVersionedReleaseStructureDoesNotFallBack() {
        val invalidLatest = release("dict-v16.2.4-r1", "2026-07-25T03:00:00Z", sha2)
            .replace("\"size\": $BUILD_INFO_SIZE", "\"size\": 0")
        val result = update(
            releases(
                release("dict-v16.2.3-r1", "2026-07-25T02:00:00Z", sha1),
                invalidLatest,
            ),
            manifest("dict-v16.2.3-r1", "v16.2.3", sha1),
            ModelDownload.DictionaryInstallMetadata(),
        )

        assertEquals(ModelDownload.UpdateCheck.PARSE_ERROR, result.state)
        assertNull(result.asset)
    }

    @Test
    fun mismatchedManifestReleaseIsAParseFailure() {
        val tag = "dict-v16.2.3-r1"
        val mismatched = manifest(tag, "v16.2.3", sha2)
            .replace("\"release_tag\": \"$tag\"", "\"release_tag\": \"dict-v16.2.3-r2\"")
        val result = update(
            releases(release(tag, PUBLISHED, sha2)),
            mismatched,
            ModelDownload.DictionaryInstallMetadata(sha256 = sha1),
        )

        assertEquals(ModelDownload.UpdateCheck.PARSE_ERROR, result.state)
        assertNull(result.asset)
    }

    @Test
    fun manifestConnectivityFailureKeepsItsOfflineClassification() {
        val tag = "dict-v16.2.3-r1"
        val result = ModelDownload.dictionaryUpdateFromFetch(
            { url ->
                if (url == ModelDownload.DICT_RELEASES_URL) {
                    releases(release(tag, PUBLISHED, sha2))
                } else {
                    throw UnknownHostException("github.com")
                }
            },
            ModelDownload.DictionaryInstallMetadata(),
        )

        assertEquals(ModelDownload.UpdateCheck.OFFLINE, result.state)
        assertNull(result.asset)
    }

    @Test
    fun downloadDiscoveryRequiresBothReleaseListAndManifest() {
        val tag = "dict-v16.2.3-r1"
        val resolved = ModelDownload.resolveDictionaryDownloadAsset { url ->
            if (url == ModelDownload.DICT_RELEASES_URL) {
                releases(release(tag, PUBLISHED, sha2))
            } else {
                manifest(tag, "v16.2.3", sha2)
            }
        }
        val offline = ModelDownload.resolveDictionaryDownloadAsset {
            throw UnknownHostException("github.com")
        }
        val malformed = ModelDownload.resolveDictionaryDownloadAsset { "{}" }

        assertEquals(sha2, resolved?.sha256)
        assertEquals(packUrl(tag), resolved?.url)
        assertEquals(PUBLISHED, resolved?.publishedAt)
        assertNull(offline)
        assertNull(malformed)
    }

    private fun update(
        releasesJson: String,
        manifestJson: String,
        current: ModelDownload.DictionaryInstallMetadata,
    ): ModelDownload.DictionaryUpdateCheck =
        ModelDownload.dictionaryUpdateFromFetch(
            { url -> if (url == ModelDownload.DICT_RELEASES_URL) releasesJson else manifestJson },
            current,
        )

    private fun releases(vararg releases: String): String =
        releases.joinToString(prefix = "[", postfix = "]")

    private fun release(tag: String, publishedAt: String, packSha: String): String =
        """
        {
          "tag_name": "$tag",
          "html_url": "${releaseUrl(tag)}",
          "draft": false,
          "prerelease": false,
          "published_at": "$publishedAt",
          "assets": [
            {
              "name": "aegis_dict_pack_$tag.zip",
              "size": $PACK_SIZE,
              "digest": "sha256:$packSha",
              "browser_download_url": "${packUrl(tag)}"
            },
            {
              "name": "aegis-dictionary-update.json",
              "size": $MANIFEST_SIZE,
              "digest": "sha256:${"3".repeat(64)}",
              "browser_download_url": "${manifestUrl(tag)}"
            },
            {
              "name": "aegis-build-info.json",
              "size": $BUILD_INFO_SIZE,
              "digest": "sha256:${"4".repeat(64)}",
              "browser_download_url": "${buildInfoUrl(tag)}"
            }
          ]
        }
        """.trimIndent()

    private fun manifest(tag: String, sourceTag: String, sha256: String): String =
        """
        {
          "schema_version": 1,
          "kind": "dictionary_update",
          "asset": {
            "name": "aegis_dict_pack_$tag.zip",
            "url": "${packUrl(tag)}",
            "release_tag": "$tag",
            "release_url": "${releaseUrl(tag)}",
            "prerelease": false,
            "published_at": null,
            "sha256": "$sha256",
            "size_bytes": $PACK_SIZE
          },
          "source": {
            "repo": "https://github.com/amzxyz/rime-wanxiang",
            "ref_type": "tag",
            "tag": "$sourceTag",
            "branch": null,
            "commit": "${"5".repeat(40)}"
          }
        }
        """.trimIndent()

    private fun releaseUrl(tag: String) =
        "https://github.com/lurixo/Aegis/releases/tag/$tag"

    private fun packUrl(tag: String) =
        "https://github.com/lurixo/Aegis/releases/download/$tag/aegis_dict_pack_$tag.zip"

    private fun manifestUrl(tag: String) =
        "https://github.com/lurixo/Aegis/releases/download/$tag/aegis-dictionary-update.json"

    private fun buildInfoUrl(tag: String) =
        "https://github.com/lurixo/Aegis/releases/download/$tag/aegis-build-info.json"

    private companion object {
        const val PACK_SIZE = 98_236_647L
        const val MANIFEST_SIZE = 720
        const val BUILD_INFO_SIZE = 8_370
        const val PUBLISHED = "2026-07-25T03:00:00Z"
    }
}
