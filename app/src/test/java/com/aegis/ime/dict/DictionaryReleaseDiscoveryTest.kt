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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun discoveryReadsTheDedicatedRollingManifest() {
        assertEquals("dict-latest", ModelDownload.DICT_LATEST_TAG)
        assertEquals(
            "https://github.com/lurixo/Aegis/releases/download/dict-latest/aegis-dictionary-update.json",
            ModelDownload.DICT_UPDATE_URL,
        )
    }

    @Test
    fun nullablePublishedAtManifestReturnsTheResolvedPack() {
        val result = ModelDownload.dictionaryUpdateFromFetch(
            { manifest(" SHA256:${sha2.uppercase()} ") },
            ModelDownload.DictionaryInstallMetadata(sha256 = sha1),
        )

        assertEquals(ModelDownload.UpdateCheck.UPDATE, result.state)
        val asset = requireNotNull(result.asset)
        assertEquals("aegis_dict_pack_dict-latest.zip", asset.assetName)
        assertEquals(ASSET_URL, asset.url)
        assertEquals(sha2, asset.sha256)
        assertEquals(98_236_647L, asset.sizeBytes)
        assertEquals("dict-latest", asset.releaseTag)
        assertEquals("https://github.com/lurixo/Aegis/releases/tag/dict-latest", asset.releaseUrl)
        assertFalse(asset.prerelease)
        assertNull(asset.publishedAt)
    }

    @Test
    fun matchingPackHashIsUpToDate() {
        val result = ModelDownload.dictionaryUpdateFromFetch(
            { manifest(sha2) },
            ModelDownload.DictionaryInstallMetadata(sha256 = sha2),
        )

        assertEquals(ModelDownload.UpdateCheck.UP_TO_DATE, result.state)
        assertNull(result.asset)
    }

    @Test
    fun malformedLocalHashIsReportedAsUnknownNotAsAnUpdate() {
        val result = ModelDownload.dictionaryUpdateFromFetch(
            { manifest(sha2) },
            ModelDownload.DictionaryInstallMetadata(sha256 = "not-a-sha"),
        )

        assertEquals(ModelDownload.UpdateCheck.UNKNOWN, result.state)
        assertNull(result.asset)
    }

    @Test
    fun wrongManifestContractIsAParseFailure() {
        val wrongSchema = manifest(sha2).replace("\"schema_version\": 1", "\"schema_version\": 2")
        val wrongKind = manifest(sha2).replace("dictionary_update", "app_update")

        assertEquals(
            ModelDownload.UpdateCheck.PARSE_ERROR,
            ModelDownload.dictionaryUpdateFromFetch(
                { wrongSchema },
                ModelDownload.DictionaryInstallMetadata(),
            ).state,
        )
        assertEquals(
            ModelDownload.UpdateCheck.PARSE_ERROR,
            ModelDownload.dictionaryUpdateFromFetch(
                { wrongKind },
                ModelDownload.DictionaryInstallMetadata(),
            ).state,
        )
    }

    @Test
    fun mismatchedRollingReleasePathIsAParseFailure() {
        val mismatched = manifest(sha2).replace(
            "/releases/download/dict-latest/",
            "/releases/download/other-release/",
        )
        val result = ModelDownload.dictionaryUpdateFromFetch(
            { mismatched },
            ModelDownload.DictionaryInstallMetadata(sha256 = sha2),
        )

        assertEquals(ModelDownload.UpdateCheck.PARSE_ERROR, result.state)
        assertNull(result.asset)
    }

    @Test
    fun downloadDiscoveryUsesTheManifestAndKeepsTheFailureWhenUnavailable() {
        val resolved = ModelDownload.resolveDictionaryDownloadAsset { manifest(sha2) }
        val offline = ModelDownload.resolveDictionaryDownloadAsset {
            throw UnknownHostException("github.com")
        }
        val malformed = ModelDownload.resolveDictionaryDownloadAsset { "{}" }

        assertEquals(sha2, resolved.getOrNull()?.sha256)
        assertEquals(ASSET_URL, resolved.getOrNull()?.url)
        assertTrue(offline.exceptionOrNull() is UnknownHostException)
        assertTrue(malformed.isFailure)
        assertNotNull(malformed.exceptionOrNull())
    }

    private fun manifest(sha256: String): String =
        """
        {
          "schema_version": 1,
          "kind": "dictionary_update",
          "asset": {
            "name": "aegis_dict_pack_dict-latest.zip",
            "url": "$ASSET_URL",
            "release_tag": "dict-latest",
            "release_url": "https://github.com/lurixo/Aegis/releases/tag/dict-latest",
            "prerelease": false,
            "published_at": null,
            "sha256": "$sha256",
            "size_bytes": 98236647
          },
          "source": {
            "repo": "https://github.com/amzxyz/rime-wanxiang",
            "ref_type": "tag",
            "tag": "v16.1.0",
            "branch": null,
            "commit": "6c792a2e68c8382f9c63e8bed74c5cf247f1b1a9"
          }
        }
        """.trimIndent()

    private companion object {
        const val ASSET_URL =
            "https://github.com/lurixo/Aegis/releases/download/dict-latest/aegis_dict_pack_dict-latest.zip"
    }
}
